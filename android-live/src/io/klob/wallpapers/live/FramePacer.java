package io.klob.wallpapers.live;

/** Gate visible animation on display VSync without rounding 60 fps up to 17 ms. */
final class FramePacer {
    private static final long TOLERANCE_NS = 750_000;
    private long deadline;
    private int rate;

    void reset() { deadline = 0; }

    boolean ready(long vsyncNanos, int fps) {
        long interval = 1_000_000_000L / fps;
        if (deadline == 0 || rate != fps) {
            deadline = vsyncNanos + interval;
            rate = fps;
            return true;
        }
        if (vsyncNanos + TOLERANCE_NS < deadline) return false;
        deadline += interval;
        // Recover from a blocked render thread without a burst of catch-up frames.
        if (deadline <= vsyncNanos) deadline = vsyncNanos + interval;
        return true;
    }
}
