package com.sturq.runelitedroid.audio;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * SourceDataLine implementation that pipes PCM bytes through OpenSL ES via
 * librldroid_audio.so. One native stream handle per open()/close() cycle.
 *
 * Most controls and the more exotic Line methods are stubbed — RuneLite's
 * audio code path only uses open(format, bufferSize), write(byte[], offset,
 * len), drain(), and close().
 */
final class AndroidSourceDataLine implements SourceDataLine {

    static {
        try {
            System.loadLibrary("rldroid_audio");
        } catch (Throwable t) {
            System.err.println("[rldroid_audio] failed to load native lib: " + t);
        }
    }

    private static native long nativeOpen(int sampleRate, int channels, int bitsPerSample);
    private static native int  nativeWrite(long handle, byte[] data, int offset, int length);
    private static native void nativeDrain(long handle);
    private static native void nativeClose(long handle);

    private final AndroidMixer parent;
    private AudioFormat format;
    private long handle;
    private boolean open;
    private boolean running;
    private long framePosition;
    private final List<LineListener> listeners = new ArrayList<>();

    AndroidSourceDataLine(AndroidMixer parent, AudioFormat fmt) {
        this.parent = parent;
        this.format = fmt;
    }

    @Override
    public void open(AudioFormat format, int bufferSize) throws LineUnavailableException {
        open(format);
    }

    @Override
    public void open(AudioFormat fmt) throws LineUnavailableException {
        if (open) throw new IllegalStateException("already open");
        if (fmt == null) throw new LineUnavailableException("null format");
        int rate = (int) fmt.getSampleRate();
        int ch   = fmt.getChannels();
        int bits = fmt.getSampleSizeInBits();
        if (rate <= 0 || ch <= 0 || bits <= 0) {
            throw new LineUnavailableException("incomplete format: " + fmt);
        }
        long h = nativeOpen(rate, ch, bits);
        if (h == 0) throw new LineUnavailableException("nativeOpen returned 0 for " + fmt);
        this.handle = h;
        this.format = fmt;
        this.open = true;
        this.running = true;
        fire(LineEvent.Type.OPEN, 0);
    }

    @Override
    public void open() throws LineUnavailableException {
        open(format);
    }

    @Override
    public int write(byte[] b, int off, int len) {
        if (!open || handle == 0) return 0;
        int wrote = nativeWrite(handle, b, off, len);
        if (wrote > 0 && format.getFrameSize() > 0) {
            framePosition += wrote / format.getFrameSize();
        }
        return Math.max(0, wrote);
    }

    @Override
    public void drain() {
        if (open && handle != 0) nativeDrain(handle);
    }

    @Override
    public void flush() { /* OpenSL ES buffer clear on close handles this */ }

    @Override
    public void start() {
        if (!running) {
            running = true;
            fire(LineEvent.Type.START, framePosition);
        }
    }

    @Override
    public void stop() {
        if (running) {
            running = false;
            fire(LineEvent.Type.STOP, framePosition);
        }
    }

    @Override
    public boolean isRunning() { return open && running; }

    @Override
    public boolean isActive() { return open && running; }

    @Override
    public AudioFormat getFormat() { return format; }

    @Override
    public int getBufferSize() { return 16384; }

    @Override
    public int available() { return getBufferSize(); }

    @Override
    public int getFramePosition() { return (int) framePosition; }

    @Override
    public long getLongFramePosition() { return framePosition; }

    @Override
    public long getMicrosecondPosition() {
        float rate = format.getSampleRate();
        return rate > 0 ? (long) (framePosition * 1_000_000L / rate) : 0L;
    }

    @Override
    public float getLevel() { return AudioSystem_NOT_SPECIFIED; }

    @Override
    public Line.Info getLineInfo() {
        return new DataLine.Info(SourceDataLine.class, format);
    }

    @Override
    public void close() {
        if (!open) return;
        try { drain(); } catch (Throwable ignored) {}
        long h = handle;
        handle = 0;
        open = false;
        running = false;
        if (h != 0) nativeClose(h);
        fire(LineEvent.Type.CLOSE, framePosition);
        parent.onLineClosed(this);
    }

    @Override
    public boolean isOpen() { return open; }

    @Override
    public Control[] getControls() { return new Control[0]; }

    @Override
    public boolean isControlSupported(Control.Type control) { return false; }

    @Override
    public Control getControl(Control.Type control) {
        throw new IllegalArgumentException(String.valueOf(control));
    }

    @Override
    public void addLineListener(LineListener listener) {
        synchronized (listeners) { listeners.add(listener); }
    }

    @Override
    public void removeLineListener(LineListener listener) {
        synchronized (listeners) { listeners.remove(listener); }
    }

    private void fire(LineEvent.Type type, long pos) {
        LineEvent ev = new LineEvent(this, type, pos);
        List<LineListener> snapshot;
        synchronized (listeners) { snapshot = new ArrayList<>(listeners); }
        for (LineListener l : snapshot) {
            try { l.update(ev); } catch (Throwable ignored) {}
        }
        parent.notifyEvent(ev);
    }

    private static final float AudioSystem_NOT_SPECIFIED = -1f; // alias
}
