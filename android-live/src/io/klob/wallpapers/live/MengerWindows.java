package io.klob.wallpapers.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/** An orthographic Menger module with a transparent window looking into the next scale. */
final class MengerWindows {
    private static final float RATIO = 5f;
    // Projection of the point inside the open window in generate_menger.py.
    private static final float FOCUS_X = 0.3510348f, FOCUS_Y = 0.5982943f;
    private static final float DETAIL_WIDTH = .25f;
    private static Bitmap module;
    private static Bitmap detailModule;
    private final BitmapShader texture;
    private final BitmapShader detailTexture;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF detailBounds;
    private final int size;

    MengerWindows(Context context) {
        Bitmap image = module(context);
        size = image.getWidth();
        texture = sample(image);
        float radius = size * DETAIL_WIDTH / 2;
        detailBounds = new RectF(size * FOCUS_X - radius, size * FOCUS_Y - radius,
                size * FOCUS_X + radius, size * FOCUS_Y + radius);
        Bitmap detail = detailModule(context);
        detailTexture = sample(detail);
        Matrix detailToModule = new Matrix();
        detailToModule.setScale(detailBounds.width() / detail.getWidth(), detailBounds.height() / detail.getHeight());
        detailToModule.postTranslate(detailBounds.left, detailBounds.top);
        detailTexture.setLocalMatrix(detailToModule);
    }

    private static BitmapShader sample(Bitmap image) {
        BitmapShader shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return shader;
    }

    private static synchronized Bitmap module(Context context) {
        if (module == null) {
            module = BitmapFactory.decodeResource(context.getResources(), R.drawable.menger_windows);
            module.setDensity(Bitmap.DENSITY_NONE);
        }
        return module;
    }

    private static synchronized Bitmap detailModule(Context context) {
        if (detailModule == null) {
            detailModule = BitmapFactory.decodeResource(context.getResources(), R.drawable.menger_windows_detail);
            detailModule.setDensity(Bitmap.DENSITY_NONE);
        }
        return detailModule;
    }

    static boolean uses(String assetPath) {
        return assetPath != null && assetPath.startsWith("menger-frames/");
    }

    void draw(Canvas canvas, int width, int height, double phase) {
        canvas.drawColor(0xff101015);
        float extent = Math.max(width, height);
        double span = extent * Math.pow(RATIO, phase);
        // At the wrap, each module takes the exact position of its predecessor.
        // Start below a pixel. Stop once the clear aperture contains the whole display.
        while (span > 1) span /= RATIO;
        // At 13×, the clear square (radius .04) already contains the display.
        for (; span < extent * 13; span *= RATIO) {
            float side = (float) span;
            canvas.save();
            canvas.translate(width * .49f - side * FOCUS_X, height * .50f - side * FOCUS_Y);
            canvas.scale(side / size, side / size);
            paint.setShader(texture);
            if (side > size) {
                // Both passes sample the same coordinates at different resolutions.
                canvas.save();
                canvas.clipOutRect(detailBounds);
                canvas.drawRect(0, 0, size, size, paint);
                canvas.restore();
                paint.setShader(detailTexture);
                canvas.clipRect(detailBounds);
            }
            canvas.drawRect(0, 0, size, size, paint);
            canvas.restore();
        }
    }
}
