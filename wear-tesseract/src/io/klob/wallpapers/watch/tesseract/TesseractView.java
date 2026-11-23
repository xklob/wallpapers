package io.klob.wallpapers.watch.tesseract;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Renders Poly's exact 6-solid morph loop (Cube -> Cuboctahedron -> Octahedron ->
 * Icosahedron -> Tetrahedron -> Truncated tetrahedron -> Cube) with dotted hidden edges,
 * solid glowing front edges, continuous RGB hue rotation, and live bass reactivity.
 */
final class TesseractView extends View {
    enum Mode {
        PURE("PURE", 1.0f),
        CLOCK("CLOCK", 1.0f),
        DIM("DIM", 0.58f);

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
        MEDIUM("10S", 10_000L, 1.0f),
        FAST("4S", 4_000L, 1.85f),
        SLOW("20S", 20_000L, 0.55f);

        final String label;
        final long huePeriodMs;
        final float animRate;

        Speed(String label, long huePeriodMs, float animRate) {
            this.label = label;
            this.huePeriodMs = huePeriodMs;
            this.animRate = animRate;
        }

        Speed next() {
            Speed[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    interface Listener {
        void onStateChanged(Mode mode, Speed speed, boolean bassPulse);
    }

    private static final float DESIGN_SIZE = 456f;
    private static final float CENTER = DESIGN_SIZE * 0.5f;
    private static final long FRAME_MS = 22L;
    private static final long HUD_DURATION_MS = 1_700L;
    private static final float EPS = 1e-5f;
    private static final int MAX_VERTS = 32;
    private static final int MAX_FACES = 32;

    private static final int[] RING_COLORS = {
            0xFFFF1225, 0xFFFF6A00, 0xFFFFEE00, 0xFF6EFF05,
            0xFF00FF8A, 0xFF00ECFF, 0xFF005BFF, 0xFF5E0BFF,
            0xFFE300FF, 0xFFFF0089, 0xFFFF1225
    };

    private final PolyModel polyModel = new PolyModel();
    private final PolyModel.FrameState frameState = new PolyModel.FrameState();
    private final float[] screenX = new float[MAX_VERTS];
    private final float[] screenY = new float[MAX_VERTS];
    private final float[] vertZ = new float[MAX_VERTS];
    private final boolean[] faceVisible = new boolean[MAX_FACES];

    private final Paint rearDotGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rearDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frontGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frontCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frontHotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint metaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final SweepGradient ringGradient = new SweepGradient(CENTER, CENTER, RING_COLORS, null);
    private final Matrix ringMatrix = new Matrix();

    private final SimpleDateFormat time12Format = new SimpleDateFormat("h:mm", Locale.US);
    private final SimpleDateFormat time24Format = new SimpleDateFormat("HH:mm", Locale.US);
    private final Date now = new Date();
    private final GestureDetector gestures;
    private final BassPulse bassPulse;
    private final Listener listener;

    private Mode mode;
    private Speed speed;
    private boolean bassEnabled;
    private int batteryPercent = -1;
    private int heartRate = 0;
    private boolean running;
    private long lastTickMs = SystemClock.uptimeMillis();
    private float huePhase;
    private float polyClock;
    private float orbitClock;
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

    TesseractView(Context context, Mode initialMode, Speed initialSpeed, boolean initialBass,
            BassPulse bassPulse, Listener listener) {
        super(context);
        this.mode = initialMode;
        this.speed = initialSpeed;
        this.bassEnabled = initialBass;
        this.bassPulse = bassPulse;
        this.listener = listener;
        setKeepScreenOn(true);

        rearDotGlowPaint.setStyle(Paint.Style.FILL);
        rearDotPaint.setStyle(Paint.Style.FILL);

        frontGlowPaint.setStyle(Paint.Style.STROKE);
        frontGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        frontGlowPaint.setStrokeJoin(Paint.Join.ROUND);

        frontCorePaint.setStyle(Paint.Style.STROKE);
        frontCorePaint.setStrokeCap(Paint.Cap.ROUND);
        frontCorePaint.setStrokeJoin(Paint.Join.ROUND);

        frontHotPaint.setStyle(Paint.Style.STROKE);
        frontHotPaint.setStrokeCap(Paint.Cap.ROUND);
        frontHotPaint.setStrokeJoin(Paint.Join.ROUND);

        dotPaint.setStyle(Paint.Style.FILL);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3.0f);
        ringPaint.setShader(ringGradient);

        ringGlowPaint.setStyle(Paint.Style.STROKE);
        ringGlowPaint.setStrokeWidth(11.0f);
        ringGlowPaint.setShader(ringGradient);

        Typeface bold = loadSpaceGrotesk(context, 700, Typeface.BOLD);
        Typeface medium = loadSpaceGrotesk(context, 500, Typeface.NORMAL);

        timePaint.setTypeface(bold);
        timePaint.setTextSize(54f);
        timePaint.setTextAlign(Paint.Align.CENTER);
        timePaint.setShadowLayer(12f, 0f, 0f, 0xCC000000);

        metaPaint.setTypeface(medium);
        metaPaint.setTextSize(19f);
        metaPaint.setTextAlign(Paint.Align.CENTER);
        metaPaint.setShadowLayer(10f, 0f, 0f, 0xCC000000);

        hudPaint.setTypeface(medium);
        hudPaint.setTextSize(16f);
        hudPaint.setTextAlign(Paint.Align.CENTER);

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                mode = mode.next();
                notifyChange();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                speed = speed.next();
                notifyChange();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                bassEnabled = !bassEnabled;
                notifyChange();
            }
        });
    }

    private void notifyChange() {
        showHud(statusLabel());
        if (listener != null) {
            listener.onStateChanged(mode, speed, bassEnabled);
        }
        invalidate();
    }

    String statusLabel() {
        return mode.label + " // " + speed.label + (bassEnabled ? " // BASS" : "");
    }

    void setBassEnabled(boolean enabled) {
        if (bassEnabled == enabled) return;
        bassEnabled = enabled;
        showHud(statusLabel());
        invalidate();
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

    private static Typeface loadSpaceGrotesk(Context context, int weight, int fallbackStyle) {
        try {
            return new Typeface.Builder(context.getAssets(), "space_grotesk.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
        } catch (RuntimeException ignored) {
            return Typeface.create(Typeface.SANS_SERIF, fallbackStyle);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestures.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);

        long nowMs = SystemClock.uptimeMillis();
        long dtMs = Math.max(0L, Math.min(120L, nowMs - lastTickMs));
        lastTickMs = nowMs;
        float dtSec = dtMs / 1000f;

        float pulse = bassEnabled ? bassPulse.pulse(nowMs) : 0f;
        float activity = bassEnabled ? bassPulse.activity(nowMs) : 0f;

        huePhase = (huePhase + dtMs / (float) speed.huePeriodMs + dtMs * pulse * activity * 0.00055f) % 1.0f;
        polyClock += dtSec * speed.animRate * (1f + 0.35f * pulse * activity);
        orbitClock += dtSec * speed.animRate * (1f + 0.90f * pulse * activity);

        polyModel.sample(polyClock, frameState);
        PolyModel.Mesh mesh = frameState.mesh;

        float scale = Math.min(getWidth(), getHeight()) / DESIGN_SIZE;
        float offsetX = (getWidth() - DESIGN_SIZE * scale) * 0.5f;
        float offsetY = (getHeight() - DESIGN_SIZE * scale) * 0.5f;

        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        float alphaMaster = mode.alphaScale * (1f - 0.30f * activity * (1f - pulse));
        float beatBloom = 1f + 0.55f * pulse * activity;

        // Subtle rotating rainbow bezel ring
        ringMatrix.setRotate(huePhase * 360f, CENTER, CENTER);
        ringGradient.setLocalMatrix(ringMatrix);
        ringGlowPaint.setAlpha(clamp255(Math.round((44f + 56f * pulse * activity) * alphaMaster)));
        ringPaint.setAlpha(clamp255(Math.round((150f + 95f * pulse * activity) * alphaMaster)));
        canvas.drawCircle(CENTER, CENTER, 219f, ringGlowPaint);
        canvas.drawCircle(CENTER, CENTER, 219f, ringPaint);

        // Smooth 3D camera orbit inspired by Poly's three-quarter view
        double yawRad = Math.toRadians(22.0 + orbitClock * 26.0);
        double elevRad = Math.toRadians(16.0 + 10.0 * Math.sin(orbitClock * 0.55));
        double cosY = Math.cos(yawRad), sinY = Math.sin(yawRad);
        double cosE = Math.cos(elevRad), sinE = Math.sin(elevRad);

        // Classify front-facing vs rear-facing faces (exact Poly visibility test)
        int fCount = mesh.faceNormals.length;
        for (int fi = 0; fi < fCount; fi++) {
            float[] n = mesh.faceNormals[fi];
            double zz = -n[0] * sinY + n[2] * cosY;
            double nz = n[1] * sinE + zz * cosE;
            faceVisible[fi] = nz > EPS;
        }

        // Project vertices (orthographic like Poly, scaled to fill the round dial boldly)
        float baseRadius = (mode == Mode.CLOCK ? 128f : 158f)
                * frameState.scale * (1f + 0.11f * pulse * activity);
        int vCount = mesh.vertices.length;
        for (int vi = 0; vi < vCount; vi++) {
            float[] v = mesh.vertices[vi];
            double xx = v[0] * cosY + v[2] * sinY;
            double zz = -v[0] * sinY + v[2] * cosY;
            double yy = v[1] * cosE - zz * sinE;
            double rz = v[1] * sinE + zz * cosE;
            screenX[vi] = CENTER + (float) (xx * baseRadius);
            screenY[vi] = CENTER - (float) (yy * baseRadius);
            vertZ[vi] = (float) rz;
        }

        float scaleRoot = (float) Math.sqrt(Math.max(0.12f, frameState.scale));
        double hueRad = huePhase * Math.PI * 2.0;
        int eCount = mesh.edgeA.length;

        // Pass 1: Rear (hidden) edges drawn as Poly's signature bright dotted lines
        float dotRadius = 4.2f * scaleRoot * (1f + 0.18f * pulse * activity);
        float dotGlowRadius = dotRadius * 2.1f * beatBloom;
        float dotSpacing = 17.5f * scaleRoot;
        int rearGlowAlpha = clamp255(Math.round(52f * beatBloom * alphaMaster));
        int rearDotAlpha = clamp255(Math.round(215f * alphaMaster));

        for (int ei = 0; ei < eCount; ei++) {
            if (faceVisible[mesh.edgeFace0[ei]] || faceVisible[mesh.edgeFace1[ei]]) continue;
            int a = mesh.edgeA[ei];
            int b = mesh.edgeB[ei];
            float x0 = screenX[a], y0 = screenY[a];
            float x1 = screenX[b], y1 = screenY[b];
            float dist = (float) Math.hypot(x1 - x0, y1 - y0);
            int steps = Math.max(2, Math.round(dist / Math.max(6f, dotSpacing)));

            double edgeRad = hueRad + (ei / (double) Math.max(1, eCount)) * Math.PI * 0.65;
            rearDotGlowPaint.setColor(rgbWheel(edgeRad, 130, 125, rearGlowAlpha));
            rearDotPaint.setColor(rgbWheel(edgeRad, 160, 95, rearDotAlpha));

            for (int s = 0; s <= steps; s++) {
                float t = s / (float) steps;
                float px = x0 + (x1 - x0) * t;
                float py = y0 + (y1 - y0) * t;
                canvas.drawCircle(px, py, dotGlowRadius, rearDotGlowPaint);
                canvas.drawCircle(px, py, dotRadius, rearDotPaint);
            }
        }

        // Pass 2: Front (visible) edges drawn on top with crisp multi-layer neon glow
        float frontGlowW = 16.0f * scaleRoot * beatBloom;
        float frontCoreW = 6.8f * scaleRoot * (1f + 0.12f * pulse * activity);
        float frontHotW = 2.6f * scaleRoot;
        int frontGlowAlpha = clamp255(Math.round(82f * beatBloom * alphaMaster));
        int frontCoreAlpha = clamp255(Math.round(255f * alphaMaster));
        int frontHotAlpha = clamp255(Math.round(185f * alphaMaster));

        frontGlowPaint.setStrokeWidth(frontGlowW);
        frontCorePaint.setStrokeWidth(frontCoreW);
        frontHotPaint.setStrokeWidth(frontHotW);

        for (int ei = 0; ei < eCount; ei++) {
            if (!faceVisible[mesh.edgeFace0[ei]] && !faceVisible[mesh.edgeFace1[ei]]) continue;
            int a = mesh.edgeA[ei];
            int b = mesh.edgeB[ei];
            float midZ = 0.5f * (vertZ[a] + vertZ[b]);
            double edgeRad = hueRad + midZ * 0.45 + (ei / (double) Math.max(1, eCount)) * Math.PI * 0.35;

            frontGlowPaint.setColor(rgbWheel(edgeRad, 130, 125, frontGlowAlpha));
            canvas.drawLine(screenX[a], screenY[a], screenX[b], screenY[b], frontGlowPaint);

            frontCorePaint.setColor(rgbWheel(edgeRad, 150, 105, frontCoreAlpha));
            canvas.drawLine(screenX[a], screenY[a], screenX[b], screenY[b], frontCorePaint);

            frontHotPaint.setColor(rgbWheel(edgeRad, 228, 27, frontHotAlpha));
            canvas.drawLine(screenX[a], screenY[a], screenX[b], screenY[b], frontHotPaint);
        }

        // Optional clean time + HR/battery overlay in CLOCK mode
        if (mode == Mode.CLOCK) {
            now.setTime(System.currentTimeMillis());
            String timeStr = (DateFormat.is24HourFormat(getContext()) ? time24Format : time12Format).format(now);
            timePaint.setColor(rgbWheel(hueRad, 222, 33, 245));
            drawCenteredText(canvas, timeStr, CENTER, 62f, timePaint);

            String hrPart = heartRate > 0 ? ("♥\uFE0E " + heartRate) : "♥\uFE0E --";
            String battPart = batteryPercent >= 0 ? (batteryPercent + "%") : "--%";
            metaPaint.setColor(rgbWheel(hueRad + 2.094395, 195, 60, 225));
            drawCenteredText(canvas, hrPart + "  ·  " + battPart, CENTER, 404f, metaPaint);
        }

        // Subtle live bass status dot when BASS is armed
        if (bassEnabled && nowMs >= hudUntilMs) {
            boolean hearing = bassPulse.hasSignal(nowMs);
            int dotAlpha = hearing ? clamp255(Math.round(115f + 140f * pulse)) : 60;
            float dotR = hearing ? (3.2f + 2.8f * pulse) : 2.6f;
            float dotY = mode == Mode.CLOCK ? 378f : 422f;
            dotPaint.setColor(rgbWheel(hueRad + 2.094395, 180, 75, dotAlpha));
            canvas.drawCircle(CENTER, dotY, dotR, dotPaint);
        }

        // Brief HUD pill when switching modes / speeds / bass
        if (nowMs < hudUntilMs && !hudText.isEmpty()) {
            float fade = Math.min(1f, (hudUntilMs - nowMs) / 350f);
            hudPaint.setColor(Color.argb(Math.round(215f * fade), 232, 236, 250));
            drawCenteredText(canvas, hudText, CENTER, 384f, hudPaint);
        }

        canvas.restore();
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int rgbWheel(double rad, int base, int amp, int alpha) {
        int r = clamp255((int) Math.round(base + amp * Math.cos(rad)));
        int g = clamp255((int) Math.round(base + amp * Math.cos(rad - 2.094395)));
        int b = clamp255((int) Math.round(base + amp * Math.cos(rad - 4.18879)));
        return Color.argb(alpha, r, g, b);
    }

    private static void drawCenteredText(Canvas canvas, String text, float cx, float cy, Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(text, cx, baseline, paint);
    }
}
