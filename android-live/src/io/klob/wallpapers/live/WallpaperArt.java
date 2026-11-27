package io.klob.wallpapers.live;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Build;

import com.caverock.androidsvg.PreserveAspectRatio;
import com.caverock.androidsvg.RenderOptions;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class WallpaperArt {
    enum Layer {
        FULL(null),
        ARTWORK(".rotation-overlay { display: none; } .rotation-pattern { clip-path: none; }"),
        COLOR_MASK(".rotation-overlay, .rotation-backdrop { display: none; }"),
        TRANSFORMED_COLOR_MASK(COLOR_MASK.css + " .rotation-pattern { clip-path: none; }"),
        OVERLAYS(".rotation-pattern, .rotation-backdrop { display: none; }"),
        FRAME(OVERLAYS.css + " .rotation-gutter { display: inline; }");
        final String css;
        Layer(String css) { this.css = css; }
    }

    static String assetPath(String path) {
        return MandelbrotZoom.uses(path) ? path.substring(MandelbrotZoom.PREFIX.length()) : path;
    }

    static boolean hasScene(String path) {
        return MandelbrotZoom.uses(path);
    }

    static Bitmap renderStatic(Context context, String path, int width, int height) throws IOException {
        return renderStatic(context, path, width, height, null, null, 0);
    }

    static Bitmap renderStatic(Context context, String path, int width, int height, Catalog.Model model,
                               PanelCorners corners) throws IOException {
        return renderStatic(context, path, width, height, model, corners, 0);
    }

    static Bitmap renderStatic(Context context, String path, int width, int height, Catalog.Model model,
                               PanelCorners corners, int rotation) throws IOException {
        return renderStatic(context, path, width, height, model, corners, rotation, WallpaperTone.NEUTRAL);
    }

    static Bitmap renderStatic(Context context, String path, int width, int height, Catalog.Model model,
                               PanelCorners corners, int rotation, WallpaperTone tone) throws IOException {
        return renderStatic(context, path, width, height, model, corners, rotation, ArtworkTransform.FIT, tone);
    }

    static Bitmap renderStatic(Context context, String path, int width, int height, Catalog.Model model,
                               PanelCorners corners, int rotation, int zoom, WallpaperTone tone) throws IOException {
        // ImageWallpaper zooms the committed bitmap around its center at rest.
        // Submit a padded display-space image so the frame and camera stay visible.
        Resources resources = context.getResources();
        int id = resources.getIdentifier("config_wallpaperMaxScale", "dimen", "android");
        float launcherZoom = id == 0 ? 1.10f : resources.getFloat(id);
        float scale = 1f / Math.max(1f, launcherZoom);
        boolean framed = ArtworkTransform.isActive(rotation, zoom);
        if (!framed && tone.filter() == null) {
            return render(context, path, width, height, scale, model, corners);
        }

        SVG svg = loadSvg(context, path, width, height, model, corners);
        Bitmap bitmap = createBitmap(width, height, true);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale, width / 2f, height / 2f);
        canvas.clipRect(0, 0, width, height);

        int toneLayer = tone.saveLayer(canvas, width, height);
        boolean scene = hasScene(path);
        if (toneLayer != 0 && !scene) canvas.drawColor(Color.BLACK);
        if (scene || framed) {
            canvas.save();
            float magnification = ArtworkTransform.apply(canvas, width, height, rotation, zoom);
            if (scene) drawScene(context, canvas, path, width, height, magnification);
            else if (zoom < ArtworkTransform.FIT) WideGrid.load(context, path).draw(canvas, width, height, magnification * scale);
            else drawSvg(svg, canvas, width, height, Layer.ARTWORK);
            canvas.restore();
        }
        drawSvg(svg, canvas, width, height, framed && !scene ? Layer.FRAME : Layer.FULL);
        if (toneLayer != 0) canvas.restoreToCount(toneLayer);
        return bitmap;
    }

    static Bitmap render(Context context, String path, int width, int height) throws IOException {
        return render(context, path, width, height, 1f, null, null);
    }

    static Bitmap render(Context context, String path, int width, int height, float scale,
                         Catalog.Model model, PanelCorners corners) throws IOException {
        return render(context, path, width, height, scale, model, corners, true);
    }

    static Bitmap render(Context context, String path, int width, int height, float scale,
                         Catalog.Model model, PanelCorners corners, boolean includeScene) throws IOException {
        SVG svg = loadSvg(context, path, width, height, model, corners);
        Bitmap bitmap = createBitmap(width, height, includeScene || !hasScene(path));
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale, width / 2f, height / 2f);
        if (includeScene && hasScene(path)) {
            canvas.clipRect(0, 0, width, height);
            drawScene(context, canvas, path, width, height, 1f);
        }
        drawSvg(svg, canvas, width, height, Layer.FULL);
        return bitmap;
    }

    static Bitmap renderLayer(Context context, String path, int width, int height, Catalog.Model model,
                              PanelCorners corners, Layer layer) throws IOException {
        SVG svg = loadSvg(context, path, width, height, model, corners);
        Bitmap bitmap = createBitmap(width, height, layer == Layer.ARTWORK);
        drawSvg(svg, new Canvas(bitmap), width, height, layer);
        return bitmap;
    }

    static Bitmap renderTravelingColors(Context context, String path, int width, int height,
                                        Catalog.Model model, PanelCorners corners) throws IOException {
        SVG svg = loadSvg(context, path, width, height, model, corners);
        WideGrid grid = WideGrid.load(context, path);
        Bitmap bitmap = createBitmap(width, height, true);
        Canvas canvas = new Canvas(bitmap);
        drawSvg(svg, canvas, width, height, Layer.FULL);
        int[] row = new int[width];
        byte[] brightness = captureBrightness(bitmap, row);

        // Bake the fitted wallpaper as one texture. The frame and artwork
        // meet at antialiased edges that must share the same brightness.
        Bitmap coloredArt = createBitmap(width, height, false);
        Canvas artCanvas = new Canvas(coloredArt);
        drawSvg(svg, artCanvas, width, height, Layer.COLOR_MASK);
        grid.drawTravelingColors(artCanvas, width, height, 1, false);
        bitmap.eraseColor(Color.BLACK);
        canvas.drawBitmap(coloredArt, 0, 0, null);
        coloredArt.recycle();
        drawSvg(svg, canvas, width, height, Layer.OVERLAYS);
        restoreBrightness(bitmap, row, brightness);
        return bitmap;
    }

    private static Bitmap createBitmap(int width, int height, boolean black) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (black) bitmap.eraseColor(Color.BLACK);
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        return bitmap;
    }

    private static SVG loadSvg(Context context, String path, int width, int height,
                               Catalog.Model model, PanelCorners corners) throws IOException {
        try (InputStream file = context.getAssets().open(assetPath(path))) {
            if (corners == null || !corners.matches(model, Build.MODEL, Build.MANUFACTURER, width, height)
                    || !path.contains("/rgb_border/")) return SVG.getFromInputStream(file);
            InputStreamReader reader = new InputStreamReader(file, StandardCharsets.UTF_8);
            StringBuilder source = new StringBuilder();
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) != -1) source.append(buffer, 0, n);
            return SVG.getFromString(corners.fit(source.toString(), model.width > model.height));
        } catch (SVGParseException e) {
            throw new IOException("Invalid wallpaper SVG: " + path, e);
        }
    }

    static ArtworkLayer artworkLayer(Context context, String path, int width, int height,
                                     Catalog.Model model, PanelCorners corners, boolean wide, boolean traveling) throws IOException {
        return new ArtworkLayer(wide ? null : loadSvg(context, path, width, height, model, corners),
                wide || traveling ? WideGrid.load(context, path) : null, width, height, traveling);
    }

    static final class ArtworkLayer {
        private final SVG svg;
        private final WideGrid grid;
        private final int width, height;
        private final boolean traveling;

        ArtworkLayer(SVG svg, WideGrid grid, int width, int height, boolean traveling) {
            this.svg = svg;
            this.grid = grid;
            this.width = width;
            this.height = height;
            this.traveling = traveling;
        }

        Bitmap rasterize(int rotation, int zoom) {
            Bitmap bitmap = createBitmap(width, height, !traveling);
            Canvas canvas = new Canvas(bitmap);
            float magnification = ArtworkTransform.apply(canvas, width, height, rotation, zoom);
            if (traveling) {
                if (svg != null) {
                    boolean transformed = ArtworkTransform.isActive(rotation, zoom);
                    drawSvg(svg, canvas, width, height, transformed ? Layer.TRANSFORMED_COLOR_MASK : Layer.COLOR_MASK);
                } else {
                    grid.draw(canvas, width, height, magnification);
                }
                int[] row = new int[width];
                byte[] brightness = captureBrightness(bitmap, row);
                grid.drawTravelingColors(canvas, width, height, magnification, svg == null);
                restoreBrightness(bitmap, row, brightness);
            } else if (grid != null) {
                grid.draw(canvas, width, height, magnification);
            } else {
                drawSvg(svg, canvas, width, height, Layer.ARTWORK);
            }
            return bitmap;
        }
    }

    private static byte[] captureBrightness(Bitmap bitmap, int[] row) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        byte[] brightness = new byte[width * height];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                brightness[y * width + x] = (byte) peakChannel(row[x]);
            }
        }
        return brightness;
    }

    private static void restoreBrightness(Bitmap bitmap, int[] row, byte[] brightness) {
        int width = bitmap.getWidth();
        // A gradient or two crossing strokes can blend RGB hues. Match the
        // original value before uploading the palette, so that only hue moves.
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            boolean changed = false;
            for (int x = 0; x < width; x++) {
                int desired = brightness[y * width + x] & 255;
                int pixel = row[x];
                int value = peakChannel(pixel);
                if (value == 0 || desired == value) continue;
                float gain = desired / (float) value;
                row[x] = Color.argb(Color.alpha(pixel), Math.round(gain * Color.red(pixel)),
                        Math.round(gain * Color.green(pixel)), Math.round(gain * Color.blue(pixel)));
                changed = true;
            }
            if (changed) bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
    }

    private static int peakChannel(int pixel) {
        return Math.max(Color.red(pixel), Math.max(Color.green(pixel), Color.blue(pixel)));
    }

    private static void drawScene(Context context, Canvas canvas, String path, int width, int height,
                                  float magnification) {
        if (MandelbrotZoom.uses(path)) MandelbrotZoom.drawStill(context, canvas, width, height);
    }

    private static void drawSvg(SVG svg, Canvas canvas, int width, int height, Layer layer) {
        RectF source = svg.getDocumentViewBox();
        canvas.save();
        boolean rotate = orient(canvas, source.width(), source.height(), width, height);
        int viewWidth = rotate ? height : width;
        int viewHeight = rotate ? width : height;
        svg.setDocumentWidth(viewWidth);
        svg.setDocumentHeight(viewHeight);
        svg.setDocumentPreserveAspectRatio(PreserveAspectRatio.FULLSCREEN);
        if (layer.css == null) svg.renderToCanvas(canvas, new RectF(0, 0, viewWidth, viewHeight));
        else svg.renderToCanvas(canvas, RenderOptions.create().viewPort(0, 0, viewWidth, viewHeight).css(layer.css));
        canvas.restore();
    }

    static boolean orient(Canvas canvas, float sourceWidth, float sourceHeight, int width, int height) {
        boolean rotate = (sourceWidth > sourceHeight) != (width > height);
        if (rotate) {
            // Foldable landscape presets use a counterclockwise rotation from portrait.
            if (width > height) {
                canvas.translate(0, height);
                canvas.rotate(-90);
            } else {
                canvas.translate(width, 0);
                canvas.rotate(90);
            }
        }
        return rotate;
    }

    private WallpaperArt() {}
}
