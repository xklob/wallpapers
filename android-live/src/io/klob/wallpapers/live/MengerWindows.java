package io.klob.wallpapers.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.util.Log;

import java.lang.ref.WeakReference;
import android.graphics.Shader;

/** An orthographic Menger module with a transparent window looking into the next scale. */
final class MengerWindows {
    private static final float RATIO = 5f;
    // Projection of the point inside the open window in generate_menger.py.
    private static final float FOCUS_X = 0.34971622f, FOCUS_Y = 0.59916291f;
    private static final float DETAIL_WIDTH = .25f;
    private static final String DETAIL_BLEND =
            "uniform shader base, detail;\n"
            + "uniform float4 bounds;\n"
            + "uniform float mixAmount;\n"
            + "half4 main(float2 p) {\n"
            + "    half4 lower = base.eval(p);\n"
            + "    float2 border = min(p - bounds.xy, bounds.zw - p);\n"
            + "    float distance = min(border.x, border.y);\n"
            + "    if (distance <= 0) return lower;\n"
            + "    half weight = half(smoothstep(0.0, 18.0, distance) * mixAmount);\n"
            + "    return mix(lower, detail.eval(p), weight);\n"
            + "}";
    private static WeakReference<Bitmap> moduleCache = new WeakReference<>(null);
    private static WeakReference<Bitmap> detailCache = new WeakReference<>(null);
    private final BitmapShader texture;
    private final BitmapShader detailTexture;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF detailBounds;
    private final int size;
    private RuntimeShader blend;
    private boolean attemptedBlend;

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
        Bitmap bitmap = moduleCache.get();
        if (bitmap == null) {
            bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.menger_windows);
            bitmap.setDensity(Bitmap.DENSITY_NONE);
            bitmap.setHasMipMap(true);
            moduleCache = new WeakReference<>(bitmap);
        }
        return bitmap;
    }

    private static synchronized Bitmap detailModule(Context context) {
        Bitmap bitmap = detailCache.get();
        if (bitmap == null) {
            bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.menger_windows_detail);
            bitmap.setDensity(Bitmap.DENSITY_NONE);
            bitmap.setHasMipMap(true);
            detailCache = new WeakReference<>(bitmap);
        }
        return bitmap;
    }

    private RuntimeShader detailBlend() {
        if (!attemptedBlend) {
            attemptedBlend = true;
            try {
                RuntimeShader candidate = new RuntimeShader(DETAIL_BLEND);
                candidate.setInputShader("base", texture);
                candidate.setInputShader("detail", detailTexture);
                candidate.setFloatUniform("bounds", detailBounds.left, detailBounds.top,
                        detailBounds.right, detailBounds.bottom);
                blend = candidate;
            } catch (IllegalArgumentException exception) {
                Log.w("RgbGeometryLive", "Menger detail blend unavailable", exception);
            }
        }
        return blend;
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
            RuntimeShader transition = side > size * .8f && canvas.isHardwareAccelerated() ? detailBlend() : null;
            if (transition != null) {
                // Use the same projection for each source. Fade in magnification detail
                // and feather its boundary without stacking two translucent surfaces.
                float t = Math.min(1, (side / size - .8f) / .5f);
                transition.setFloatUniform("mixAmount", t * t * (3 - 2 * t));
                paint.setShader(transition);
            } else if (side > size) {
                // Also keep high detail available when baking the still on a software canvas.
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
