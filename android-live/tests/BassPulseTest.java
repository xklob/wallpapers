package io.klob.wallpapers.live;

public final class BassPulseTest {
    private static final int SAMPLE_RATE = 48_000_000;

    public static void main(String[] args) {
        kickOnsetsSurviveSilentGaps();
        steadyAudioDoesNotRetrigger();
        silenceRestoresAmbientMotion();
        spectrumUsesTheSampleRate();
        smoothingKeepsRapidPulsesContinuous();
        raisingSensitivityDetectsAQuietKick();
        effectsHaveIndependentChannels();
        microphoneLevelsSeparateBassFromTreble();
        System.out.println("Bass onset, silence, smoothing, sensitivity, effect-channel and microphone PCM checks passed.");
    }

    private static void kickOnsetsSurviveSilentGaps() {
        BassPulse detector = new BassPulse();
        detector.analyze(frame(2, 40), SAMPLE_RATE, 100);
        assertAbove(detector.pulse(145, 35), 0.95f);
        detector.analyze(frame(2, 0), SAMPLE_RATE, 150);
        detector.analyze(frame(2, 40), SAMPLE_RATE, 220);
        assertBelow(detector.pulse(265, 35), 0.6f);
        detector.analyze(frame(2, 0), SAMPLE_RATE, 300);
        detector.analyze(frame(2, 40), SAMPLE_RATE, 550);
        assertAbove(detector.pulse(595, 35), 0.95f);
    }

    private static void steadyAudioDoesNotRetrigger() {
        BassPulse detector = new BassPulse();
        for (int time = 100; time < 2_000; time += 50) {
            detector.analyze(frame(2, 40), SAMPLE_RATE, time);
        }
        assertAbove(detector.activity(2_000), 0.95f);
        assertBelow(detector.pulse(2_000, 35), 0.01f);
    }

    private static void silenceRestoresAmbientMotion() {
        BassPulse detector = new BassPulse();
        detector.analyze(frame(20, 40), SAMPLE_RATE, 100);
        detector.analyze(frame(20, 40), SAMPLE_RATE, 200);
        assertAbove(detector.activity(250), 0.99f);
        assertBelow(detector.pulse(250, 35), 0.01f);
        detector.analyze(frame(20, 1), SAMPLE_RATE, 600);
        if (detector.hasSignal(650)) throw new AssertionError("Silence kept the signal active");
        assertBelow(detector.activity(1_200), 0.01f);
        detector.reset();
        assertBelow(detector.activity(1_201), 0.01f);
    }

    private static void spectrumUsesTheSampleRate() {
        BassPulse detector = new BassPulse();
        detector.analyze(frame(7, 40), SAMPLE_RATE, 100);
        assertBelow(detector.pulse(145, 35), 0.01f);
        detector.reset();
        detector.analyze(frame(7, 40), SAMPLE_RATE / 2, 100);
        assertAbove(detector.pulse(145, 35), 0.95f);
    }

    private static void smoothingKeepsRapidPulsesContinuous() {
        BassPulse detector = new BassPulse();
        detector.analyze(frame(2, 40), SAMPLE_RATE, 100);
        assertAbove(detector.pulse(300, 100), detector.pulse(300, 0) + 0.35f);
        detector.analyze(frame(2, 0), SAMPLE_RATE, 250);
        float before = detector.pulse(299, 100);
        detector.analyze(frame(2, 40), SAMPLE_RATE, 300);
        assertAbove(detector.pulse(300, 100), before - 0.015f);
        assertBelow(detector.wavePulse(300, 100), 0.01f);
        assertBelow(detector.travel(300, 100), 0.01f);
        assertAbove(detector.travel(665, 100), 0.49f);
        assertBelow(detector.pulse(1_200, 100), 0.01f);
    }

    private static void raisingSensitivityDetectsAQuietKick() {
        BassPulse detector = new BassPulse();
        detector.setSensitivity(0);
        detector.analyze(frame(2, 6), SAMPLE_RATE, 100);
        assertBelow(detector.pulse(145, 35), 0.01f);
        detector.reset();
        detector.setSensitivity(100);
        detector.analyze(frame(2, 6), SAMPLE_RATE, 100);
        assertAbove(detector.pulse(145, 35), 0.95f);
    }

    private static void effectsHaveIndependentChannels() {
        assertAbove(AudioEffect.PULSE_AND_COLOR.dimming, 0f);
        assertAbove(AudioEffect.PULSE_AND_COLOR.huePerMillisecond, 0f);
        assertBelow(AudioEffect.BRIGHTNESS_PULSE.huePerMillisecond, 0.00001f);
        assertBelow(AudioEffect.COLOR_ACCELERATION.dimming, 0.00001f);
        assertAbove(AudioEffect.DIAGONAL_COLOR_WAVE.waveHue, 0f);
        if (AudioEffect.fromPreference("old_effect") != AudioEffect.DEFAULT) throw new AssertionError();
    }

    private static void microphoneLevelsSeparateBassFromTreble() {
        MicrophoneLevels levels = new MicrophoneLevels(48_000);
        short[] block = new short[2_048];
        for (int index = 0; index < block.length; index++) {
            block[index] = (short) (4_000 * Math.sin(2 * Math.PI * index * 80 / 48_000));
        }
        levels.measure(block, block.length);
        if (!levels.audible) throw new AssertionError("Microphone bass was classified as silence");
        float bassLevel = levels.bass;
        assertAbove(bassLevel, 25f);
        BassPulse detector = new BassPulse();
        detector.analyzeLevels(levels.audible, bassLevel, 100);
        assertAbove(detector.pulse(145, 35), 0.95f);

        for (int index = 0; index < block.length; index++) {
            block[index] = (short) (4_000 * Math.sin(2 * Math.PI * index * 4_000 / 48_000));
        }
        levels.measure(block, block.length);
        levels.measure(block, block.length);
        if (!levels.audible) throw new AssertionError("Microphone treble was classified as silence");
        assertBelow(levels.bass, bassLevel / 12f);
        java.util.Arrays.fill(block, (short) 0);
        levels.measure(block, block.length);
        levels.measure(block, block.length);
        if (levels.audible) throw new AssertionError("Microphone silence was classified as audio");
    }

    private static byte[] frame(int bin, int magnitude) {
        byte[] fft = new byte[1024];
        fft[bin * 2] = (byte) magnitude;
        return fft;
    }

    private static void assertAbove(float value, float threshold) {
        if (!(value > threshold)) throw new AssertionError(value + " must exceed " + threshold);
    }

    private static void assertBelow(float value, float threshold) {
        if (!(value < threshold)) throw new AssertionError(value + " must be below " + threshold);
    }
}
