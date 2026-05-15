package com.sturq.runelite.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import java.util.ArrayList;
import java.util.List;

/**
 * One Mixer that exposes a SourceDataLine backed by AAudio. RuneLite opens
 * one SourceDataLine per sound effect / music track; AAudio mixes the
 * concurrent streams in the kernel.
 */
public class RunavaAudioMixer implements Mixer {

    /** Wide format set — accept any reasonable PCM_SIGNED 16-bit little-endian
     *  config. RuneLite picks 22050 / 44100 / 48000 Hz mono or stereo; we
     *  pass whatever it asks for straight through to AAudio. */
    private static final Line.Info SOURCE_LINE_INFO = new DataLine.Info(
            SourceDataLine.class,
            new AudioFormat[]{
                    new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                            AudioFormat.NOT_SPECIFIED, 16, 1, 2,
                            AudioFormat.NOT_SPECIFIED, false),
                    new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                            AudioFormat.NOT_SPECIFIED, 16, 2, 4,
                            AudioFormat.NOT_SPECIFIED, false),
            });

    private final Mixer.Info info;
    private final List<LineListener> listeners = new ArrayList<>();
    private final List<RunavaSourceDataLine> openLines = new ArrayList<>();
    private volatile boolean open;

    RunavaAudioMixer(Mixer.Info info) {
        this.info = info;
    }

    @Override
    public Mixer.Info getMixerInfo() { return info; }

    @Override
    public Line.Info[] getSourceLineInfo() {
        return new Line.Info[]{SOURCE_LINE_INFO};
    }

    @Override
    public Line.Info[] getTargetLineInfo() { return new Line.Info[0]; }

    @Override
    public Line.Info[] getSourceLineInfo(Line.Info info) {
        if (info != null && info.matches(SOURCE_LINE_INFO)) return new Line.Info[]{SOURCE_LINE_INFO};
        return new Line.Info[0];
    }

    @Override
    public Line.Info[] getTargetLineInfo(Line.Info info) { return new Line.Info[0]; }

    @Override
    public boolean isLineSupported(Line.Info info) {
        return info != null && info.matches(SOURCE_LINE_INFO);
    }

    @Override
    public Line getLine(Line.Info info) throws LineUnavailableException {
        if (!isLineSupported(info)) {
            throw new LineUnavailableException("Unsupported line: " + info);
        }
        AudioFormat format = null;
        if (info instanceof DataLine.Info) {
            AudioFormat[] formats = ((DataLine.Info) info).getFormats();
            if (formats != null && formats.length > 0) format = formats[0];
        }
        RunavaSourceDataLine line = new RunavaSourceDataLine(this, format);
        synchronized (openLines) { openLines.add(line); }
        return line;
    }

    @Override
    public int getMaxLines(Line.Info info) {
        return isLineSupported(info) ? 32 : 0;
    }

    @Override
    public Line[] getSourceLines() {
        synchronized (openLines) { return openLines.toArray(new Line[0]); }
    }

    @Override
    public Line[] getTargetLines() { return new Line[0]; }

    @Override
    public void synchronize(Line[] lines, boolean maintain) {
        throw new IllegalArgumentException("synchronize not supported");
    }

    @Override
    public void unsynchronize(Line[] lines) {}

    @Override
    public boolean isSynchronizationSupported(Line[] lines, boolean maintain) { return false; }

    // ---------- Line ----------

    @Override
    public Line.Info getLineInfo() { return new Line.Info(Mixer.class); }

    @Override
    public void open() { open = true; }

    @Override
    public void close() {
        open = false;
        synchronized (openLines) {
            for (RunavaSourceDataLine l : new ArrayList<>(openLines)) {
                try { l.close(); } catch (Throwable ignored) {}
            }
            openLines.clear();
        }
    }

    @Override
    public boolean isOpen() { return open; }

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

    void fire(LineEvent.Type type, Line source, long position) {
        LineEvent evt = new LineEvent(source, type, position);
        List<LineListener> copy;
        synchronized (listeners) { copy = new ArrayList<>(listeners); }
        for (LineListener l : copy) {
            try { l.update(evt); } catch (Throwable ignored) {}
        }
    }

    void onLineClosed(RunavaSourceDataLine line) {
        synchronized (openLines) { openLines.remove(line); }
    }
}
