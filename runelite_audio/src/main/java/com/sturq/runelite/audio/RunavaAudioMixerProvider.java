package com.sturq.runelite.audio;

import javax.sound.sampled.Mixer;
import javax.sound.sampled.spi.MixerProvider;

/**
 * SPI entry point. javax.sound.sampled.AudioSystem scans for this class via
 * ServiceLoader using META-INF/services/javax.sound.sampled.spi.MixerProvider.
 *
 * For the scan to find us, this jar has to sit on the bootstrap class path
 * (passed via -Xbootclasspath/a:) - that's the part the prior audio attempts
 * never got right.
 */
public class RunavaAudioMixerProvider extends MixerProvider {

    private static final Mixer.Info INFO = new Mixer.Info(
            "RunavaAudio",
            "sturq",
            "Android AAudio passthrough for javax.sound.sampled",
            "0.1") {};

    static {
        // Loud - we want to see this in logcat the instant the JRE discovers
        // us. If this line never appears, the SPI scan never reached us and
        // the classpath setup is what's broken.
        System.out.println("[runava-audio] RunavaAudioMixerProvider loaded");
        // Fire-and-forget warm-up: open and immediately close one AAudio
        // stream so the first real line.open hits the native cache instead
        // of paying full AAudio init cost on RuneLite's first sound.
        RunavaSourceDataLine.prewarmInBackground();
    }

    public RunavaAudioMixerProvider() {
        System.out.println("[runava-audio] RunavaAudioMixerProvider() instantiated");
    }

    @Override
    public Mixer.Info[] getMixerInfo() {
        return new Mixer.Info[]{INFO};
    }

    @Override
    public Mixer getMixer(Mixer.Info info) {
        if (info == null || info == INFO) {
            return new RunavaAudioMixer(INFO);
        }
        throw new IllegalArgumentException("Mixer " + info + " not supported by RunavaAudio");
    }
}
