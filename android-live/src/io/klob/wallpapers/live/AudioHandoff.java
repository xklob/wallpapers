package io.klob.wallpapers.live;

/** Gives playback immediate priority and waits for sustained quiet before opening the microphone. */
final class AudioHandoff {
    static final long QUIET_MS = 1_250;
    private static final long UNSET = -1;
    private long quietSince = UNSET;

    boolean update(boolean devicePlaying, long now) {
        if (devicePlaying) {
            reset();
            return false;
        }
        if (quietSince == UNSET) quietSince = now;
        return now - quietSince >= QUIET_MS;
    }

    void reset() {
        quietSince = UNSET;
    }
}
