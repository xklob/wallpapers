package io.klob.wallpapers.live;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;

import com.caverock.androidsvg.PreserveAspectRatio;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import java.io.IOException;
import java.io.InputStream;

final class WallpaperArt {
    static Bitmap renderStatic(Context context, String path, int width, int height) throws IOException {
        // ImageWallpaper zooms the committed bitmap around its center at rest.
        // Submit a padded display-space image so the frame and camera stay visible.
        Resources resources = context.getResources();
        int id = resources.getIdentifier("config_wallpaperMaxScale", "dimen", "android");
        float zoom = id == 0 ? 1.10f : resources.getFloat(id);
        return render(context, path, width, height, 1f / Math.max(1f, zoom));
    }

    static Bitmap render(Context context, String path, int width, int height) throws IOException {
        return render(context, path, width, height, 1f);
    }

    private static Bitmap render(Context context, String path, int width, int height, float scale)
            throws IOException {
        try (InputStream file = context.getAssets().open(path)) {
            SVG svg = SVG.getFromInputStream(file);
            RectF source = svg.getDocumentViewBox();
            boolean rotate = (source.width() > source.height()) != (width > height);
            int viewWidth = rotate ? height : width;
            int viewHeight = rotate ? width : height;
            svg.setDocumentWidth(viewWidth);
            svg.setDocumentHeight(viewHeight);
            svg.setDocumentPreserveAspectRatio(PreserveAspectRatio.FULLSCREEN);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLACK);
            bitmap.setDensity(Bitmap.DENSITY_NONE);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale, width / 2f, height / 2f);
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
