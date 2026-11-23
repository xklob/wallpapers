package io.klob.wallpapers.live;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

/** Monitors device playback once for all visible Auto-mode renderers, on the main looper. */
final class AutoAudio {
    static final AutoAudio INSTANCE = new AutoAudio();
    private static final long CHECK_MS = 100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<RgbRenderer> listeners = new HashSet<>();
    private final AudioHandoff handoff = new AudioHandoff();
    private final Runnable check = this::checkPlayback;
    private AudioManager audioManager;
    private boolean devicePlaying;
    private boolean microphoneAllowed;

    private AutoAudio() {}

    void setListening(RgbRenderer renderer, Context context, boolean listen) {
        if (listen) {
            if (listeners.add(renderer) && listeners.size() == 1) {
                audioManager = context.getSystemService(AudioManager.class);
                checkPlayback();
            }
        } else if (listeners.remove(renderer) && listeners.isEmpty()) {
            handler.removeCallbacks(check);
            handoff.reset();
            audioManager = null;
            devicePlaying = false;
            microphoneAllowed = false;
            MicrophoneAudioService.setAutomaticListening(false);
        }
    }

    private void checkPlayback() {
        long now = SystemClock.uptimeMillis();
        // Playback activity covers quiet parts and routes that produce no usable output FFT.
        devicePlaying = (audioManager != null && audioManager.isMusicActive())
                || OutputAudio.INSTANCE.pulses().hasSignal(now);
        microphoneAllowed = handoff.update(devicePlaying, now);
        MicrophoneAudioService.setAutomaticListening(microphoneAllowed);
        handler.postDelayed(check, CHECK_MS);
    }

    boolean isDevicePlaying() {
        return devicePlaying;
    }

    boolean isUsingMicrophone() {
        return microphoneAllowed && MicrophoneAudioService.isRunning();
    }
}
