package io.klob.wallpapers.watch.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class LiveClockView extends View {
    enum Mode {
        FULL("FULL", 1.0f),
        MINIMAL("MINIMAL", 1.0f),
        DIM("DIM", 0.62f);

        final String label;
        final float alphaScale;

        Mode(String label, float alphaScale) {
            this.label = label;
            this.alphaScale = alphaScale;
        }

        Mode next() {
            Mode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    enum Speed {
        MEDIUM("10S", 10_000L),
        FAST("4S", 4_000L),
        SLOW("20S", 20_000L);

        final String label;
        final long periodMs;

        Speed(String label, long periodMs) {
            this.label = label;
            this.periodMs = periodMs;
        }

        Speed next() {
            Speed[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    interface Listener {
        void onStateChanged(Mode mode, Speed speed);
    }

    private static final float DESIGN_SIZE = 456f;
    private static final long FRAME_MS = 33L;
    private static final long HUD_DURATION_MS = 1_600L;

    private static final String HUE_SHADER =
            "uniform shader art;\n"
            + "uniform float phase;\n"
            + "half4 main(float2 p) {\n"
            + "    float3 c = float3(art.eval(p).rgb);\n"
            + "    float hi = max(max(c.r, c.g), c.b);\n"
            + "    float lo = min(min(c.r, c.g), c.b);\n"
            + "    float delta = hi - lo;\n"
            + "    if (delta < 0.0001) return half4(half3(c), 1.0);\n"
            + "    float h = hi == c.r ? (c.g - c.b) / delta\n"
            + "            : hi == c.g ? 2.0 + (c.b - c.r) / delta\n"
            + "            : 4.0 + (c.r - c.g) / delta;\n"
            + "    float3 wheel = clamp(abs(fract(h / 6.0 + phase +\n"
            + "            float3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);\n"
            + "    return half4(half3(lo + delta * wheel), 1.0);\n"
            + "}";

    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint metaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RuntimeShader hueShader = new RuntimeShader(HUE_SHADER);
    private final Bitmap artwork;
    private final Path heartPath = new Path();
    private final RectF battBody = new RectF(243f, 308f, 262f, 320f);
    private final RectF battFill = new RectF(246f, 311f, 256f, 317f);
    private final RectF battCap = new RectF(263f, 311.5f, 265f, 316.5f);

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE · MMM d", Locale.US);
    private final SimpleDateFormat time12Format = new SimpleDateFormat("h:mm", Locale.US);
    private final SimpleDateFormat time24Format = new SimpleDateFormat("HH:mm", Locale.US);
    private final Date now = new Date();
    private final GestureDetector gestures;
    private final Listener listener;

    private Mode mode = Mode.FULL;
    private Speed speed = Speed.MEDIUM;
    private int batteryPercent = -1;
    private int heartRate = 0;
    private boolean running;
    private long startTimeMs = SystemClock.uptimeMillis();
    private float phase;
    private long lastTickMs = startTimeMs;
    private long hudUntilMs;
    private String hudText = "";

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    LiveClockView(Context context, Mode initialMode, Speed initialSpeed, Listener listener) {
        super(context);
        this.mode = initialMode;
        this.speed = initialSpeed;
        this.listener = listener;
        setKeepScreenOn(true);

        artwork = loadArtwork(context);
        if (artwork != null) {
            BitmapShader input = new BitmapShader(artwork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            input.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            hueShader.setInputShader("art", input);
            artPaint.setShader(hueShader);
        }

        Typeface bold = loadSpaceGrotesk(context, 700, Typeface.BOLD);
        Typeface medium = loadSpaceGrotesk(context, 500, Typeface.NORMAL);

        timePaint.setTypeface(bold);
        timePaint.setTextSize(112f);
        timePaint.setTextAlign(Paint.Align.CENTER);

        datePaint.setTypeface(medium);
        datePaint.setTextSize(23f);
        datePaint.setTextAlign(Paint.Align.CENTER);

        metaPaint.setTypeface(medium);
        metaPaint.setTextSize(22f);
        metaPaint.setTextAlign(Paint.Align.LEFT);

        hudPaint.setTypeface(medium);
        hudPaint.setTextSize(16f);
        hudPaint.setTextAlign(Paint.Align.CENTER);

        iconFillPaint.setStyle(Paint.Style.FILL);
        iconStrokePaint.setStyle(Paint.Style.STROKE);
        iconStrokePaint.setStrokeWidth(1.6f);

        buildHeartPath(144f, 305f, 18f);

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                mode = mode.next();
                showHud(mode.label + " // " + speed.label);
                if (LiveClockView.this.listener != null) {
                    LiveClockView.this.listener.onStateChanged(mode, speed);
                }
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                speed = speed.next();
                showHud(mode.label + " // " + speed.label);
                if (LiveClockView.this.listener != null) {
                    LiveClockView.this.listener.onStateChanged(mode, speed);
                }
                invalidate();
                return true;
            }
        });
    }

    private static Bitmap loadArtwork(Context context) {
        try (InputStream stream = context.getAssets().open("honeycomb.png")) {
            return BitmapFactory.decodeStream(stream);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Typeface loadSpaceGrotesk(Context context, int weight, int fallbackStyle) {
        try {
            return new Typeface.Builder(context.getAssets(), "space_grotesk.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
        } catch (RuntimeException ignored) {
            return Typeface.create(Typeface.SANS_SERIF, fallbackStyle);
        }
    }

    private void buildHeartPath(float ox, float oy, float s) {
        heartPath.reset();
        float cx = ox + s * 0.5f;
        heartPath.moveTo(cx, oy + s * 0.90f);
        heartPath.cubicTo(
                ox + s * 0.04f, oy + s * 0.58f,
                ox + s * 0.02f, oy + s * 0.14f,
                ox + s * 0.30f, oy + s * 0.14f);
        heartPath.cubicTo(
                ox + s * 0.43f, oy + s * 0.14f,
                cx, oy + s * 0.25f,
                cx, oy + s * 0.33f);
        heartPath.cubicTo(
                cx, oy + s * 0.25f,
                ox + s * 0.57f, oy + s * 0.14f,
                ox + s * 0.70f, oy + s * 0.14f);
        heartPath.cubicTo(
                ox + s * 0.98f, oy + s * 0.14f,
                ox + s * 0.96f, oy + s * 0.58f,
                cx, oy + s * 0.90f);
        heartPath.close();
    }

    void setBatteryPercent(int percent) {
        this.batteryPercent = percent;
    }

    void setHeartRate(int bpm) {
        this.heartRate = bpm;
    }

    void setRunning(boolean active) {
        if (running == active) return;
        running = active;
        removeCallbacks(ticker);
        lastTickMs = SystemClock.uptimeMillis();
        if (running) {
            post(ticker);
        }
    }

    void showHud(String text) {
        hudText = text;
        hudUntilMs = SystemClock.uptimeMillis() + HUD_DURATION_MS;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestures.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);

        long nowMs = SystemClock.uptimeMillis();
        long dt = Math.max(0L, nowMs - lastTickMs);
        lastTickMs = nowMs;
        phase = (phase + (float) dt / (float) speed.periodMs) % 1.0f;

        float scale = Math.min(getWidth(), getHeight()) / DESIGN_SIZE;
        float offsetX = (getWidth() - DESIGN_SIZE * scale) * 0.5f;
        float offsetY = (getHeight() - DESIGN_SIZE * scale) * 0.5f;

        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        double angle = phase * Math.PI * 2.0;
        now.setTime(System.currentTimeMillis());
        String timeStr = (DateFormat.is24HourFormat(getContext()) ? time24Format : time12Format).format(now);

        if (mode == Mode.FULL) {
            if (artwork != null) {
                hueShader.setFloatUniform("phase", phase);
                canvas.drawRect(0f, 0f, DESIGN_SIZE, DESIGN_SIZE, artPaint);
            }

            datePaint.setColor(rgbWheel(angle, 190, 65, 255));
            String dateStr = dateFormat.format(now).toUpperCase(Locale.US);
            drawCenteredText(canvas, dateStr, 228f, 140f, datePaint);

            timePaint.setColor(rgbWheel(angle + 1.047198, 205, 50, 255));
            drawCenteredText(canvas, timeStr, 228f, 227f, timePaint);

            int metaColor = rgbWheel(angle + 2.094395, 190, 65, 255);
            metaPaint.setColor(metaColor);
            iconFillPaint.setColor(metaColor);
            iconStrokePaint.setColor(metaColor);

            canvas.drawPath(heartPath, iconFillPaint);
            String hrStr = heartRate > 0 ? String.valueOf(heartRate) : "--";
            drawMiddleLeftText(canvas, hrStr, 168f, 314f, metaPaint);

            canvas.drawRoundRect(battBody, 2.5f, 2.5f, iconStrokePaint);
            float fillRatio = batteryPercent >= 0 ? Math.max(0.08f, Math.min(1f, batteryPercent / 100f)) : 0.6f;
            battFill.right = battFill.left + 13f * fillRatio;
            canvas.drawRoundRect(battFill, 1f, 1f, iconFillPaint);
            canvas.drawRoundRect(battCap, 1f, 1f, iconFillPaint);

            String battStr = batteryPercent >= 0 ? (batteryPercent + "%") : "--%";
            drawMiddleLeftText(canvas, battStr, 272f, 314f, metaPaint);
        } else {
            double driftSec = (nowMs - startTimeMs) / 1000.0;
            float dx = (float) (Math.sin(driftSec * 0.052) * 4.5);
            float dy = (float) (Math.cos(driftSec * 0.037) * 4.5);
            int alpha = Math.round(255f * mode.alphaScale);
            timePaint.setColor(rgbWheel(angle, 155, 100, alpha));
            drawCenteredText(canvas, timeStr, 228f + dx, 228f + dy, timePaint);
        }

        if (nowMs < hudUntilMs && !hudText.isEmpty()) {
            float fade = Math.min(1f, (hudUntilMs - nowMs) / 350f);
            hudPaint.setColor(Color.argb(Math.round(190f * fade), 220, 224, 240));
            drawCenteredText(canvas, hudText, 228f, 378f, hudPaint);
        }

        canvas.restore();
    }

    private static int rgbWheel(double rad, int base, int amp, int alpha) {
        int r = clamp255((int) Math.round(base + amp * Math.cos(rad)));
        int g = clamp255((int) Math.round(base + amp * Math.cos(rad - 2.094395)));
        int b = clamp255((int) Math.round(base + amp * Math.cos(rad - 4.18879)));
        return Color.argb(alpha, r, g, b);
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static void drawCenteredText(Canvas canvas, String text, float cx, float cy, Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(text, cx, baseline, paint);
    }

    private static void drawMiddleLeftText(Canvas canvas, String text, float x, float cy, Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(text, x, baseline, paint);
    }
}
