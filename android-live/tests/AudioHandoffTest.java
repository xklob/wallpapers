package io.klob.wallpapers.live;

public final class AudioHandoffTest {
    public static void main(String[] args) {
        AudioHandoff handoff = new AudioHandoff();
        expect(false, handoff.update(false, 100));
        expect(false, handoff.update(false, 1_349));
        expect(true, handoff.update(false, 1_350));
        expect(false, handoff.update(true, 1_400));
        expect(false, handoff.update(false, 1_450));
        expect(false, handoff.update(true, 1_550));
        expect(false, handoff.update(false, 1_650));
        expect(false, handoff.update(false, 2_899));
        expect(true, handoff.update(false, 2_900));
        handoff.reset();
        expect(false, handoff.update(false, 5_000));
        expect(true, handoff.update(false, 6_250));
        expect(false, handoff.update(true, 6_251));

        if (AudioSource.fromPreference(null, true) != AudioSource.MICROPHONE
                || AudioSource.fromPreference(null, false) != AudioSource.DEVICE
                || AudioSource.fromPreference("AUTO", true) != AudioSource.AUTO
                || AudioSource.fromPreference("DEVICE", true) != AudioSource.DEVICE) {
            throw new AssertionError("Stored source preferences did not migrate");
        }
        System.out.println("Playback priority, quiet delay, restart and source migration checks passed.");
    }

    private static void expect(boolean expected, boolean actual) {
        if (expected != actual) throw new AssertionError("Expected microphone=" + expected);
    }
}
