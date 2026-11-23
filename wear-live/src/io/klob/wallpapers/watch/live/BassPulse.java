package io.klob.wallpapers.watch.live;

/** Adaptive bass-band onset detector for real-time beat pulsing without retaining audio. */
final class BassPulse {
    private static final long NO_AUDIO = -1L;
    private static final long SIGNAL_HOLD_MS = 400L;
    private static final long SIGNAL_FADE_MS = 700L;
    private static final long MIN_PULSE_GAP_MS = 165L;

    private long lastSignalAt = NO_AUDIO;
    private long signalStartedAt = NO_AUDIO;
    private long lastFrameAt = NO_AUDIO;
    private long lastPulseAt = NO_AUDIO;
    private long previousPulseAt = NO_AUDIO;
    private float bassFloor;
    private float previousBass;
    private float thresholdScale = 0.85f;

    synchronized void analyzeLevels(boolean audible, float bass, long now) {
        if (audible) {
            if (lastSignalAt == NO_AUDIO || now - lastSignalAt >= SIGNAL_HOLD_MS + SIGNAL_FADE_MS) {
                signalStartedAt = now;
                bassFloor = 0f;
                previousBass = 0f;
                lastFrameAt = NO_AUDIO;
            }
            lastSignalAt = now;
        }

        boolean aboveFloor = bass >= Math.max(2.6f * thresholdScale, bassFloor * (1f + 0.40f * thresholdScale));
        boolean rising = bass - previousBass >= Math.max(1.1f, previousBass * 0.14f) * thresholdScale;
        boolean pastCooldown = lastPulseAt == NO_AUDIO || now - lastPulseAt >= MIN_PULSE_GAP_MS;
        if (audible && aboveFloor && rising && pastCooldown) {
            previousPulseAt = lastPulseAt;
            lastPulseAt = now;
        }

        float adaptation = lastFrameAt == NO_AUDIO ? 0.08f
                : clamp((now - lastFrameAt) / 600f, 0.025f, 0.2f);
        bassFloor += (bass - bassFloor) * adaptation;
        previousBass = bass;
        lastFrameAt = now;
    }

    synchronized boolean hasSignal(long now) {
        return lastSignalAt != NO_AUDIO && now - lastSignalAt <= SIGNAL_HOLD_MS;
    }

    synchronized float pulse(long now) {
        return Math.max(envelope(lastPulseAt, now), envelope(previousPulseAt, now));
    }

    synchronized float wavePulse(long now) {
        return envelope(lastPulseAt, now);
    }

    synchronized float travel(long now) {
        if (lastPulseAt == NO_AUDIO) return 0f;
        return clamp((now - lastPulseAt) / 360f, 0f, 1f);
    }

    synchronized float activity(long now) {
        if (lastPulseAt == NO_AUDIO || signalStartedAt == NO_AUDIO) return 0f;
        float attack = clamp((now - signalStartedAt) / 120f, 0f, 1f);
        float silenceDecay = clamp((now - lastSignalAt - SIGNAL_HOLD_MS) / (float) SIGNAL_FADE_MS, 0f, 1f);
        float beatDecay = clamp((now - lastPulseAt - 1_400L) / 900f, 0f, 1f);
        return attack * (1f - Math.max(silenceDecay, beatDecay));
    }

    synchronized void reset() {
        lastSignalAt = NO_AUDIO;
        signalStartedAt = NO_AUDIO;
        lastFrameAt = NO_AUDIO;
        lastPulseAt = NO_AUDIO;
        previousPulseAt = NO_AUDIO;
        bassFloor = 0f;
        previousBass = 0f;
    }

    private static float envelope(long pulseAt, long now) {
        if (pulseAt == NO_AUDIO) return 0f;
        float age = now - pulseAt;
        float attack = 32f;
        if (age < attack) return age / attack;
        float decay = 115f;
        float elapsed = (age - attack) / decay;
        return Math.max(0f, ((float) Math.exp(-elapsed) - 0.025f) / 0.975f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.min(max, Math.max(min, value));
    }
}
