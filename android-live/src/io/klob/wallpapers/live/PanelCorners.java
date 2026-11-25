package io.klob.wallpapers.live;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.Display;
import android.view.RoundedCorner;
import android.view.WindowInsets;
import android.view.WindowMetrics;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The active internal panel, in display coordinates for the current rotation. */
final class PanelCorners {
    private static final Pattern FRAME = Pattern.compile("data-device-frame=\"([^\"]+)\"");
    private static final Pattern SHAPES = Pattern.compile(
            "<rect [^<>]*id=\"device-clip\"[^<>]*/>|<path id=\"device-(top|right|bottom|left)\" d=\"[^\"]*\"");
    private static final String[] SIDES = {"top", "right", "bottom", "left"};
    private static final double DIAGONAL = Math.sqrt(0.5);

    final int width, height;
    private final RoundedCorner[] corners;

    PanelCorners(int width, int height, RoundedCorner[] corners) {
        this.width = width;
        this.height = height;
        this.corners = corners;
    }

    static PanelCorners read(Display display, WindowMetrics metrics) {
        if (display == null || display.getDisplayId() != Display.DEFAULT_DISPLAY) return null;
        Rect bounds = metrics.getBounds();
        WindowInsets insets = metrics.getWindowInsets();
        RoundedCorner[] corners = new RoundedCorner[4];
        boolean reported = false;
        for (int i = 0; i < corners.length; i++) {
            corners[i] = insets.getRoundedCorner(i);
            reported |= corners[i] != null && corners[i].getRadius() > 0;
        }
        return reported ? new PanelCorners(bounds.width(), bounds.height(), corners) : null;
    }

    boolean sameGeometry(PanelCorners other) {
        return other != null && width == other.width && height == other.height && Arrays.equals(corners, other.corners);
    }

    boolean matches(Catalog.Model model, String hardware, String manufacturer, int targetWidth, int targetHeight) {
        if (model == null || !model.matches(hardware) || !model.manufacturer.equalsIgnoreCase(manufacturer))
            return false;
        // A foldable can expose two panels under the same hardware model code.
        // A scaled thumbnail is eligible only if it shows the active panel orientation.
        return sameAspect(width, height, targetWidth, targetHeight)
                && sameAspect(Math.min(width, height), Math.max(width, height),
                              Math.min(model.width, model.height), Math.max(model.width, model.height));
    }

    private static boolean sameAspect(int w, int h, int otherW, int otherH) {
        return h > 0 && otherW > 0 && Math.abs((double) w * otherH / (h * (double) otherW) - 1) < 0.01;
    }

    String fit(String svg, boolean sourceLandscape) {
        Matcher frame = FRAME.matcher(svg);
        if (!frame.find()) return svg;
        // prepare_art stores the frame's normalized viewport box, fallback radii as
        // fractions of that box, then the clip and stroke boxes in their own SVG coordinates.
        String[] fields = frame.group(1).split(" ");
        double[] meta = new double[fields.length];
        for (int i = 0; i < fields.length; i++) meta[i] = Double.parseDouble(fields[i]);
        boolean rotate = sourceLandscape != (width > height);
        double[][] radii = new double[4][2];
        for (int i = 0; i < 4; i++) {
            int corner = rotate ? (i + (sourceLandscape ? 3 : 1)) % 4 : i;
            radii[corner][0] = meta[4];
            radii[corner][1] = meta[5];
            RoundedCorner report = corners[i];
            if (report == null || report.getRadius() <= 0) continue;
            Point center = report.getCenter();
            double x = (double) center.x / width, y = (double) center.y / height;
            double cx = rotate ? (sourceLandscape ? y : 1 - y) : x;
            double cy = rotate ? (sourceLandscape ? 1 - x : x) : y;
            double rx = (corner == 0 || corner == 3) ? cx - meta[0] : meta[0] + meta[2] - cx;
            double ry = corner < 2 ? cy - meta[1] : meta[1] + meta[3] - cy;
            // Keep the authored gutter. Center the inset arc on the panel's reported center.
            radii[corner][0] = clampRadius(rx, (double) report.getRadius() / (rotate ? height : width), meta[2]);
            radii[corner][1] = clampRadius(ry, (double) report.getRadius() / (rotate ? width : height), meta[3]);
            if (radii[corner][0] == 0 || radii[corner][1] == 0)
                radii[corner][0] = radii[corner][1] = 0;
        }
        Matcher shapes = SHAPES.matcher(svg);
        StringBuffer out = new StringBuffer(svg.length());
        while (shapes.find()) {
            String side = shapes.group(1);
            String replacement;
            if (side == null) {
                StringBuilder d = new StringBuilder();
                for (int i = 0; i < 4; i++) d.append(sidePath(i, meta, 6, radii, i == 0));
                replacement = "<path d=\"" + d + "Z\"/>";
            } else {
                int index = Arrays.asList(SIDES).indexOf(side);
                replacement = "<path d=\"" + sidePath(index, meta, 10, radii, true) + "\"";
            }
            shapes.appendReplacement(out, replacement);
        }
        shapes.appendTail(out);
        return out.toString();
    }

    private static double clampRadius(double distance, double radius, double span) {
        return Math.max(0, Math.min(0.5, Math.min(distance, radius) / span));
    }

    // Each side owns half of the adjoining corner arcs, preserving all four original gradients.
    private static String sidePath(int side, double[] box, int offset, double[][] radii, boolean move) {
        StringBuilder path = new StringBuilder();
        for (int end = 0; end <= 1; end++) {
            int c = (side + end) % 4;
            int sx = (c == 0 || c == 3) ? -1 : 1;
            int sy = c < 2 ? -1 : 1;
            double rx = radii[c][0] * box[offset + 2];
            double ry = radii[c][1] * box[offset + 3];
            double cx = box[offset] + (sx > 0 ? box[offset + 2] : 0) - sx * rx;
            double cy = box[offset + 1] + (sy > 0 ? box[offset + 3] : 0) - sy * ry;
            String diagonal = point(cx + sx * rx * DIAGONAL, cy + sy * ry * DIAGONAL);
            String tangent = side % 2 == 0 ? point(cx, cy + sy * ry) : point(cx + sx * rx, cy);
            String arc = (rx == 0 || ry == 0) ? "L" : "A" + point(rx, ry) + " 0 0 1 ";
            if (end == 0) path.append(move ? "M" : "L").append(diagonal).append(arc).append(tangent);
            else path.append("L").append(tangent).append(arc).append(diagonal);
        }
        return path.toString();
    }

    private static String point(double x, double y) {
        return Float.toString((float) x) + "," + Float.toString((float) y);
    }
}
