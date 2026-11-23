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
 * Renders a single 16-vertex, 32-edge 4D polytope that continuously rotates in 4D
 * and smoothly morphs across 5 sacred/hyper-geometric shapes with neon RGB glow
 * and optional microphone bass reactivity.
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
        FAST("4S", 4_000L, 2.05f),
        SLOW("20S", 20_000L, 0.52f);

        final String label;
        final long huePeriodMs;
        final float rotRate;

        Speed(String label, long huePeriodMs, float rotRate) {
            this.label = label;
            this.huePeriodMs = huePeriodMs;
            this.rotRate = rotRate;
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
    private static final float SHAPE_STAGE_SECONDS = 5.6f;
    private static final int NUM_VERTS = 16;
    private static final int NUM_EDGES = 32;
    private static final int NUM_SHAPES = 5;

    private static final int[] RING_COLORS = {
            0xFFFF1225, 0xFFFF6A00, 0xFFFFEE00, 0xFF6EFF05,
            0xFF00FF8A, 0xFF00ECFF, 0xFF005BFF, 0xFF5E0BFF,
            0xFFE300FF, 0xFFFF0089, 0xFFFF1225
    };

    // 5 target 4D shapes on the exact 16-vertex, 32-edge hypercube topology
    private final float[][][] shapes = new float[NUM_SHAPES][NUM_VERTS][4];
    private final int[] edgeA = new int[NUM_EDGES];
    private final int[] edgeB = new int[NUM_EDGES];
    private final float[] edgeHueBase = new float[NUM_EDGES];
    private final int[] edgeOrder = new int[NUM_EDGES];
    private final float[] edgeDepth = new float[NUM_EDGES];

    private final float[] screenX = new float[NUM_VERTS];
    private final float[] screenY = new float[NUM_VERTS];
    private final float[] vertDepth = new float[NUM_VERTS];
    private final float[] vertW = new float[NUM_VERTS];

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    private float rotClock = 1.25f;
    private float morphClock;
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

        buildTopologyAndShapes();

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        corePaint.setStyle(Paint.Style.STROKE);
        corePaint.setStrokeCap(Paint.Cap.ROUND);
        hotPaint.setStyle(Paint.Style.STROKE);
        hotPaint.setStrokeCap(Paint.Cap.ROUND);

        nodeGlowPaint.setStyle(Paint.Style.FILL);
        nodeCorePaint.setStyle(Paint.Style.FILL);
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

    private void buildTopologyAndShapes() {
        // 32 edges of the 4D hypercube (Hamming distance 1)
        int e = 0;
        for (int i = 0; i < NUM_VERTS; i++) {
            for (int bit = 0; bit < 4; bit++) {
                int j = i ^ (1 << bit);
                if (j > i) {
                    edgeA[e] = i;
                    edgeB[e] = j;
                    edgeHueBase[e] = (bit * 0.25f) + (e / (float) NUM_EDGES) * 0.35f;
                    edgeOrder[e] = e;
                    e++;
                }
            }
        }

        double cos45 = Math.cos(Math.PI / 4.0);
        double sin45 = Math.sin(Math.PI / 4.0);

        for (int i = 0; i < NUM_VERTS; i++) {
            float sx = (i & 1) != 0 ? 1f : -1f;
            float sy = (i & 2) != 0 ? 1f : -1f;
            float sz = (i & 4) != 0 ? 1f : -1f;
            float sw = (i & 8) != 0 ? 1f : -1f;

            // Gray-code ring indices so adjacent hypercube edges stay adjacent on rings/tori
            int lo2 = i & 3;
            int hi2 = (i >> 2) & 3;
            int g1 = lo2 ^ (lo2 >> 1);
            int g2 = hi2 ^ (hi2 >> 1);
            double theta = g1 * (Math.PI * 0.5) + Math.PI * 0.25;
            double phi = g2 * (Math.PI * 0.5) + Math.PI * 0.25;

            // Shape 0: Classic 4D Tesseract (8-cell)
            shapes[0][i][0] = sx * 0.72f;
            shapes[0][i][1] = sy * 0.72f;
            shapes[0][i][2] = sz * 0.72f;
            shapes[0][i][3] = sw * 0.72f;

            // Shape 1: 4D Clifford Torus (exact 4x4 woven geodesic torus in S^3)
            shapes[1][i][0] = (float) (Math.cos(theta) * 0.90);
            shapes[1][i][1] = (float) (Math.sin(theta) * 0.90);
            shapes[1][i][2] = (float) (Math.cos(phi) * 0.90);
            shapes[1][i][3] = (float) (Math.sin(phi) * 0.90);

            // Shape 2: 4D Stellated Merkaba Star (bipartite outer/inner counter-twisted shells)
            boolean even = (sx * sy * sz * sw) > 0f;
            double s = even ? 0.72 : 0.28;
            double rot = even ? 1.0 : -1.0;
            double mx = (sx * cos45 - sy * sin45 * rot) * s;
            double my = (sx * sin45 * rot + sy * cos45) * s;
            double mz = (sz * cos45 - sw * sin45 * rot) * s;
            double mw = (sz * sin45 * rot + sw * cos45) * s;
            shapes[2][i][0] = (float) mx;
            shapes[2][i][1] = (float) my;
            shapes[2][i][2] = (float) mz;
            shapes[2][i][3] = (float) mw;

            // Shape 3: 4D Hyper-Diamond / Octaplex Crystal
            double da = g1 * (Math.PI * 0.5) + (sw < 0f ? Math.PI * 0.25 : 0.0);
            double dr = sw > 0f ? 0.94 : 0.34;
            shapes[3][i][0] = (float) (Math.cos(da) * dr);
            shapes[3][i][1] = (float) (Math.sin(da) * dr);
            shapes[3][i][2] = sz * (sw > 0f ? 0.36f : 0.98f);
            shapes[3][i][3] = sw * 0.50f;

            // Shape 4: 4D Sacred Vortex / Blooming Hyper-Mandala
            double va = g1 * (Math.PI * 0.5) + (sw > 0f ? Math.PI * 0.25 : 0.0);
            double vr = sz > 0f ? 1.02 : 0.42;
            shapes[4][i][0] = (float) (Math.cos(va) * vr);
            shapes[4][i][1] = (float) (Math.sin(va) * vr);
            shapes[4][i][2] = sz * 0.48f;
            shapes[4][i][3] = sw * 0.64f;
        }
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

        // Smooth hue phase + bass surge
        huePhase = (huePhase + dtMs / (float) speed.huePeriodMs + dtMs * pulse * activity * 0.00055f) % 1.0f;

        // Continuous 4D rotation clock + subtle beat burst
        float speedMult = speed.rotRate * (1f + 0.95f * pulse * activity);
        rotClock += dtSec * speedMult;

        // Continuous polytope morph timeline (hold ~35% of each stage, quintic morph over ~65%)
        morphClock += dtSec * (0.85f + 0.35f * speed.rotRate) * (1f + 0.55f * pulse * activity);
        float stagePos = (morphClock / SHAPE_STAGE_SECONDS) % NUM_SHAPES;
        int shapeFrom = (int) stagePos;
        int shapeTo = (shapeFrom + 1) % NUM_SHAPES;
        float frac = stagePos - shapeFrom;
        float morphT = frac < 0.32f ? 0f : quintic((frac - 0.32f) / 0.68f);

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

        // 4D rotation angles across 4 planes for organic inside-out hyper-rotation
        double axw = rotClock * 0.68;
        double ayz = rotClock * 0.43;
        double azw = rotClock * 0.31;
        double axy = rotClock * 0.24;
        double cxw = Math.cos(axw), sxw = Math.sin(axw);
        double cyz = Math.cos(ayz), syz = Math.sin(ayz);
        double czw = Math.cos(azw), szw = Math.sin(azw);
        double cxy = Math.cos(axy), sxy = Math.sin(axy);

        // Perspective distance contracts slightly on bass kicks for a 4D depth rush
        double d4 = 2.55 - 0.28 * pulse * activity;
        double d3 = 3.15;
        float projScale = (mode == Mode.CLOCK ? 610f : 740f) * (1f + 0.085f * pulse * activity);

        float[][] sA = shapes[shapeFrom];
        float[][] sB = shapes[shapeTo];
        for (int i = 0; i < NUM_VERTS; i++) {
            double x = sA[i][0] + (sB[i][0] - sA[i][0]) * morphT;
            double y = sA[i][1] + (sB[i][1] - sA[i][1]) * morphT;
            double z = sA[i][2] + (sB[i][2] - sA[i][2]) * morphT;
            double w = sA[i][3] + (sB[i][3] - sA[i][3]) * morphT;

            // 4D plane rotations: XW -> YZ -> ZW -> XY
            double nx = x * cxw - w * sxw;
            double nw = x * sxw + w * cxw;
            x = nx;
            w = nw;

            double ny = y * cyz - z * syz;
            double nz = y * syz + z * cyz;
            y = ny;
            z = nz;

            nz = z * czw - w * szw;
            nw = z * szw + w * czw;
            z = nz;
            w = nw;

            nx = x * cxy - y * sxy;
            ny = x * sxy + y * cxy;
            x = nx;
            y = ny;

            double s4 = 1.0 / Math.max(0.65, d4 - w);
            double x3 = x * s4;
            double y3 = y * s4;
            double z3 = z * s4;

            double s3 = 1.0 / Math.max(0.85, d3 - z3);
            screenX[i] = CENTER + (float) (x3 * s3 * projScale);
            screenY[i] = CENTER + (float) (y3 * s3 * projScale);
            vertW[i] = (float) w;
            vertDepth[i] = (float) (w * 0.6 + z3 * 0.7);
        }

        // Compute edge depths & insertion-sort back-to-front
        for (int e = 0; e < NUM_EDGES; e++) {
            edgeDepth[e] = 0.5f * (vertDepth[edgeA[e]] + vertDepth[edgeB[e]]);
            edgeOrder[e] = e;
        }
        for (int i = 1; i < NUM_EDGES; i++) {
            int key = edgeOrder[i];
            float keyD = edgeDepth[key];
            int j = i - 1;
            while (j >= 0 && edgeDepth[edgeOrder[j]] > keyD) {
                edgeOrder[j + 1] = edgeOrder[j];
                j--;
            }
            edgeOrder[j + 1] = key;
        }

        // Draw 32 neon glowing edges back-to-front
        for (int idx = 0; idx < NUM_EDGES; idx++) {
            int e = edgeOrder[idx];
            int a = edgeA[e];
            int b = edgeB[e];
            float depthNorm = clamp01((edgeDepth[e] + 1.05f) / 2.1f);
            double edgeHueRad = (huePhase + edgeHueBase[e] + edgeDepth[e] * 0.14f) * Math.PI * 2.0;

            float glowWidth = (7.0f + 7.5f * depthNorm) * beatBloom;
            float coreWidth = 2.0f + 2.4f * depthNorm;

            int glowAlpha = clamp255(Math.round((34f + 56f * depthNorm) * beatBloom * alphaMaster));
            int coreAlpha = clamp255(Math.round((120f + 135f * depthNorm) * alphaMaster));
            int hotAlpha = clamp255(Math.round((65f + 125f * depthNorm) * alphaMaster));

            glowPaint.setStrokeWidth(glowWidth);
            glowPaint.setColor(rgbWheel(edgeHueRad, 130, 125, glowAlpha));
            canvas.drawLine(screenX[a], screenY[a], screenX[b], screenY[b], glowPaint);

            corePaint.setStrokeWidth(coreWidth);
            corePaint.setColor(rgbWheel(edgeHueRad, 150, 105, coreAlpha));
            canvas.drawLine(screenX[a], screenY[a], screenX[b], screenY[b], corePaint);

            hotPaint.setStrokeWidth(coreWidth * 0.42f);
            hotPaint.setColor(rgbWheel(edgeHueRad, 225, 30, hotAlpha));
            canvas.drawLine(screenX[a], screenY[a], screenX[b], screenY[b], hotPaint);
        }

        // Draw 16 gleaming 4D vertex nodes
        for (int i = 0; i < NUM_VERTS; i++) {
            float dn = clamp01((vertDepth[i] + 1.05f) / 2.1f);
            double vRad = (huePhase + i * 0.0625f + vertW[i] * 0.16f) * Math.PI * 2.0;
            float rGlow = (5.0f + 5.5f * dn) * beatBloom;
            float rCore = 2.0f + 2.4f * dn;

            nodeGlowPaint.setColor(rgbWheel(vRad, 145, 110, clamp255(Math.round((65f + 85f * dn) * alphaMaster))));
            canvas.drawCircle(screenX[i], screenY[i], rGlow, nodeGlowPaint);

            nodeCorePaint.setColor(rgbWheel(vRad, 232, 23, clamp255(Math.round((150f + 105f * dn) * alphaMaster))));
            canvas.drawCircle(screenX[i], screenY[i], rCore, nodeCorePaint);
        }

        // Optional clean time + HR/battery overlay in CLOCK mode
        if (mode == Mode.CLOCK) {
            now.setTime(System.currentTimeMillis());
            String timeStr = (DateFormat.is24HourFormat(getContext()) ? time24Format : time12Format).format(now);
            double tRad = huePhase * Math.PI * 2.0;
            timePaint.setColor(rgbWheel(tRad, 222, 33, 245));
            drawCenteredText(canvas, timeStr, CENTER, 62f, timePaint);

            String hrPart = heartRate > 0 ? ("♥\uFE0E " + heartRate) : "♥\uFE0E --";
            String battPart = batteryPercent >= 0 ? (batteryPercent + "%") : "--%";
            metaPaint.setColor(rgbWheel(tRad + 2.094395, 195, 60, 225));
            drawCenteredText(canvas, hrPart + "  ·  " + battPart, CENTER, 404f, metaPaint);
        }

        // Subtle live bass status dot when BASS is armed
        if (bassEnabled && nowMs >= hudUntilMs) {
            boolean hearing = bassPulse.hasSignal(nowMs);
            int dotAlpha = hearing ? clamp255(Math.round(115f + 140f * pulse)) : 60;
            float dotRadius = hearing ? (3.2f + 2.8f * pulse) : 2.6f;
            float dotY = mode == Mode.CLOCK ? 378f : 422f;
            dotPaint.setColor(rgbWheel(huePhase * Math.PI * 2.0 + 2.094395, 180, 75, dotAlpha));
            canvas.drawCircle(CENTER, dotY, dotRadius, dotPaint);
        }

        // Brief HUD pill when switching modes / speeds / bass
        if (nowMs < hudUntilMs && !hudText.isEmpty()) {
            float fade = Math.min(1f, (hudUntilMs - nowMs) / 350f);
            hudPaint.setColor(Color.argb(Math.round(215f * fade), 232, 236, 250));
            drawCenteredText(canvas, hudText, CENTER, 384f, hudPaint);
        }

        canvas.restore();
    }

    private static float quintic(float t) {
        float c = clamp01(t);
        return c * c * c * (c * (c * 6f - 15f) + 10f);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
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
