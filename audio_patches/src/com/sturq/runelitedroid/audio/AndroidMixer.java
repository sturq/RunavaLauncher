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
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/**
 * The mixer end of the SPI. We only support output (SourceDataLine) — no
 * capture, no clips, no controls. That's all RuneLite asks for and all OSRS
 * needs (PCM sample playback for sound effects and music).
 */
final class AndroidMixer implements Mixer {

    private final Mixer.Info mixerInfo;
    private final Line.Info  lineInfo;
    private final List<Line> openLines = new ArrayList<>();
    private final List<LineListener> listeners = new ArrayList<>();
    private boolean mixerOpen;

    AndroidMixer(Mixer.Info info) {
        this.mixerInfo = info;
        this.lineInfo  = new Line.Info(Mixer.class);
    }

    @Override
    public Mixer.Info getMixerInfo() { return mixerInfo; }

    @Override
    public Line.Info getLineInfo() { return lineInfo; }

    @Override
    public Line.Info[] getSourceLineInfo() {
        return new Line.Info[]{
                new DataLine.Info(SourceDataLine.class,
                        new AudioFormat[]{ANY_PCM},
                        /*minBufferSize*/ 1, /*maxBufferSize*/ AudioFormat.NOT_SPECIFIED)
        };
    }

    @Override
    public Line.Info[] getTargetLineInfo() { return new Line.Info[0]; }

    @Override
    public Line.Info[] getSourceLineInfo(Line.Info info) {
        if (info instanceof DataLine.Info) {
            DataLine.Info di = (DataLine.Info) info;
            if (SourceDataLine.class.isAssignableFrom(di.getLineClass())) {
                return getSourceLineInfo();
            }
        }
        return new Line.Info[0];
    }

    @Override
    public Line.Info[] getTargetLineInfo(Line.Info info) {
        return new Line.Info[0];
    }

    @Override
    public boolean isLineSupported(Line.Info info) {
        return getSourceLineInfo(info).length > 0;
    }

    @Override
    public Line getLine(Line.Info info) throws LineUnavailableException {
        if (!(info instanceof DataLine.Info)) {
            throw new LineUnavailableException("only DataLine.Info supported");
        }
        DataLine.Info di = (DataLine.Info) info;
        if (!SourceDataLine.class.isAssignableFrom(di.getLineClass())) {
            throw new LineUnavailableException("only SourceDataLine supported");
        }
        AudioFormat[] formats = di.getFormats();
        AudioFormat fmt = formats != null && formats.length > 0 ? formats[0] : ANY_PCM;
        AndroidSourceDataLine line = new AndroidSourceDataLine(this, fmt);
        synchronized (openLines) { openLines.add(line); }
        return line;
    }

    @Override
    public int getMaxLines(Line.Info info) {
        return isLineSupported(info) ? 16 : 0;
    }

    @Override
    public Line[] getSourceLines() {
        synchronized (openLines) { return openLines.toArray(new Line[0]); }
    }

    @Override
    public Line[] getTargetLines() { return new Line[0]; }

    @Override
    public void synchronize(Line[] lines, boolean maintainSync) { }

    @Override
    public void unsynchronize(Line[] lines) { }

    @Override
    public boolean isSynchronizationSupported(Line[] lines, boolean maintainSync) {
        return false;
    }

    @Override
    public void open() { mixerOpen = true; }

    @Override
    public void close() { mixerOpen = false; }

    @Override
    public boolean isOpen() { return mixerOpen; }

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

    void notifyEvent(LineEvent ev) {
        List<LineListener> snapshot;
        synchronized (listeners) { snapshot = new ArrayList<>(listeners); }
        for (LineListener l : snapshot) {
            try { l.update(ev); } catch (Throwable ignored) {}
        }
    }

    void onLineClosed(Line line) {
        synchronized (openLines) { openLines.remove(line); }
    }

    /** PCM placeholder for getSourceLineInfo — any-PCM matches what RuneLite
     *  passes (22050 Hz, 16-bit, stereo, little-endian). NOT_SPECIFIED on
     *  rate/channels/etc means "any value works". */
    private static final AudioFormat ANY_PCM = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            AudioFormat.NOT_SPECIFIED,
            AudioFormat.NOT_SPECIFIED,
            AudioFormat.NOT_SPECIFIED,
            AudioFormat.NOT_SPECIFIED,
            AudioFormat.NOT_SPECIFIED,
            false);
}
