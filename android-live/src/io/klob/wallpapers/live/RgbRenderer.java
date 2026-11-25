package io.klob.wallpapers.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.WindowMetrics;

import java.io.IOException;

final class RgbRenderer {
    static final long FRAME_INTERVAL_MS = 33;
    private static final String SHADER =
            "uniform shader art;\n"
            + "uniform float phase;\n"
            + "uniform float gain;\n"
            + "uniform float4 audioWave;\n"
            + "half4 main(float2 p) {\n"
            + "    float3 c = float3(art.eval(p).rgb);\n"
            + "    float hi = max(max(c.r, c.g), c.b);\n"
            + "    float lo = min(min(c.r, c.g), c.b);\n"
            + "    float delta = hi - lo;\n"
            + "    if (delta < 0.0001 || (phase == 0.0 && audioWave.x == 0.0))\n"
            + "        return half4(half3(c * gain), 1.0);\n"
            + "    float h = hi == c.r ? (c.g - c.b) / delta\n"
            + "            : hi == c.g ? 2.0 + (c.b - c.r) / delta\n"
            + "            : 4.0 + (c.r - c.g) / delta;\n"
            + "    float offset = phase;\n"
            + "    if (audioWave.x > 0.0) {\n"
            + "        float position = (p.x + p.y - audioWave.z) * audioWave.w;\n"
            + "        float band = 1.0 - smoothstep(0.0, 0.25, abs(position - audioWave.y));\n"
            + "        offset += audioWave.x * band;\n"
            + "    }\n"
            + "    float3 wheel = clamp(abs(fract(h / 6.0 + offset +\n"
            + "            float3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);\n"
            + "    return half4(half3((lo + delta * wheel) * gain), 1.0);\n"
            + "}";

    private final Context context;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private final RuntimeShader shader = createShader();
    private Bitmap bitmap;
    private BitmapShader input;
    private String artPath, rotatedPath;
    private Catalog.Model model, rotatedModel;
    private PanelCorners corners;
    private int layoutWidth;
    private int layoutHeight;
    private int periodMillis = 18000;
    private boolean animate = true;
    private boolean audioSync;
    private AudioSource audioSource = AudioSource.DEVICE;
    private AudioEffect audioEffect = AudioEffect.DEFAULT;
    private float audioIntensity = Settings.DEFAULT_INTENSITY / 100f;
    private int audioSmoothness = Settings.DEFAULT_SMOOTHNESS;
    private boolean visible;
    private float phase;
    private long phaseTime;

    RgbRenderer(Context context) {
        this.context = context.getApplicationContext();
    }

    private static RuntimeShader createShader() {
        try {
            return new RuntimeShader(SHADER);
        } catch (IllegalArgumentException exception) {
            Log.e("RgbGeometryLive", "Could not initialize the hue shader", exception);
            return null;
        }
    }

    void update(SharedPreferences preferences, WallpaperScreen screen) {
        Catalog.Model selected = Settings.model(context, preferences, screen);
        Catalog.Model rotated = Catalog.get(context).forOrientation(selected, selected.width <= selected.height);
        String path = Settings.artPath(selected, preferences, screen);
        String other = rotated == selected ? path : Settings.artPath(rotated, preferences, screen);
        if (selected != model || !path.equals(artPath) || !other.equals(rotatedPath)) {
            model = selected;
            rotatedModel = rotated;
            artPath = path;
            rotatedPath = other;
            layoutWidth = layoutHeight = 0;
        }

        advance(SystemClock.uptimeMillis());
        periodMillis = Settings.period(preferences) * 1000;
        setAnimation(Settings.enabled(preferences, Settings.ANIMATE));
        audioSync = Settings.audioSync(preferences);
        audioSource = Settings.audioSource(preferences);
        audioEffect = Settings.audioEffect(preferences);
        audioIntensity = Settings.percentage(preferences, Settings.AUDIO_INTENSITY, Settings.DEFAULT_INTENSITY) / 100f;
        audioSmoothness = Settings.percentage(preferences, Settings.AUDIO_SMOOTHNESS, Settings.DEFAULT_SMOOTHNESS);
        int sensitivity = Settings.percentage(preferences, Settings.BASS_SENSITIVITY, Settings.DEFAULT_SENSITIVITY);
        OutputAudio.INSTANCE.pulses().setSensitivity(sensitivity);
        MicrophoneAudioService.pulses().setSensitivity(sensitivity);
        updateAudio();
    }

    void setPanel(Display display, WindowMetrics metrics) {
        PanelCorners next = PanelCorners.read(display, metrics);
        if (corners == null ? next == null : corners.sameGeometry(next)) return;
        corners = next;
        layoutWidth = layoutHeight = 0;
    }

    void setVisible(boolean visible) {
        advance(SystemClock.uptimeMillis());
        this.visible = visible;
        updateAudio();
    }

    private void updateAudio() {
        boolean listen = audioSync && visible && animate && shader != null && bitmap != null;
        OutputAudio.INSTANCE.setListening(this, context, listen && audioSource != AudioSource.MICROPHONE);
        AutoAudio.INSTANCE.setListening(this, context, listen && audioSource == AudioSource.AUTO
                && OutputAudio.hasPermission(context));
        MicrophoneAudioService.setListening(this, listen && audioSource == AudioSource.MICROPHONE
                && OutputAudio.hasPermission(context));
    }

    private BassPulse pulses() {
        return audioSource == AudioSource.MICROPHONE
                || (audioSource == AudioSource.AUTO && AutoAudio.INSTANCE.isUsingMicrophone())
                ? MicrophoneAudioService.pulses() : OutputAudio.INSTANCE.pulses();
    }

    private void setAnimation(boolean animate) {
        this.animate = animate;
        if (!animate) phase = 0.0f;
    }

    private void advance(long now) {
        if (visible && animate) {
            long elapsed = now - phaseTime;
            float kick = audioSync && elapsed <= 150 ? pulses().pulse(now, audioSmoothness) : 0f;
            float boost = kick * audioIntensity * audioEffect.huePerMillisecond;
            phase = (phase + elapsed / (float) periodMillis + elapsed * boost) % 1.0f;
        }
        phaseTime = now;
    }

    boolean isAnimating() {
        return animate && visible && shader != null && bitmap != null;
    }

    private void layout(int width, int height) {
        if (width == layoutWidth && height == layoutHeight) return;
        try {
            boolean rotate = (width > height) != (model.width > model.height);
            bitmap = WallpaperArt.render(context, rotate ? rotatedPath : artPath, width, height, 1f,
                    rotate ? rotatedModel : model, corners);
            input = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            input.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            if (shader != null) shader.setInputShader("art", input);
        } catch (IOException exception) {
            bitmap = null;
            input = null;
            Log.e("RgbGeometryLive", "Could not load " + artPath, exception);
        }
        updateAudio();
        destination.set(0, 0, width, height);
        layoutWidth = width;
        layoutHeight = height;
    }

    void draw(Canvas canvas) {
        draw(canvas, canvas.getWidth(), canvas.getHeight());
    }

    void draw(Canvas canvas, int width, int height) {
        canvas.drawColor(Color.BLACK);
        if (artPath == null || width <= 0 || height <= 0) return;
        layout(width, height);
        if (bitmap == null) return;
        long now = SystemClock.uptimeMillis();
        advance(now);

        if (animate && shader != null && canvas.isHardwareAccelerated()) {
            BassPulse pulses = pulses();
            float pulse = audioSync ? pulses.pulse(now, audioSmoothness) : 0f;
            float intensity = audioSync ? audioIntensity : 0f;
            float gain = 1f - audioEffect.dimming * intensity * pulses.activity(now) * (1f - pulse);
            shader.setFloatUniform("phase", phase);
            shader.setFloatUniform("gain", gain);
            shader.setFloatUniform("audioWave", audioEffect.waveHue * intensity * pulses.wavePulse(now, audioSmoothness),
                    pulses.travel(now, audioSmoothness), destination.left + destination.top,
                    1f / (destination.width() + destination.height()));
            paint.setShader(shader);
        } else {
            paint.setShader(input);
        }
        canvas.drawRect(destination, paint);
    }
}
