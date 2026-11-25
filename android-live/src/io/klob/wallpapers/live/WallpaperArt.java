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
        FRAME(".rotation-pattern, .rotation-backdrop { display: none; } .rotation-gutter { display: inline; }");
        final String css;
        Layer(String css) { this.css = css; }
    }

    static String assetPath(String path) {
        return MandelbrotZoom.uses(path) ? path.substring(MandelbrotZoom.PREFIX.length()) : path;
    }

    static boolean hasScene(String path) {
        return MengerWindows.uses(path) || MandelbrotZoom.uses(path);
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
                                     Catalog.Model model, PanelCorners corners) throws IOException {
        return new ArtworkLayer(loadSvg(context, path, width, height, model, corners), width, height);
    }

    static final class ArtworkLayer {
        private final SVG svg;
        private final int width, height;

        ArtworkLayer(SVG svg, int width, int height) {
            this.svg = svg;
            this.width = width;
            this.height = height;
        }

        Bitmap rasterize(int rotation, int zoom) {
            Bitmap bitmap = createBitmap(width, height, true);
            Canvas canvas = new Canvas(bitmap);
            ArtworkTransform.apply(canvas, width, height, rotation, zoom);
            drawSvg(svg, canvas, width, height, Layer.ARTWORK);
            return bitmap;
        }
    }

    private static void drawScene(Context context, Canvas canvas, String path, int width, int height,
                                  float magnification) {
        if (MandelbrotZoom.uses(path)) MandelbrotZoom.drawStill(context, canvas, width, height);
        else new MengerWindows(context).draw(canvas, width, height, 0, magnification);
    }

    private static void drawSvg(SVG svg, Canvas canvas, int width, int height, Layer layer) {
        RectF source = svg.getDocumentViewBox();
        boolean rotate = (source.width() > source.height()) != (width > height);
        int viewWidth = rotate ? height : width;
        int viewHeight = rotate ? width : height;
        svg.setDocumentWidth(viewWidth);
        svg.setDocumentHeight(viewHeight);
        svg.setDocumentPreserveAspectRatio(PreserveAspectRatio.FULLSCREEN);
        canvas.save();
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
        if (layer.css == null) svg.renderToCanvas(canvas, new RectF(0, 0, viewWidth, viewHeight));
        else svg.renderToCanvas(canvas, RenderOptions.create().viewPort(0, 0, viewWidth, viewHeight).css(layer.css));
        canvas.restore();
    }

    private WallpaperArt() {}
}
