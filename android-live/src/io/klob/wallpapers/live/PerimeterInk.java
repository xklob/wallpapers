package io.klob.wallpapers.live;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.SweepGradient;

import java.util.HashMap;
import java.util.Map;

/** Bakes one cycle of hue along each hexagon's perimeter. */
final class PerimeterInk {
    private static final int SAMPLES_PER_SEGMENT = 8;
    private static final int PHASES = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Long, HexagonColors[]> colorsBySize = new HashMap<>();
    private final float scaleX, scaleY;
    private final float stroke, faintStroke;
    private final int faintAlpha;
    private final boolean cubes;
    private final float[] hsv = new float[3];
    private final float baseHue;

    PerimeterInk(int paletteColor, float scaleX, float scaleY, float stroke,
                 float faintStroke, int faintAlpha, boolean cubes, boolean recolor) {
        Color.colorToHSV(paletteColor, hsv);
        baseHue = hsv[0] / 360f;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.stroke = stroke;
        this.faintStroke = faintStroke;
        this.faintAlpha = recolor ? 255 : faintAlpha;
        this.cubes = cubes;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        // Keep the exact SVG coverage, including cutoffs and anti-aliased edges.
        if (recolor) paint.setBlendMode(BlendMode.SRC_ATOP);
    }

    void draw(Canvas canvas, int row, int column, float x, float y, float halfWidth, float halfHeight) {
        long size = ((long) Float.floatToIntBits(halfWidth) << 32) | (Float.floatToIntBits(halfHeight) & 0xffffffffL);
        HexagonColors[] offsets = colorsBySize.computeIfAbsent(size, key -> new HexagonColors[PHASES]);
        // Stagger neighboring cells on the triangular lattice.
        int index = Math.floorMod(column + 2 * (row & 1), PHASES);
        HexagonColors colors = offsets[index];
        if (colors == null) {
            colors = new HexagonColors(halfWidth, halfHeight, baseHue + index / 12f);
            offsets[index] = colors;
        }

        canvas.save();
        canvas.translate(x, y);
        paint.setStrokeWidth(stroke);
        paint.setAlpha(255);
        paint.setShader(colors.outlineColor);
        canvas.drawPath(colors.outline, paint);
        if (cubes) {
            for (int vertex = 1; vertex < 7; vertex++) {
                boolean faint = (vertex & 1) != 0;
                paint.setStrokeWidth(faint ? faintStroke : stroke);
                paint.setAlpha(faint ? faintAlpha : 255);
                paint.setShader(colors.spokeColors[vertex - 1]);
                canvas.drawLine(0, 0, colors.x[vertex], colors.y[vertex], paint);
            }
        }
        canvas.restore();
    }

    private int color(float hue) {
        hsv[0] = (hue - (float)Math.floor(hue)) * 360f;
        return Color.HSVToColor(hsv);
    }

    private final class HexagonColors {
        final Path outline = new Path();
        final SweepGradient outlineColor;
        final LinearGradient[] spokeColors;
        final float[] x, y;

        HexagonColors(float a, float b, float hue) {
            WideGrid.traceHexagon(outline, 0, 0, a, b);
            // SweepGradient begins at 3 o'clock. Split that edge at its midpoint,
            // then walk clockwise, using the length of each edge on the display.
            x = new float[]{a, a, 0, -a, -a, 0, a, a};
            y = new float[]{0, b / 2f, b, b / 2f, -b / 2f, -b, -b / 2f, 0};
            float[] distance = new float[x.length];
            for (int i = 1; i < x.length; i++) {
                distance[i] = distance[i - 1] + (float)Math.hypot(
                        (x[i] - x[i - 1]) * scaleX, (y[i] - y[i - 1]) * scaleY);
            }
            float perimeter = distance[distance.length - 1];
            int last = (x.length - 1) * SAMPLES_PER_SEGMENT;
            float[] angles = new float[last + 1];
            int[] colors = new int[last + 1];
            for (int edge = 0; edge < x.length - 1; edge++) {
                for (int step = 0; step < SAMPLES_PER_SEGMENT; step++) {
                    float t = step / (float)SAMPLES_PER_SEGMENT;
                    float px = x[edge] + (x[edge + 1] - x[edge]) * t;
                    float py = y[edge] + (y[edge + 1] - y[edge]) * t;
                    double angle = Math.atan2(py, px) / (2 * Math.PI);
                    int i = edge * SAMPLES_PER_SEGMENT + step;
                    angles[i] = (float)(angle < 0 ? angle + 1 : angle);
                    float length = distance[edge] + (distance[edge + 1] - distance[edge]) * t;
                    // The renderer adds phase over time. Decreasing hue along the
                    // contour makes a given color advance clockwise with that phase.
                    colors[i] = color(hue - length / perimeter);
                }
            }
            angles[last] = 1;
            colors[last] = colors[0];
            outlineColor = new SweepGradient(0, 0, colors, angles);

            // Tie the cube's spokes to the hue at their corresponding corners.
            spokeColors = cubes ? new LinearGradient[6] : null;
            if (spokeColors != null) {
                float center = hue - 1f / 6f;
                for (int i = 1; i < x.length - 1; i++) {
                    float delta = hue - distance[i] / perimeter - center;
                    delta -= Math.round(delta);
                    int[] ramp = new int[SAMPLES_PER_SEGMENT + 1];
                    for (int step = 0; step < ramp.length; step++) {
                        ramp[step] = color(center + delta * step / SAMPLES_PER_SEGMENT);
                    }
                    ramp[ramp.length - 1] = colors[i * SAMPLES_PER_SEGMENT];
                    spokeColors[i - 1] = new LinearGradient(0, 0, x[i], y[i], ramp, null, Shader.TileMode.CLAMP);
                }
            }
        }
    }
}
