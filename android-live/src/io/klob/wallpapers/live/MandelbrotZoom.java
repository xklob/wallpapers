package io.klob.wallpapers.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.util.Log;

final class MandelbrotZoom {
    static final String PREFIX = "mandelbrot/";
    // The complex scaling factor calculated in generate_mandelbrot.py.
    private static final String PROJECTION =
            "uniform shader atlas;\n"
            + "uniform float2 atlasSize;\n"
            + "uniform float4 camera;\n"
            + "half4 main(float2 p) {\n"
            + "    float2 z = float2(p.x - camera.x, camera.y - p.y);\n"
            + "    float r = length(z);\n"
            + "    half4 center = half4(4.0/255.0, 5.0/255.0, 10.0/255.0, 1.0);\n"
            + "    if (r <= 0.75) return center;\n"
            + "    float depth = log(r / camera.z) / 0.851767802885;\n"
            + "    float angle = (atan(z.y, z.x) + 0.023399272963 * depth) / 6.283185307180;\n"
            + "    float2 uv = fract(float2(angle, depth - camera.w));\n"
            + "    return mix(center, atlas.eval(uv * atlasSize), half(smoothstep(0.75, 2.25, r)));\n"
            + "}";

    private static Bitmap atlas;
    private static Bitmap still;
    private final RuntimeShader projection;
    private final Paint paint = new Paint();

    MandelbrotZoom(Context context) {
        projection = createProjection(context);
    }

    private static RuntimeShader createProjection(Context context) {
        try {
            RuntimeShader shader = new RuntimeShader(PROJECTION);
            Bitmap bitmap = atlas(context);
            BitmapShader texture = new BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            texture.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shader.setInputShader("atlas", texture);
            shader.setFloatUniform("atlasSize", bitmap.getWidth(), bitmap.getHeight());
            return shader;
        } catch (IllegalArgumentException exception) {
            Log.e("RgbGeometryLive", "Could not initialize the Mandelbrot projection", exception);
            return null;
        }
    }

    private static synchronized Bitmap atlas(Context context) {
        if (atlas == null) atlas = BitmapFactory.decodeResource(context.getResources(), R.drawable.mandelbrot_atlas);
        return atlas;
    }

    private static synchronized Bitmap still(Context context) {
        if (still == null) still = BitmapFactory.decodeResource(context.getResources(), R.drawable.mandelbrot_still);
        return still;
    }

    static boolean uses(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    boolean isAvailable() {
        return projection != null;
    }

    void draw(Context context, Canvas canvas, int width, int height, double phase, RuntimeShader colors) {
        if (projection == null || !canvas.isHardwareAccelerated()) {
            drawStill(context, canvas, width, height);
            return;
        }
        projection.setFloatUniform("camera", width / 2f, height / 2f, Math.max(width, height), (float) phase);
        if (colors != null) colors.setInputShader("art", projection);
        paint.setShader(colors == null ? projection : colors);
        canvas.drawRect(0, 0, width, height, paint);
    }

    static void drawStill(Context context, Canvas canvas, int width, int height) {
        float size = Math.max(width, height);
        float left = (width - size) / 2;
        float top = (height - size) / 2;
        canvas.drawBitmap(still(context), null, new RectF(left, top, left + size, top + size),
                new Paint(Paint.FILTER_BITMAP_FLAG));
    }
}
