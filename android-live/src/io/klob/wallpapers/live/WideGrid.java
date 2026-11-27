package io.klob.wallpapers.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** The authored hexagonal lattice continued outside its original SVG viewport. */
final class WideGrid {
    private final float viewWidth, viewHeight;
    private final float sx, sy, tx, ty;
    private final float x0, y0, dx, dy, halfWidth, halfHeight;
    private final float stroke, faintStroke, faintAlpha;
    private final boolean cubes, connected;
    private final LinearGradient gradient;
    private final int paletteColor;
    private final Map<Integer, float[]> authoredCells = new HashMap<>();

    static WideGrid load(Context context, String svgPath) throws IOException {
        String path = "wide-grid/" + svgPath.substring(0, svgPath.length() - 4) + ".bin";
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(context.getAssets().open(path)))) {
            return new WideGrid(in);
        }
    }

    private WideGrid(DataInputStream in) throws IOException {
        viewWidth = in.readFloat();
        viewHeight = in.readFloat();
        sx = in.readFloat(); sy = in.readFloat(); tx = in.readFloat(); ty = in.readFloat();
        x0 = in.readFloat(); y0 = in.readFloat(); dx = in.readFloat(); dy = in.readFloat();
        halfWidth = in.readFloat(); halfHeight = in.readFloat();
        stroke = in.readFloat();
        float x1 = in.readFloat(), y1 = in.readFloat(), x2 = in.readFloat(), y2 = in.readFloat();
        faintStroke = in.readFloat();
        faintAlpha = in.readFloat();
        cubes = in.readBoolean();
        connected = in.readBoolean();
        int stops = in.readUnsignedShort();
        int[] colors = new int[stops];
        float[] offsets = new float[stops];
        for (int i = 0; i < stops; i++) {
            offsets[i] = in.readFloat();
            colors[i] = in.readInt();
        }
        // The authored gradient is exact in its original range. Mirror it outside
        // that range to keep the color continuous rather than clamp to one hue.
        gradient = new LinearGradient(x1, y1, x2, y2, colors, offsets, Shader.TileMode.MIRROR);
        paletteColor = colors[0];
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int row = in.readShort(), col = in.readShort();
            authoredCells.put(index(row, col), new float[]{in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat()});
        }
    }

    void draw(Canvas canvas, int width, int height, float magnification) {
        draw(canvas, width, height, magnification, false, false);
    }

    void drawTravelingColors(Canvas canvas, int width, int height, float magnification, boolean extend) {
        draw(canvas, width, height, magnification, true, extend);
    }

    private void draw(Canvas canvas, int width, int height, float magnification, boolean traveling, boolean extend) {
        canvas.save();
        boolean rotate = WallpaperArt.orient(canvas, viewWidth, viewHeight, width, height);
        int w = rotate ? height : width;
        int h = rotate ? width : height;
        canvas.translate(tx * w, ty * h);
        float scaleX = sx * w, scaleY = sy * h;
        canvas.scale(scaleX, scaleY);
        Rect bounds = new Rect();
        if (canvas.getClipBounds(bounds)) {
            float minStroke = .8f / (Math.min(scaleX, scaleY) * magnification);
            float outlineWidth = Math.max(stroke, minStroke);
            if (connected) {
                ConnectedHoneycomb.draw(canvas, bounds, x0, y0, dx, dy, scaleX, scaleY,
                        outlineWidth, traveling ? outlineWidth + 2 * Math.max(.6f, minStroke) : outlineWidth,
                        gradient, paletteColor, traveling);
                canvas.restore();
                return;
            }
            float faintWidth = Math.max(faintStroke, minStroke * .6f);
            // Let the color reach all subpixels at the edge of the original mask.
            float padding = traveling ? 2 * Math.max(.6f, minStroke) : 0;
            PerimeterInk perimeterInk = traveling ? new PerimeterInk(paletteColor, scaleX, scaleY,
                    outlineWidth + padding, faintWidth + padding, Math.round(faintAlpha * 255), cubes, true) : null;
            // One extra row and column includes any cell that crosses an edge.
            int first = (int)Math.floor((bounds.top - y0) / dy) - 1;
            int last = (int)Math.ceil((bounds.bottom - y0) / dy) + 1;
            Path outlines = new Path(), lightSpokes = new Path(), darkSpokes = new Path();
            for (int row = first; row <= last; row++) {
                float start = x0 + (row & 1) * dx / 2;
                int left = (int)Math.floor((bounds.left - start) / dx) - 1;
                int right = (int)Math.ceil((bounds.right - start) / dx) + 1;
                for (int col = left; col <= right; col++) {
                    float[] cell = authoredCells.get(index(row, col));
                    if (traveling && !extend && cell == null) continue;
                    float x = cell == null ? start + col * dx : cell[0];
                    float y = cell == null ? y0 + row * dy : cell[1];
                    float a = cell == null ? halfWidth : cell[2];
                    float b = cell == null ? halfHeight : cell[3];
                    if (perimeterInk != null) {
                        perimeterInk.draw(canvas, row, col, x, y, a, b);
                        continue;
                    }
                    traceHexagon(outlines, x, y, a, b);
                    if (cubes) {
                        spoke(lightSpokes, x, y, x + a, y - b / 2);
                        spoke(lightSpokes, x, y, x, y + b);
                        spoke(lightSpokes, x, y, x - a, y - b / 2);
                        spoke(darkSpokes, x, y, x, y - b);
                        spoke(darkSpokes, x, y, x + a, y + b / 2);
                        spoke(darkSpokes, x, y, x - a, y + b / 2);
                    }
                }
            }
            if (!traveling) {
                Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
                ink.setStyle(Paint.Style.STROKE);
                ink.setShader(gradient);
                ink.setStrokeJoin(Paint.Join.ROUND);
                ink.setStrokeWidth(outlineWidth);
                canvas.drawPath(outlines, ink);
                if (cubes) {
                    ink.setStrokeWidth(faintWidth);
                    ink.setAlpha(Math.round(255 * faintAlpha));
                    canvas.drawPath(darkSpokes, ink);
                    ink.setAlpha(255);
                    ink.setStrokeWidth(outlineWidth);
                    canvas.drawPath(lightSpokes, ink);
                }
            }
        }
        canvas.restore();
    }

    static void traceHexagon(Path path, float x, float y, float a, float b) {
        path.moveTo(x, y - b);
        path.lineTo(x + a, y - b / 2f);
        path.lineTo(x + a, y + b / 2f);
        path.lineTo(x, y + b);
        path.lineTo(x - a, y + b / 2f);
        path.lineTo(x - a, y - b / 2f);
        path.close();
    }

    private static void spoke(Path path, float x, float y, float endX, float endY) {
        path.moveTo(x, y);
        path.lineTo(endX, endY);
    }

    private static int index(int row, int col) {
        return (row << 16) | (col & 0xffff);
    }
}
