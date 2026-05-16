package com.sturq.runelite.audio;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One AAudio output stream for the whole JVM. Every active SourceDataLine
 * keeps a Java-side ring buffer of incoming PCM; this pump thread sums the
 * active lines into a single output buffer and writes it to one shared
 * AAudio stream.
 *
 * Why: per-line AAudio streams pushed too much load through the audio HAL
 * and the multiple drain threads competed with the game's render thread,
 * causing the visible lag at music start/transitions. With one stream and
 * one pump the audio overhead is constant regardless of how many concurrent
 * sounds RuneLite plays.
 */
public final class GlobalAudioPump {

    static final int SAMPLE_RATE = 22050;
    static final int CHANNELS = 2;
    static final int FRAME_BYTES = CHANNELS * 2; // 16-bit
    private static final int CHUNK_FRAMES = 512; // ~23ms at 22050Hz
    private static final int CHUNK_BYTES = CHUNK_FRAMES * FRAME_BYTES;
    private static final int CHUNK_SAMPLES = CHUNK_FRAMES * CHANNELS;

    private static volatile GlobalAudioPump INSTANCE;

    private final CopyOnWriteArrayList<RunavaSourceDataLine> active = new CopyOnWriteArrayList<>();
    private long handle;
    private Thread pumpThread;
    private volatile boolean shutdown;

    private GlobalAudioPump() {}

    public static synchronized GlobalAudioPump get() {
        if (INSTANCE == null) INSTANCE = new GlobalAudioPump();
        return INSTANCE;
    }

    public synchronized void prewarm() {
        ensureStreamOpen();
    }

    public synchronized void register(RunavaSourceDataLine line) {
        ensureStreamOpen();
        if (!active.contains(line)) active.add(line);
    }

    public synchronized void unregister(RunavaSourceDataLine line) {
        active.remove(line);
    }

    private void ensureStreamOpen() {
        if (handle != 0) return;
        try {
            RunavaSourceDataLine.ensureNativeLoaded();
        } catch (Throwable t) {
            System.out.println("[runava-audio] pump: native load failed: " + t);
            return;
        }
        handle = RunavaSourceDataLine.nativeOpenShared(SAMPLE_RATE, CHANNELS);
        if (handle == 0) {
            System.out.println("[runava-audio] pump: nativeOpenShared returned 0");
            return;
        }
        RunavaSourceDataLine.nativeStartShared(handle);
        pumpThread = new Thread(this::pumpLoop, "RunavaAudioPump");
        pumpThread.setDaemon(true);
        pumpThread.setPriority(Thread.MIN_PRIORITY);
        pumpThread.start();
        System.out.println("[runava-audio] pump: shared stream open + thread started");
    }

    private void pumpLoop() {
        byte[] outBuf = new byte[CHUNK_BYTES];
        int[] mix = new int[CHUNK_SAMPLES];
        while (!shutdown && handle != 0) {
            for (int i = 0; i < mix.length; i++) mix[i] = 0;

            for (RunavaSourceDataLine line : active) {
                try { line.mixInto(mix, CHUNK_FRAMES); }
                catch (Throwable ignored) {}
            }

            for (int i = 0; i < mix.length; i++) {
                int s = mix[i];
                if (s > 32767) s = 32767;
                else if (s < -32768) s = -32768;
                outBuf[i * 2] = (byte) (s & 0xFF);
                outBuf[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }

            int written = RunavaSourceDataLine.nativeWriteShared(handle, outBuf, 0, CHUNK_BYTES);
            if (written <= 0) {
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }
        }
    }
}
