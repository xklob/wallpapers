package io.klob.wallpapers.live;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** One shared stroke per honeycomb edge, with color traveling through its junctions. */
final class ConnectedHoneycomb {
    private static final class Vertex {
        final float x, y;
        final List<Edge> edges = new ArrayList<>(3);
        double distance = Double.POSITIVE_INFINITY;
        Vertex(float x, float y) { this.x = x; this.y = y; }
    }

    private static final class Edge {
        final Vertex a, b;
        final double length;
        Edge(Vertex a, Vertex b, float sx, float sy) {
            this.a = a; this.b = b;
            length = Math.hypot((b.x - a.x) * sx, (b.y - a.y) * sy);
            a.edges.add(this); b.edges.add(this);
        }
    }

    private static final class Visit implements Comparable<Visit> {
        final Vertex vertex;
        final double distance;
        Visit(Vertex vertex, double distance) { this.vertex = vertex; this.distance = distance; }
        @Override public int compareTo(Visit other) { return Double.compare(distance, other.distance); }
    }

    private static long key(int x, int y) { return ((long)x << 32) | (y & 0xffffffffL); }

    private static Vertex vertex(Map<Long, Vertex> vertices, int x, int y,
                                 float x0, float y0, float dx, float dy) {
        return vertices.computeIfAbsent(key(x, y), unused -> new Vertex(x0 + x * dx / 2, y0 + y * dy / 3));
    }

    static void draw(Canvas canvas, Rect bounds, float x0, float y0, float dx, float dy,
                     float sx, float sy, float stroke, float colorStroke, Shader gradient,
                     int palette, boolean traveling) {
        Map<Long, Vertex> vertices = new HashMap<>();
        List<Edge> edges = new ArrayList<>();
        // Include the fixed source vertex even when framing crops it offscreen.
        int first = Math.min(0, (int)Math.floor((bounds.top - y0) / dy) - 2);
        int last = Math.max(0, (int)Math.ceil((bounds.bottom - y0) / dy) + 2);
        int left = Math.min(0, (int)Math.floor((bounds.left - x0) / dx) - 2);
        int right = Math.max(0, (int)Math.ceil((bounds.right - x0) / dx) + 2);
        for (int row = first; row <= last; row++) {
            for (int col = left; col <= right; col++) {
                int x = 2 * col + (row & 1), y = 3 * row;
                Vertex top = vertex(vertices, x, y - 2, x0, y0, dx, dy);
                Vertex upper = vertex(vertices, x + 1, y - 1, x0, y0, dx, dy);
                Vertex lower = vertex(vertices, x + 1, y + 1, x0, y0, dx, dy);
                Vertex bottom = vertex(vertices, x, y + 2, x0, y0, dx, dy);
                // Each cell owns only its three right edges. Its neighbors own
                // the left edges, so every line and intersection stays shared.
                edges.add(new Edge(top, upper, sx, sy));
                edges.add(new Edge(upper, lower, sx, sy));
                edges.add(new Edge(lower, bottom, sx, sy));
            }
        }
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeWidth(traveling ? colorStroke : stroke);
        if (!traveling) {
            Path path = new Path();
            for (Edge edge : edges) {
                path.moveTo(edge.a.x, edge.a.y); path.lineTo(edge.b.x, edge.b.y);
            }
            ink.setShader(gradient);
            canvas.drawPath(path, ink);
            return;
        }

        Vertex origin = vertices.get(key(0, -2));
        origin.distance = 0;
        PriorityQueue<Visit> queue = new PriorityQueue<>();
        queue.add(new Visit(origin, 0));
        while (!queue.isEmpty()) {
            Visit visit = queue.remove();
            if (visit.distance != visit.vertex.distance) continue;
            for (Edge edge : visit.vertex.edges) {
                Vertex next = edge.a == visit.vertex ? edge.b : edge.a;
                double distance = visit.distance + edge.length;
                if (distance < next.distance) {
                    next.distance = distance;
                    queue.add(new Visit(next, distance));
                }
            }
        }
        // Distance follows the actual lines, including their turns and branches.
        // The renderer advances hue each frame; this palette is baked only when
        // the layout changes. Shared vertices always receive the same color.
        double perimeter = 4 * Math.hypot(dx * sx / 2, dy * sy / 3) + 4 * dy * sy / 3;
        float[] hsv = new float[3];
        Color.colorToHSV(palette, hsv);
        float baseHue = hsv[0];
        ink.setBlendMode(BlendMode.SRC_ATOP);
        int[] colors = new int[13];
        for (Edge edge : edges) {
            for (int i = 0; i < colors.length; i++) {
                double t = i / (double)(colors.length - 1);
                double distance = Math.min(edge.a.distance + t * edge.length,
                        edge.b.distance + (1 - t) * edge.length);
                hsv[0] = (float)((baseHue - distance / perimeter * 360) % 360 + 360) % 360;
                colors[i] = Color.HSVToColor(hsv);
            }
            ink.setShader(new LinearGradient(edge.a.x, edge.a.y, edge.b.x, edge.b.y,
                    colors, null, Shader.TileMode.CLAMP));
            canvas.drawLine(edge.a.x, edge.a.y, edge.b.x, edge.b.y, ink);
        }
    }

    private ConnectedHoneycomb() {}
}
