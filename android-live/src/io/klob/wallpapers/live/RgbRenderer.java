package io.klob.wallpapers.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

final class RgbRenderer {
    static final long FRAME_INTERVAL_MS = 33;
    private static final String SHADER =
            "uniform shader art;\n"
            + "uniform float phase;\n"
            + "uniform float gain;\n"
            + "half4 main(float2 p) {\n"
            + "    float3 c = float3(art.eval(p).rgb);\n"
            + "    float hi = max(max(c.r, c.g), c.b);\n"
            + "    float lo = min(min(c.r, c.g), c.b);\n"
            + "    float delta = hi - lo;\n"
            + "    if (delta < 0.0001 || phase == 0.0) return half4(half3(c * gain), 1.0);\n"
            + "    float h = hi == c.r ? (c.g - c.b) / delta\n"
            + "            : hi == c.g ? 2.0 + (c.b - c.r) / delta\n"
            + "            : 4.0 + (c.r - c.g) / delta;\n"
            + "    float3 wheel = clamp(abs(fract(h / 6.0 + phase +\n"
            + "            float3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);\n"
            + "    return half4(half3((lo + delta * wheel) * gain), 1.0);\n"
            + "}";

    private final Context context;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private final Matrix imageMatrix = new Matrix();
    private final RuntimeShader shader = createShader();
    private Bitmap bitmap;
    private BitmapShader input;
    private String artPath;
    private int layoutWidth;
    private int layoutHeight;
    private int periodMillis = 18000;
    private boolean animate = true;
    private boolean audioSync;
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

    void update(SharedPreferences preferences) {
        String path = Settings.artPath(preferences);
        if (!path.equals(artPath)) {
            try (InputStream file = context.getAssets().open(path)) {
                Bitmap replacement = BitmapFactory.decodeStream(file);
                if (replacement == null) throw new IOException("Invalid WebP: " + path);
                bitmap = replacement;
                input = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                input.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                artPath = path;
                layoutWidth = 0;
                layoutHeight = 0;
            } catch (IOException exception) {
                Log.e("RgbGeometryLive", "Could not load wallpaper artwork", exception);
            }
        }

        advance(SystemClock.uptimeMillis());
        periodMillis = Settings.period(preferences) * 1000;
        setAnimation(Settings.enabled(preferences, Settings.ANIMATE));
        audioSync = Settings.audioSync(preferences);
        updateAudio();
    }

    void setVisible(boolean visible) {
        advance(SystemClock.uptimeMillis());
        this.visible = visible;
        updateAudio();
    }

    private void updateAudio() {
        OutputAudio.INSTANCE.setListening(this, context,
                audioSync && visible && animate && shader != null && bitmap != null);
    }

    private void setAnimation(boolean animate) {
        this.animate = animate;
        if (!animate) phase = 0.0f;
    }

    private void advance(long now) {
        if (visible && animate) {
            long elapsed = now - phaseTime;
            float kick = audioSync && elapsed <= 150 ? OutputAudio.INSTANCE.pulses().pulse(now) : 0f;
            phase = (phase + elapsed / (float) periodMillis + elapsed * kick * 0.00075f) % 1.0f;
        }
        phaseTime = now;
    }

    boolean isAnimating() {
        return animate && visible && shader != null && bitmap != null;
    }

    private void layout(int width, int height) {
        if (width == layoutWidth && height == layoutHeight) return;
        float scale = Math.min(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
        float imageWidth = bitmap.getWidth() * scale;
        float imageHeight = bitmap.getHeight() * scale;
        float left = (width - imageWidth) / 2.0f;
        float top = (height - imageHeight) / 2.0f;
        destination.set(left, top, left + imageWidth, top + imageHeight);
        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(left, top);
        input.setLocalMatrix(imageMatrix);
        if (shader != null) shader.setInputShader("art", input);
        layoutWidth = width;
        layoutHeight = height;
    }

    void draw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        if (bitmap == null) return;
        layout(canvas.getWidth(), canvas.getHeight());
        long now = SystemClock.uptimeMillis();
        advance(now);

        if (animate && shader != null && canvas.isHardwareAccelerated()) {
            BassPulse pulses = OutputAudio.INSTANCE.pulses();
            float gain = audioSync ? 1f - 0.42f * pulses.activity(now) * (1f - pulses.pulse(now)) : 1f;
            shader.setFloatUniform("phase", phase);
            shader.setFloatUniform("gain", gain);
            paint.setShader(shader);
        } else {
            paint.setShader(input);
        }
        canvas.drawRect(destination, paint);
    }
}
