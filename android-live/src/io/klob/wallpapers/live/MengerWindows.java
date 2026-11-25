package io.klob.wallpapers.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;

/** An orthographic Menger module with a transparent window looking into the next scale. */
final class MengerWindows {
    private static final float RATIO = 5f;
    private static final double LEVEL_MILLIS = 16000;
    // Projection of the point inside the open window in generate_menger.py.
    private static final float FOCUS_X = 0.3510348f, FOCUS_Y = 0.5982943f;
    private static Bitmap module;
    final BitmapShader texture;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    final int size;

    MengerWindows(Context context) {
        Bitmap image = module(context);
        size = image.getWidth();
        texture = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        texture.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
    }

    private static synchronized Bitmap module(Context context) {
        if (module == null) {
            module = BitmapFactory.decodeResource(context.getResources(), R.drawable.menger_windows);
            module.setDensity(Bitmap.DENSITY_NONE);
        }
        return module;
    }

    static boolean uses(String assetPath) {
        return assetPath != null && assetPath.startsWith("menger-frames/");
    }

    static double advance(double phase, long elapsedMillis, float speed) {
        return (phase + elapsedMillis * (double) speed / LEVEL_MILLIS) % 1.0;
    }

    void draw(Canvas canvas, int width, int height, double phase, Shader colorEffect) {
        canvas.drawColor(0xff101015);
        paint.setShader(colorEffect == null ? texture : colorEffect);
        float extent = Math.max(width, height);
        double span = extent * Math.pow(RATIO, phase);
        // At the wrap, each module takes the exact position of its predecessor.
        // Start below a pixel. Stop once the clear aperture contains the whole display.
        while (span > 1) span /= RATIO;
        // The texture has a clear square of radius .05 around its focus.
        for (; span < extent * 11; span *= RATIO) {
            float side = (float) span;
            canvas.save();
            canvas.translate(width * .49f - side * FOCUS_X, height * .50f - side * FOCUS_Y);
            canvas.scale(side / size, side / size);
            canvas.drawRect(0, 0, size, size, paint);
            canvas.restore();
        }
    }
}
