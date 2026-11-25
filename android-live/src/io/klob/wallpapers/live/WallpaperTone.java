package io.klob.wallpapers.live;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

/** A display-referred color adjustment applied after animation and composition. */
final class WallpaperTone {
    static final int DEFAULT = 100;
    static final int MAX = 200;
    static final WallpaperTone NEUTRAL = new WallpaperTone(DEFAULT, DEFAULT, DEFAULT);

    private final int brightness, contrast, saturation;
    private final Paint layerPaint;

    WallpaperTone(int brightness, int contrast, int saturation) {
        this.brightness = brightness;
        this.contrast = contrast;
        this.saturation = saturation;
        if (matches(DEFAULT, DEFAULT, DEFAULT)) {
            layerPaint = null;
            return;
        }
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(saturation / 100f);
        float light = brightness / 100f;
        float spread = contrast / 100f;
        float gain = light * spread;
        float offset = light * (1f - spread) * 127.5f;
        matrix.postConcat(new ColorMatrix(new float[]{
                gain, 0, 0, 0, offset,
                0, gain, 0, 0, offset,
                0, 0, gain, 0, offset,
                0, 0, 0, 1, 0
        }));
        layerPaint = new Paint();
        layerPaint.setColorFilter(new ColorMatrixColorFilter(matrix));
    }

    boolean matches(int brightness, int contrast, int saturation) {
        return this.brightness == brightness && this.contrast == contrast && this.saturation == saturation;
    }

    ColorFilter filter() {
        return layerPaint == null ? null : layerPaint.getColorFilter();
    }

    int saveLayer(Canvas canvas, int width, int height) {
        return layerPaint == null ? 0 : canvas.saveLayer(0, 0, width, height, layerPaint);
    }
}
