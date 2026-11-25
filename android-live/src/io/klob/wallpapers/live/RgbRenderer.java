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
    private static final String SHADER =
            "uniform shader art;\n"
            + "uniform float phase;\n"
            + "uniform float gain;\n"
            + "uniform float4 audioWave;\n"
            + "half4 main(float2 p) {\n"
            + "    half4 pixel = art.eval(p);\n"
            + "    float3 c = float3(pixel.rgb);\n"
            + "    float hi = max(max(c.r, c.g), c.b);\n"
            + "    float lo = min(min(c.r, c.g), c.b);\n"
            + "    float delta = hi - lo;\n"
            + "    if (delta < 0.0001 || (phase == 0.0 && audioWave.x == 0.0))\n"
            + "        return half4(half3(c * gain), pixel.a);\n"
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
            + "    return half4(half3((lo + delta * wheel) * gain), pixel.a);\n"
            + "}";

    private final Context context;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private final RuntimeShader shader = createShader();
    private Bitmap bitmap;
    private BitmapShader input;
    private BitmapShader artworkInput;
    private RuntimeShader artworkColors;
    private int rotation;
    private MengerWindows menger;
    private MandelbrotZoom mandelbrot;
    private int frameRate = 30;
    private double zoomPhase;
    private float zoomSpeed = 1f;
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
        advance(SystemClock.uptimeMillis());
        int angle = ArtworkRotation.normalized(Settings.rotation(preferences, screen));
        if ((rotation == 0) != (angle == 0) && (artPath == null || !WallpaperArt.hasScene(artPath))) {
            layoutWidth = layoutHeight = 0;
        }
        rotation = angle;
        Catalog.Model selected = Settings.model(context, preferences, screen);
        Catalog.Model rotated = Catalog.get(context).forOrientation(selected, selected.width <= selected.height);
        String path = Settings.artPath(selected, preferences, screen);
        String other = rotated == selected ? path : Settings.artPath(rotated, preferences, screen);
        if (selected != model || !path.equals(artPath) || !other.equals(rotatedPath)) {
            if (MandelbrotZoom.uses(path) != MandelbrotZoom.uses(artPath)
                    || MengerWindows.uses(path) != MengerWindows.uses(artPath)) zoomPhase = 0;
            model = selected;
            rotatedModel = rotated;
            artPath = path;
            rotatedPath = other;
            layoutWidth = layoutHeight = 0;
            if (MengerWindows.uses(path)) {
                if (menger == null) menger = new MengerWindows(context);
            } else {
                menger = null;
            }
            if (MandelbrotZoom.uses(path)) {
                if (mandelbrot == null) {
                    mandelbrot = new MandelbrotZoom(context);
                }
            } else {
                mandelbrot = null;
            }
        }

        zoomSpeed = Settings.zoomSpeed(preferences) / (mandelbrot == null ? 4f : 8f);
        frameRate = Settings.FRAME_RATES[Settings.frameRateIndex(preferences)];
        if (mandelbrot != null) mandelbrot.setQuality(Settings.quality(preferences));
        periodMillis = Settings.period(preferences) * 1000;
        setAnimation(menger == null && Settings.enabled(preferences, Settings.ANIMATE));
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
        long elapsed = now - phaseTime;
        if (visible && hasZoom()) zoomPhase = advanceZoom(zoomPhase, elapsed, zoomSpeed);
        if (visible && animate) {
            float kick = audioSync && elapsed <= 150 ? pulses().pulse(now, audioSmoothness) : 0f;
            float boost = kick * audioIntensity * audioEffect.huePerMillisecond;
            phase = (phase + elapsed / (float) periodMillis + elapsed * boost) % 1.0f;
        }
        phaseTime = now;
    }

    static double advanceZoom(double phase, long elapsedMillis, float speed) {
        return (phase + elapsedMillis * (double) speed / 16000) % 1.0;
    }

    private boolean hasZoom() {
        return menger != null || (mandelbrot != null && mandelbrot.isAvailable());
    }

    int frameRate() {
        return hasZoom() ? frameRate : 30;
    }

    boolean isAnimating() {
        return visible && bitmap != null && (hasZoom() || (animate && shader != null));
    }

    private void layout(int width, int height) {
        if (width == layoutWidth && height == layoutHeight) return;
        try {
            boolean rotate = (width > height) != (model.width > model.height);
            Catalog.Model displayed = rotate ? rotatedModel : model;
            String path = rotate ? rotatedPath : artPath;
            boolean split = rotation != 0 && !WallpaperArt.hasScene(path);
            artworkInput = null;
            if (split) {
                Bitmap artwork = WallpaperArt.renderLayer(context, path, width, height, displayed, corners,
                        WallpaperArt.Layer.ARTWORK);
                artworkInput = sample(artwork);
                if (shader != null) {
                    if (artworkColors == null) artworkColors = createShader();
                    if (artworkColors != null) artworkColors.setInputShader("art", artworkInput);
                }
                bitmap = WallpaperArt.renderLayer(context, path, width, height, displayed, corners,
                        WallpaperArt.Layer.FRAME);
            } else {
                artworkColors = null;
                bitmap = WallpaperArt.render(context, path, width, height, 1f, displayed, corners, false);
            }
            input = sample(bitmap);
            if (shader != null) shader.setInputShader("art", input);
        } catch (IOException exception) {
            bitmap = null;
            input = null;
            artworkInput = null;
            artworkColors = null;
            Log.e("RgbGeometryLive", "Could not load " + artPath, exception);
        }
        updateAudio();
        destination.set(0, 0, width, height);
        layoutWidth = width;
        layoutHeight = height;
    }

    private static BitmapShader sample(Bitmap image) {
        BitmapShader sample = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        sample.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return sample;
    }

    void draw(Canvas canvas) {
        draw(canvas, canvas.getWidth(), canvas.getHeight());
    }

    void draw(Canvas canvas, int width, int height) {
        if (artPath == null || width <= 0 || height <= 0) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        layout(width, height);
        if (bitmap == null || (menger == null && mandelbrot == null)) canvas.drawColor(Color.BLACK);
        if (bitmap == null) return;
        long now = SystemClock.uptimeMillis();
        advance(now);

        boolean colorize = animate && shader != null && canvas.isHardwareAccelerated();
        if (colorize) {
            BassPulse pulses = pulses();
            float pulse = audioSync ? pulses.pulse(now, audioSmoothness) : 0f;
            float intensity = audioSync ? audioIntensity : 0f;
            float gain = 1f - audioEffect.dimming * intensity * pulses.activity(now) * (1f - pulse);
            float wave = audioEffect.waveHue * intensity * pulses.wavePulse(now, audioSmoothness);
            float travel = pulses.travel(now, audioSmoothness);
            float diagonal = destination.width() + destination.height();
            tint(shader, gain, wave, travel, diagonal);
            if (artworkColors != null) tint(artworkColors, gain, wave, travel, diagonal);
            if (mandelbrot != null) mandelbrot.setColor(phase, gain, wave, travel);
            paint.setShader(shader);
        } else {
            paint.setShader(input);
        }
        if (menger != null || mandelbrot != null || artworkInput != null) {
            canvas.save();
            ArtworkRotation.apply(canvas, width, height, rotation);
            if (menger != null) {
                menger.draw(canvas, width, height, zoomPhase);
            } else if (mandelbrot != null) {
                if (!colorize) mandelbrot.setColor(0, 1, 0, 0);
                mandelbrot.draw(context, canvas, width, height, zoomPhase);
            } else {
                paint.setShader(colorize && artworkColors != null ? artworkColors : artworkInput);
                canvas.drawRect(destination, paint);
                paint.setShader(colorize ? shader : input);
            }
            canvas.restore();
        }
        canvas.drawRect(destination, paint);
    }

    private void tint(RuntimeShader effect, float gain, float wave, float travel, float diagonal) {
        effect.setFloatUniform("phase", phase);
        effect.setFloatUniform("gain", gain);
        effect.setFloatUniform("audioWave", wave, travel, 0f, 1f / diagonal);
    }
}
