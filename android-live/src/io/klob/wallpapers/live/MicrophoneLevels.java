package io.klob.wallpapers.live;

/** Estimates broadband and bass RMS from one block of microphone PCM. */
final class MicrophoneLevels {
    private static final float LEVEL_SCALE = 1_000f;
    private final float bassCeiling;
    private final float bassFloor;
    private float low;
    private float upperBass;
    boolean audible;
    float bass;

    MicrophoneLevels(int sampleRate) {
        bassCeiling = coefficient(210f, sampleRate);
        bassFloor = coefficient(35f, sampleRate);
    }

    void measure(short[] samples, int length) {
        float bassPower = 0f;
        float totalPower = 0f;
        for (int index = 0; index < length; index++) {
            float sample = samples[index] / 32768f;
            upperBass += bassCeiling * (sample - upperBass);
            low += bassFloor * (sample - low);
            float band = upperBass - low;
            bassPower += band * band;
            float ac = sample - low;
            totalPower += ac * ac;
        }
        bass = (float) Math.sqrt(bassPower / length) * LEVEL_SCALE;
        audible = totalPower / length >= 0.003f * 0.003f;
    }

    private static float coefficient(float hertz, int sampleRate) {
        return 1f - (float) Math.exp(-2 * Math.PI * hertz / sampleRate);
    }
}
