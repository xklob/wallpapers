package io.klob.wallpapers.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/** An explicitly started microphone session. The recorder runs only while a wallpaper surface is visible. */
public final class MicrophoneAudioService extends Service {
    private static final String CHANNEL = "wallpaper_microphone";
    private static final String STOP_ACTION = "io.klob.wallpapers.live.STOP_MICROPHONE";
    private static final int NOTIFICATION = 17;
    private static final int SAMPLE_RATE = 48_000;
    private static final int BLOCK_SIZE = 2_048;
    private static final Set<RgbRenderer> listeners = new HashSet<>();
    private static final BassPulse pulses = new BassPulse();
    private static MicrophoneAudioService running;
    private static boolean failed;
    private static boolean automaticListening;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Capture capture;

    static boolean isRunning() {
        return running != null;
    }

    static boolean failed() {
        return failed;
    }

    static BassPulse pulses() {
        return pulses;
    }

    static void setListening(RgbRenderer renderer, boolean listen) {
        boolean changed = listen ? listeners.add(renderer) : listeners.remove(renderer);
        if (changed && running != null) running.updateCapture();
    }

    static void setAutomaticListening(boolean listen) {
        if (automaticListening == listen) return;
        automaticListening = listen;
        if (running != null) running.updateCapture();
    }

    static void start(Context context) {
        failed = false;
        context.startForegroundService(new Intent(context, MicrophoneAudioService.class));
    }

    static void stop(Context context) {
        if (running != null) context.stopService(new Intent(context, MicrophoneAudioService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP_ACTION.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "Wallpaper microphone", NotificationManager.IMPORTANCE_LOW));
        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            running = this;
            SharedPreferences preferences = Settings.open(this);
            if (Settings.audioSync(preferences) && Settings.audioSource(preferences) != AudioSource.DEVICE
                    && Settings.enabled(preferences, Settings.ANIMATE)) updateCapture();
            else stopSelf();
        } catch (SecurityException exception) {
            failed = true;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, MicrophoneAudioService.class).setAction(STOP_ACTION);
        PendingIntent stopIntent = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("Wallpaper microphone")
                .setContentText("Microphone armed while the wallpaper or preview is visible")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopIntent).build())
                .build();
    }

    private void updateCapture() {
        if (listeners.isEmpty() && !automaticListening) {
            stopCapture();
        } else if (capture == null) {
            capture = new Capture();
            new Thread(capture, "Wallpaper microphone").start();
        }
    }

    private void stopCapture() {
        if (capture != null) {
            capture.active = false;
            capture = null;
        }
        pulses.reset();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        stopCapture();
        if (running == this) running = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private final class Capture implements Runnable {
        private volatile boolean active = true;

        @Override public void run() {
            AudioRecord input = null;
            try {
                AudioManager audio = getSystemService(AudioManager.class);
                boolean unprocessed = "true".equals(audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
                AudioFormat format = new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
                int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                input = new AudioRecord.Builder().setAudioFormat(format)
                        .setAudioSource(unprocessed ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        .setBufferSizeInBytes(Math.max(minimum, BLOCK_SIZE * 4)).build();
                if (!active) return;
                input.startRecording();
                if (input.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("Microphone did not start");
                }
                readLevels(input);
            } catch (RuntimeException exception) {
                Log.w("RgbGeometryLive", "Microphone audio is unavailable", exception);
                main.post(() -> {
                    if (capture == this) {
                        failed = true;
                        stopSelf();
                    }
                });
            } finally {
                if (input != null) input.release();
            }
        }

        private void readLevels(AudioRecord input) {
            short[] buffer = new short[BLOCK_SIZE];
            MicrophoneLevels levels = new MicrophoneLevels(input.getSampleRate());
            int offset = 0;
            while (active) {
                int count = input.read(buffer, offset, BLOCK_SIZE - offset, AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new IllegalStateException("AudioRecord read returned " + count);
                offset += count;
                if (offset < BLOCK_SIZE) {
                    SystemClock.sleep(12);
                    continue;
                }
                offset = 0;
                levels.measure(buffer, buffer.length);
                boolean audible = levels.audible;
                float bass = levels.bass;
                long now = SystemClock.uptimeMillis();
                main.post(() -> {
                    if (capture == this) pulses.analyzeLevels(audible, bass, now);
                });
            }
        }
    }
}
