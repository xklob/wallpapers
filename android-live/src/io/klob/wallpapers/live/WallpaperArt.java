package io.klob.wallpapers.live;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Build;

import com.caverock.androidsvg.PreserveAspectRatio;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class WallpaperArt {
    static String assetPath(String path) {
        return MandelbrotZoom.uses(path) ? path.substring(MandelbrotZoom.PREFIX.length()) : path;
    }

    static boolean hasScene(String path) {
        return MengerWindows.uses(path) || MandelbrotZoom.uses(path);
    }

    static Bitmap renderStatic(Context context, String path, int width, int height) throws IOException {
        return renderStatic(context, path, width, height, null, null);
    }

    static Bitmap renderStatic(Context context, String path, int width, int height, Catalog.Model model,
                               PanelCorners corners) throws IOException {
        // ImageWallpaper zooms the committed bitmap around its center at rest.
        // Submit a padded display-space image so the frame and camera stay visible.
        Resources resources = context.getResources();
        int id = resources.getIdentifier("config_wallpaperMaxScale", "dimen", "android");
        float zoom = id == 0 ? 1.10f : resources.getFloat(id);
        return render(context, path, width, height, 1f / Math.max(1f, zoom), model, corners);
    }

    static Bitmap render(Context context, String path, int width, int height) throws IOException {
        return render(context, path, width, height, 1f, null, null);
    }

    static Bitmap render(Context context, String path, int width, int height, float scale,
                         Catalog.Model model, PanelCorners corners)
            throws IOException {
        return render(context, path, width, height, scale, model, corners, true);
    }

    static Bitmap render(Context context, String path, int width, int height, float scale,
                         Catalog.Model model, PanelCorners corners, boolean includeScene)
            throws IOException {
        try (InputStream file = context.getAssets().open(assetPath(path))) {
            SVG svg;
            if (corners != null && corners.matches(model, Build.MODEL, Build.MANUFACTURER, width, height) && path.contains("/rgb_border/")) {
                InputStreamReader reader = new InputStreamReader(file, StandardCharsets.UTF_8);
                StringBuilder source = new StringBuilder();
                char[] buffer = new char[8192];
                int n;
                while ((n = reader.read(buffer)) != -1) source.append(buffer, 0, n);
                svg = SVG.getFromString(corners.fit(source.toString(), model.width > model.height));
            } else {
                svg = SVG.getFromInputStream(file);
            }
            RectF source = svg.getDocumentViewBox();
            boolean rotate = (source.width() > source.height()) != (width > height);
            int viewWidth = rotate ? height : width;
            int viewHeight = rotate ? width : height;
            svg.setDocumentWidth(viewWidth);
            svg.setDocumentHeight(viewHeight);
            svg.setDocumentPreserveAspectRatio(PreserveAspectRatio.FULLSCREEN);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (includeScene || !hasScene(path)) bitmap.eraseColor(Color.BLACK);
            bitmap.setDensity(Bitmap.DENSITY_NONE);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale, width / 2f, height / 2f);
            if (includeScene && hasScene(path)) {
                canvas.clipRect(0, 0, width, height);
                if (MandelbrotZoom.uses(path)) {
                    MandelbrotZoom.drawStill(context, canvas, width, height);
                } else {
                    new MengerWindows(context).draw(canvas, width, height, 0);
                }
            }
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
            svg.renderToCanvas(canvas, new RectF(0, 0, viewWidth, viewHeight));
            return bitmap;
        } catch (SVGParseException e) {
            throw new IOException("Invalid wallpaper SVG: " + path, e);
        }
    }

    private WallpaperArt() {}
}
