package io.klob.wallpapers.live;

/** Turns the output-mix FFT into an onset envelope without keeping any audio samples. */
final class BassPulse {
    private static final long NO_AUDIO = -1;
    private static final long SIGNAL_HOLD_MS = 350;
    private static final long SIGNAL_FADE_MS = 650;
    private static final long MIN_PULSE_GAP_MS = 170;

    private long lastSignalAt = NO_AUDIO;
    private long signalStartedAt = NO_AUDIO;
    private long lastFrameAt = NO_AUDIO;
    private long lastPulseAt = NO_AUDIO;
    private long previousPulseAt = NO_AUDIO;
    private float bassFloor;
    private float previousBass;
    private float thresholdScale = 1f;

    void setSensitivity(int percentage) {
        thresholdScale = 1.5f - percentage / 100f;
    }

    void analyze(byte[] fft, int milliHertz, long now) {
        if (fft.length < 4 || milliHertz <= 0) return;

        // Byte pairs at bin k are the real and imaginary parts. Skip DC and Nyquist.
        float hzPerBin = milliHertz / (1000f * fft.length);
        int bassBins = 0;
        int bassPower = 0;
        int peakPower = 0;
        for (int k = 1; k < fft.length / 2; k++) {
            int real = fft[k * 2];
            int imag = fft[k * 2 + 1];
            int power = real * real + imag * imag;
            peakPower = Math.max(peakPower, power);
            float hz = k * hzPerBin;
            if (hz >= 35f && hz <= 210f) {
                bassPower += power;
                bassBins++;
            }
        }
        float bass = bassBins == 0 ? 0f : (float) Math.sqrt(bassPower / (float) bassBins);
        analyzeLevels(peakPower >= 9, bass, now);
    }

    void analyzeLevels(boolean audible, float bass, long now) {
        if (audible) {
            if (lastSignalAt == NO_AUDIO || now - lastSignalAt >= SIGNAL_HOLD_MS + SIGNAL_FADE_MS) {
                signalStartedAt = now;
                bassFloor = 0f;
                previousBass = 0f;
                lastFrameAt = NO_AUDIO;
            }
            lastSignalAt = now;
        }

        boolean aboveFloor = bass >= Math.max(3f * thresholdScale, bassFloor * (1f + 0.45f * thresholdScale));
        boolean rising = bass - previousBass >= Math.max(1.25f, previousBass * 0.15f) * thresholdScale;
        boolean pastCooldown = lastPulseAt == NO_AUDIO || now - lastPulseAt >= MIN_PULSE_GAP_MS;
        if (audible && aboveFloor && rising && pastCooldown) {
            previousPulseAt = lastPulseAt;
            lastPulseAt = now;
        }

        float adaptation = lastFrameAt == NO_AUDIO ? 0.08f
                : clamp((now - lastFrameAt) / 640f, 0.025f, 0.2f);
        bassFloor += (bass - bassFloor) * adaptation;
        previousBass = bass;
        lastFrameAt = now;
    }

    boolean hasSignal(long now) {
        return lastSignalAt != NO_AUDIO && now - lastSignalAt <= SIGNAL_HOLD_MS;
    }

    float pulse(long now, int smoothness) {
        return Math.max(envelope(lastPulseAt, now, smoothness), envelope(previousPulseAt, now, smoothness));
    }

    float wavePulse(long now, int smoothness) {
        return envelope(lastPulseAt, now, smoothness);
    }

    private static float envelope(long pulseAt, long now, int smoothness) {
        if (pulseAt == NO_AUDIO) return 0f;
        float age = now - pulseAt;
        float attack = 25f + smoothness * (20f / 35f);
        if (age < attack) return age / attack;
        float decay = 60f + smoothness * (48f / 35f);
        float elapsed = (age - attack) / decay;
        return Math.max(0f, ((float) Math.exp(-elapsed) - 0.025f) / 0.975f);
    }

    float travel(long now, int smoothness) {
        if (lastPulseAt == NO_AUDIO) return 0f;
        return clamp((now - lastPulseAt) / (230f + smoothness * 5f), 0f, 1f);
    }

    float activity(long now) {
        if (signalStartedAt == NO_AUDIO) return 0f;
        float attack = clamp((now - signalStartedAt) / 120f, 0f, 1f);
        float decay = clamp((now - lastSignalAt - SIGNAL_HOLD_MS) / (float) SIGNAL_FADE_MS, 0f, 1f);
        return attack * (1f - decay);
    }

    void reset() {
        lastSignalAt = NO_AUDIO;
        signalStartedAt = NO_AUDIO;
        lastFrameAt = NO_AUDIO;
        lastPulseAt = NO_AUDIO;
        previousPulseAt = NO_AUDIO;
        bassFloor = 0f;
        previousBass = 0f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.min(max, Math.max(min, value));
    }
}
