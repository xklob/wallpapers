package io.klob.wallpapers.watch.live;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

/** Streams watch microphone PCM into BassPulse (35-210 Hz band-pass RMS) without saving audio. */
final class MicrophoneBeatTracker {
    private static final String TAG = "RgbHoneycombLive";
    private static final int SAMPLE_RATE = 16_000;
    private static final int BLOCK_SIZE = 512;
    private static final float LEVEL_SCALE = 1_000f;

    private final Context context;
    private final BassPulse pulses;
    private CaptureWorker worker;

    MicrophoneBeatTracker(Context context, BassPulse pulses) {
        this.context = context.getApplicationContext();
        this.pulses = pulses;
    }

    synchronized void start() {
        if (worker != null) return;
        CaptureWorker next = new CaptureWorker();
        worker = next;
        Thread thread = new Thread(next, "WearBassPulseMic");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        if (worker != null) {
            worker.active = false;
            worker = null;
        }
        pulses.reset();
    }

    private AudioRecord openRecorder() {
        AudioManager audio = context.getSystemService(AudioManager.class);
        boolean unprocessed = audio != null
                && "true".equals(audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] sources = unprocessed
                ? new int[] {
                        MediaRecorder.AudioSource.UNPROCESSED,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC
                }
                : new int[] {
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC
                };

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf > 0 ? minBuf : BLOCK_SIZE * 4, BLOCK_SIZE * 4);
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();

        for (int source : sources) {
            AudioRecord candidate = null;
            try {
                candidate = new AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufSize)
                        .build();
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    return candidate;
                }
            } catch (RuntimeException ignored) {
            }
            if (candidate != null) candidate.release();
        }
        return null;
    }

    private final class CaptureWorker implements Runnable {
        volatile boolean active = true;

        @Override
        public void run() {
            AudioRecord recorder = openRecorder();
            if (recorder == null) {
                Log.w(TAG, "Unable to initialize watch microphone");
                return;
            }
            try {
                if (!active) return;
                recorder.startRecording();
                if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "Watch microphone failed to start recording");
                    return;
                }
                readLoop(recorder);
            } catch (RuntimeException e) {
                Log.w(TAG, "Watch microphone capture stopped", e);
            } finally {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                } catch (RuntimeException ignored) {
                }
                recorder.release();
            }
        }

        private void readLoop(AudioRecord recorder) {
            short[] buffer = new short[BLOCK_SIZE];
            int rate = recorder.getSampleRate();
            float bassCeiling = coefficient(210f, rate);
            float bassFloor = coefficient(35f, rate);
            float low = 0f;
            float upperBass = 0f;
            int offset = 0;

            while (active) {
                int read = recorder.read(buffer, offset, BLOCK_SIZE - offset, AudioRecord.READ_NON_BLOCKING);
                if (read < 0) break;
                offset += read;
                if (offset < BLOCK_SIZE) {
                    SystemClock.sleep(10);
                    continue;
                }
                offset = 0;

                float bassPower = 0f;
                float totalPower = 0f;
                for (int i = 0; i < BLOCK_SIZE; i++) {
                    float sample = buffer[i] / 32768f;
                    upperBass += bassCeiling * (sample - upperBass);
                    low += bassFloor * (sample - low);
                    float band = upperBass - low;
                    bassPower += band * band;
                    float ac = sample - low;
                    totalPower += ac * ac;
                }
                float bass = (float) Math.sqrt(bassPower / BLOCK_SIZE) * LEVEL_SCALE;
                boolean audible = totalPower / BLOCK_SIZE >= 0.005f * 0.005f;
                pulses.analyzeLevels(audible, bass, SystemClock.uptimeMillis());
            }
        }
    }

    private static float coefficient(float hertz, int sampleRate) {
        return 1f - (float) Math.exp(-2.0 * Math.PI * hertz / sampleRate);
    }
}
