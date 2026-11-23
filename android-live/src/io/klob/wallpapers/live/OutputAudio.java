package io.klob.wallpapers.live;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/** Shares one output-mix effect between visible previews and wallpaper engines on the main looper. */
final class OutputAudio {
    static final OutputAudio INSTANCE = new OutputAudio();

    private final Set<RgbRenderer> listeners = new HashSet<>();
    private final BassPulse pulses = new BassPulse();
    private Visualizer effect;

    private OutputAudio() {}

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    void setListening(RgbRenderer renderer, Context context, boolean listen) {
        if (!listen || !hasPermission(context)) {
            if (listeners.remove(renderer) && listeners.isEmpty()) stop();
        } else if (listeners.add(renderer) && effect == null) {
            start();
        }
    }

    boolean isAvailable() {
        return effect != null;
    }

    BassPulse pulses() {
        return pulses;
    }

    private void start() {
        Visualizer candidate = null;
        try {
            candidate = new Visualizer(0);
            requireSuccess(candidate.setCaptureSize(Visualizer.getCaptureSizeRange()[1]));
            requireSuccess(candidate.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer source, byte[] wave, int milliHertz) {}

                @Override public void onFftDataCapture(Visualizer source, byte[] fft, int milliHertz) {
                    if (source == effect) pulses.analyze(fft, milliHertz, SystemClock.uptimeMillis());
                }
            }, Visualizer.getMaxCaptureRate(), false, true));
            requireSuccess(candidate.setEnabled(true));
            effect = candidate;
        } catch (RuntimeException exception) {
            Log.w("RgbGeometryLive", "Audio output visualization is unavailable", exception);
            if (candidate != null) candidate.release();
        }
    }

    private void stop() {
        Visualizer old = effect;
        effect = null;
        pulses.reset();
        if (old != null) old.release();
    }

    private static void requireSuccess(int status) {
        if (status != Visualizer.SUCCESS) {
            throw new IllegalStateException("Visualizer setup returned " + status);
        }
    }
}
