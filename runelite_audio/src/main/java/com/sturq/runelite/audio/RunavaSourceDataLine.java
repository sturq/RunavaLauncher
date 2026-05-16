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
 * SourceDataLine backed by one AAudio output stream. Java write() blocks the
 * caller while AAudio drains the buffer.
 *
 * Native is in librunava_audio.so. The library is loaded lazily on first
 * open() so a missing .so doesn't prevent the SPI provider from being
 * discovered — discovery is what we need to verify before anything else.
 */
public class RunavaSourceDataLine implements SourceDataLine {

    private static volatile boolean sNativeLoaded;
    private static volatile Throwable sNativeLoadError;

    private static synchronized void ensureNative() throws LineUnavailableException {
        if (sNativeLoaded) return;
        if (sNativeLoadError != null) {
            throw new LineUnavailableException("librunava_audio.so failed earlier: " + sNativeLoadError);
        }
        // The JVM's java.library.path only points at $JAVA_HOME/lib — Android's
        // app-private nativeLibraryDir isn't in there, so System.loadLibrary
        // can't find librunava_audio.so. The Android activity passes the
        // absolute path via -Drunava.audio.library so we can System.load it.
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

    private static native long nativeOpen(int sampleRate, int channels, int bufferFrames);
    private static native int nativeWrite(long handle, byte[] buf, int offset, int len);
    private static native void nativeStart(long handle);
    private static native void nativeStop(long handle);
    private static native void nativeFlush(long handle);
    private static native void nativeClose(long handle);
    private static native long nativeGetFramePosition(long handle);

    private final RunavaAudioMixer mixer;
    private AudioFormat format;
    private long handle;
    private int bufferSize;
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<LineListener> listeners = new ArrayList<>();

    RunavaSourceDataLine(RunavaAudioMixer mixer, AudioFormat preferredFormat) {
        this.mixer = mixer;
        this.format = preferredFormat;
    }

    // ---------- DataLine / SourceDataLine ----------

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
        int frameBytes = channels * 2;
        int frames = bufferSize > 0 ? bufferSize / frameBytes : 4096;
        long h = nativeOpen((int) format.getSampleRate(), channels, frames);
        if (h == 0L) throw new LineUnavailableException("nativeOpen returned 0");
        this.handle = h;
        this.format = format;
        this.bufferSize = frames * frameBytes;
        open.set(true);
        mixer.fire(LineEvent.Type.OPEN, this, 0);
        System.out.println("[runava-audio] line opened "
                + format.getSampleRate() + "Hz x" + channels + " buf=" + frames + " frames");
    }

    @Override
    public void open(AudioFormat format) throws LineUnavailableException { open(format, -1); }

    @Override
    public void open() throws LineUnavailableException {
        if (format == null) throw new LineUnavailableException("no format set");
        open(format, -1);
    }

    @Override
    public int write(byte[] b, int off, int len) {
        if (!open.get()) return 0;
        return nativeWrite(handle, b, off, len);
    }

    @Override
    public void start() {
        if (!open.get()) return;
        nativeStart(handle);
        running.set(true);
        mixer.fire(LineEvent.Type.START, this, 0);
    }

    @Override
    public void stop() {
        if (!open.get()) return;
        nativeStop(handle);
        running.set(false);
        mixer.fire(LineEvent.Type.STOP, this, getLongFramePosition());
    }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public boolean isActive() { return running.get(); }

    @Override
    public AudioFormat getFormat() { return format; }

    @Override
    public int getBufferSize() { return bufferSize; }

    @Override
    public int available() { return bufferSize; }

    @Override
    public int getFramePosition() { return (int) getLongFramePosition(); }

    @Override
    public long getLongFramePosition() {
        if (!open.get()) return 0;
        try { return nativeGetFramePosition(handle); }
        catch (Throwable ignored) { return 0; }
    }

    @Override
    public long getMicrosecondPosition() {
        if (format == null || format.getSampleRate() <= 0) return 0;
        return (long) (getLongFramePosition() * 1_000_000.0 / format.getSampleRate());
    }

    @Override
    public float getLevel() { return -1f; }

    @Override
    public void drain() {}

    @Override
    public void flush() {
        if (!open.get()) return;
        nativeFlush(handle);
    }

    // ---------- Line ----------

    @Override
    public Line.Info getLineInfo() { return new Line.Info(SourceDataLine.class); }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        running.set(false);
        try { nativeClose(handle); } catch (Throwable ignored) {}
        handle = 0L;
        mixer.fire(LineEvent.Type.CLOSE, this, 0);
        mixer.onLineClosed(this);
    }

    @Override
    public boolean isOpen() { return open.get(); }

    @Override
    public Control[] getControls() { return new Control[0]; }

    @Override
    public boolean isControlSupported(Control.Type control) { return false; }

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
