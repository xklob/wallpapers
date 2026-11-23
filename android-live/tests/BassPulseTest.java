package io.klob.wallpapers.live;

public final class BassPulseTest {
    private static final int SAMPLE_RATE = 48_000_000;

    public static void main(String[] args) {
        kickOnsetsSurviveSilentGaps();
        steadyAudioDoesNotRetrigger();
        silenceRestoresAmbientMotion();
        spectrumUsesTheSampleRate();
        System.out.println("Bass onset, cooldown, spectrum, silence and reset checks passed.");
    }

    private static void kickOnsetsSurviveSilentGaps() {
        BassPulse detector = new BassPulse();
        detector.analyze(frame(2, 40), SAMPLE_RATE, 100);
        assertAbove(detector.pulse(145), 0.95f);
        detector.analyze(frame(2, 0), SAMPLE_RATE, 150);
        detector.analyze(frame(2, 40), SAMPLE_RATE, 220);
        assertBelow(detector.pulse(265), 0.6f);
        detector.analyze(frame(2, 0), SAMPLE_RATE, 300);
        detector.analyze(frame(2, 40), SAMPLE_RATE, 550);
        assertAbove(detector.pulse(595), 0.95f);
    }

    private static void steadyAudioDoesNotRetrigger() {
        BassPulse detector = new BassPulse();
        for (int time = 100; time < 2_000; time += 50) {
            detector.analyze(frame(2, 40), SAMPLE_RATE, time);
        }
        assertAbove(detector.activity(2_000), 0.95f);
        assertBelow(detector.pulse(2_000), 0.01f);
    }

    private static void silenceRestoresAmbientMotion() {
        BassPulse detector = new BassPulse();
        detector.analyze(frame(20, 40), SAMPLE_RATE, 100);
        detector.analyze(frame(20, 40), SAMPLE_RATE, 200);
        assertAbove(detector.activity(250), 0.99f);
        assertBelow(detector.pulse(250), 0.01f);
        detector.analyze(frame(20, 1), SAMPLE_RATE, 600);
        if (detector.hasSignal(650)) throw new AssertionError("Silence kept the signal active");
        assertBelow(detector.activity(1_200), 0.01f);
        detector.reset();
        assertBelow(detector.activity(1_201), 0.01f);
    }

    private static void spectrumUsesTheSampleRate() {
        BassPulse detector = new BassPulse();
        detector.analyze(frame(7, 40), SAMPLE_RATE, 100);
        assertBelow(detector.pulse(145), 0.01f);
        detector.reset();
        detector.analyze(frame(7, 40), SAMPLE_RATE / 2, 100);
        assertAbove(detector.pulse(145), 0.95f);
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
