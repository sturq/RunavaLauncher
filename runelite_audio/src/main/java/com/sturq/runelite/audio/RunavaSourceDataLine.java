package com.sturq.runelite.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Line;
import javax.sound.sampled.SourceDataLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SourceDataLine that hands PCM to {@link GlobalAudioPump} instead of
 * owning its own AAudio stream. write() just pushes into a per-line ring
 * buffer and returns; the pump thread resamples and mixes all active lines
 * into one shared AAudio stream. write() never blocks on AAudio.
 */
public class RunavaSourceDataLine implements SourceDataLine {

    private static volatile boolean sNativeLoaded;
    private static volatile Throwable sNativeLoadError;

    static synchronized void ensureNativeLoaded() throws LineUnavailableException {
        if (sNativeLoaded) return;
        if (sNativeLoadError != null) {
            throw new LineUnavailableException("librunava_audio.so failed earlier: " + sNativeLoadError);
        }
        String absPath = System.getProperty("runava.audio.library");
        try {
            if (absPath != null && !absPath.isEmpty()) {
                System.load(absPath);
                System.out.println("[runava-audio] librunava_audio.so loaded from " + absPath);
            } else {
                System.loadLibrary("runava_audio");
                System.out.println("[runava-audio] librunava_audio.so loaded via java.library.path");
            }
            sNativeLoaded = true;
        } catch (Throwable t) {
            sNativeLoadError = t;
            throw new LineUnavailableException("load runava_audio failed: " + t);
        }
    }

    private static void ensureNative() throws LineUnavailableException { ensureNativeLoaded(); }

    static native long nativeOpenShared(int sampleRate, int channels);
    static native void nativeStartShared(long handle);
    static native int  nativeWriteShared(long handle, byte[] buf, int offset, int len);
    static native void nativeCloseShared(long handle);

    /** Open + immediately initialise the shared AAudio stream so by the time
     *  RuneLite asks for its first line, the slow AAudio init is already done. */
    public static void prewarmInBackground() {
        Thread t = new Thread(() -> {
            try {
                ensureNativeLoaded();
                GlobalAudioPump.get().prewarm();
                System.out.println("[runava-audio] prewarm complete (shared stream)");
            } catch (Throwable t2) {
                System.out.println("[runava-audio] prewarm failed: " + t2);
            }
        }, "RunavaAudioPrewarm");
        t.setDaemon(true);
        t.start();
    }

    private final RunavaAudioMixer mixer;
    private AudioFormat format;
    private int bufferSize;
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<LineListener> listeners = new ArrayList<>();

    // Per-line ring buffer of incoming PCM bytes. write() pushes here;
    // mixInto() (called by the pump thread) reads here.
    private byte[] ring;
    private int ringCap;
    private int writePos;
    private int readPos;
    private final Object ringLock = new Object();

    // Frame-position counter so getLongFramePosition stays monotonic.
    private long framesConsumed;

    RunavaSourceDataLine(RunavaAudioMixer mixer, AudioFormat preferredFormat) {
        this.mixer = mixer;
        this.format = preferredFormat;
    }

    @Override
    public void open(AudioFormat format, int bufferSize) throws LineUnavailableException {
        if (open.get()) throw new LineUnavailableException("already open");
        ensureNative();
        if (format == null) throw new LineUnavailableException("null format");
        if (format.getSampleSizeInBits() != 16 ||
                format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED ||
                format.isBigEndian()) {
            throw new LineUnavailableException(
                    "Only PCM_SIGNED 16-bit little-endian supported, got " + format);
        }
        int channels = format.getChannels();
        if (channels != 1 && channels != 2) {
            throw new LineUnavailableException("Only mono/stereo, got " + channels);
        }
        int frameBytes = channels * 2;
        // ~250ms of input audio. Big enough that the pump thread always has
        // something to mix (no underruns under normal scheduling), small
        // enough that play-after-write latency stays well under 300ms.
        int ringCapFrames = (int) format.getSampleRate() / 4;
        this.ringCap = ringCapFrames * frameBytes;
        this.ring = new byte[ringCap];
        this.writePos = 0;
        this.readPos = 0;
        this.framesConsumed = 0;
        this.format = format;
        this.bufferSize = bufferSize > 0 ? bufferSize : ringCap;
        open.set(true);
        GlobalAudioPump.get().register(this);
        mixer.fire(LineEvent.Type.OPEN, this, 0);
        System.out.println("[runava-audio] line opened "
                + format.getSampleRate() + "Hz x" + channels);
    }

    @Override public void open(AudioFormat format) throws LineUnavailableException { open(format, -1); }

    @Override public void open() throws LineUnavailableException {
        if (format == null) throw new LineUnavailableException("no format set");
        open(format, -1);
    }

    @Override
    public int write(byte[] b, int off, int len) {
        if (!open.get() || len <= 0) return 0;
        int written = 0;
        synchronized (ringLock) {
            while (written < len && open.get()) {
                int used = (writePos >= readPos) ? (writePos - readPos) : (ringCap - readPos + writePos);
                int free = ringCap - used - 1;
                if (free <= 0) {
                    try { ringLock.wait(); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return written;
                    }
                    continue;
                }
                int toCopy = Math.min(len - written, free);
                if (writePos + toCopy <= ringCap) {
                    System.arraycopy(b, off + written, ring, writePos, toCopy);
                } else {
                    int firstPart = ringCap - writePos;
                    System.arraycopy(b, off + written, ring, writePos, firstPart);
                    System.arraycopy(b, off + written + firstPart, ring, 0, toCopy - firstPart);
                }
                writePos = (writePos + toCopy) % ringCap;
                written += toCopy;
                ringLock.notifyAll();
            }
        }
        return written;
    }

    /** Pump-thread entry point. Pull up to {@code outFrames} of stereo
     *  22050Hz audio out of the ring buffer, resampling / channel-mixing as
     *  needed, and add the samples into {@code mix} (2*outFrames interleaved
     *  int32 accumulator). Called only by GlobalAudioPump. */
    void mixInto(int[] mix, int outFrames) {
        if (!running.get() || ring == null) return;
        int srcRate = (int) format.getSampleRate();
        int srcCh = format.getChannels();
        int srcFrameBytes = srcCh * 2;
        int outRate = GlobalAudioPump.SAMPLE_RATE;
        synchronized (ringLock) {
            int used = (writePos >= readPos) ? (writePos - readPos) : (ringCap - readPos + writePos);
            int availFrames = used / srcFrameBytes;
            if (availFrames == 0) return;

            // How many source frames will be advanced after writing outFrames
            // of output (so the next call picks up where we left off).
            int srcFramesToAdvance = (int) (((long) outFrames * srcRate + outRate - 1) / outRate);
            if (srcFramesToAdvance > availFrames) srcFramesToAdvance = availFrames;

            int rp = readPos;
            for (int outIdx = 0; outIdx < outFrames; outIdx++) {
                int srcIdx = (int) ((long) outIdx * srcRate / outRate);
                if (srcIdx >= srcFramesToAdvance) break;
                int frameByteOffset = srcIdx * srcFrameBytes;
                int a0 = (rp + frameByteOffset) % ringCap;
                int a1 = (a0 + 1) % ringCap;
                int sL = (short) ((ring[a0] & 0xFF) | (ring[a1] << 8));
                int sR;
                if (srcCh == 1) {
                    sR = sL;
                } else {
                    int a2 = (a0 + 2) % ringCap;
                    int a3 = (a0 + 3) % ringCap;
                    sR = (short) ((ring[a2] & 0xFF) | (ring[a3] << 8));
                }
                mix[outIdx * 2]     += sL;
                mix[outIdx * 2 + 1] += sR;
            }

            readPos = (readPos + srcFramesToAdvance * srcFrameBytes) % ringCap;
            framesConsumed += srcFramesToAdvance;
            ringLock.notifyAll();
        }
    }

    @Override public void start() {
        if (!open.get()) return;
        running.set(true);
        mixer.fire(LineEvent.Type.START, this, 0);
    }

    @Override public void stop() {
        running.set(false);
        mixer.fire(LineEvent.Type.STOP, this, getLongFramePosition());
    }

    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isActive() { return running.get(); }
    @Override public AudioFormat getFormat() { return format; }
    @Override public int getBufferSize() { return bufferSize; }

    @Override
    public int available() {
        synchronized (ringLock) {
            int used = (writePos >= readPos) ? (writePos - readPos) : (ringCap - readPos + writePos);
            return ringCap - used - 1;
        }
    }

    @Override public int getFramePosition() { return (int) framesConsumed; }
    @Override public long getLongFramePosition() { return framesConsumed; }

    @Override
    public long getMicrosecondPosition() {
        if (format == null || format.getSampleRate() <= 0) return 0;
        return (long) (framesConsumed * 1_000_000.0 / format.getSampleRate());
    }

    @Override public float getLevel() { return -1f; }
    @Override public void drain() {}

    @Override
    public void flush() {
        synchronized (ringLock) {
            readPos = writePos;
            ringLock.notifyAll();
        }
    }

    @Override public Line.Info getLineInfo() { return new Line.Info(SourceDataLine.class); }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        running.set(false);
        GlobalAudioPump.get().unregister(this);
        synchronized (ringLock) {
            ringLock.notifyAll();
        }
        ring = null;
        mixer.fire(LineEvent.Type.CLOSE, this, 0);
        mixer.onLineClosed(this);
    }

    @Override public boolean isOpen() { return open.get(); }
    @Override public Control[] getControls() { return new Control[0]; }
    @Override public boolean isControlSupported(Control.Type control) { return false; }

    @Override
    public Control getControl(Control.Type control) {
        throw new IllegalArgumentException("No controls");
    }

    @Override
    public void addLineListener(LineListener listener) {
        synchronized (listeners) { listeners.add(listener); }
    }

    @Override
    public void removeLineListener(LineListener listener) {
        synchronized (listeners) { listeners.remove(listener); }
    }
}
