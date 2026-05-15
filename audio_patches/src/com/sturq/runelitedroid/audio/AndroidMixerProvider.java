package com.sturq.runelitedroid.audio;

import javax.sound.sampled.Mixer;
import javax.sound.sampled.spi.MixerProvider;

/**
 * MixerProvider implementation that exposes a single Android-backed audio
 * mixer to javax.sound.sampled. Discovered at runtime via the ServiceLoader
 * entry in META-INF/services/javax.sound.sampled.spi.MixerProvider, so the
 * bundled OpenJDK's AudioSystem.getMixer() sees us automatically.
 *
 * The bundled OpenJDK ships without libjsound.so so the default mixer
 * providers (DirectAudioDevice, PortMixer) silently fail to load. Without
 * any provider, AudioSystem.getLine() throws "No line matching ..." and
 * RuneLite logs "Audio will be unavailable". We fill the gap.
 */
public class AndroidMixerProvider extends MixerProvider {

    private static final Mixer.Info INFO = new AndroidMixerInfo();

    @Override
    public Mixer.Info[] getMixerInfo() {
        return new Mixer.Info[]{INFO};
    }

    @Override
    public Mixer getMixer(Mixer.Info info) {
        if (info == null || info.equals(INFO)) {
            return new AndroidMixer(INFO);
        }
        return null;
    }

    // Mixer.Info has a protected constructor. Subclass to get one.
    private static final class AndroidMixerInfo extends Mixer.Info {
        AndroidMixerInfo() {
            super("RuneLiteDroid Audio",
                  "sturq",
                  "Android OpenSL ES-backed PCM mixer for headless OpenJDK",
                  "1.0");
        }
    }
}
