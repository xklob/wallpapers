package io.klob.wallpapers.live;

import android.graphics.Canvas;

/** Center rotation of the artwork only, with enough uniform scale to cover the viewport. */
final class ArtworkRotation {
    static final int MAX_DEGREES = 360;

    static int normalized(int degrees) {
        return Math.floorMod(degrees, MAX_DEGREES);
    }

    static void apply(Canvas canvas, int width, int height, int degrees) {
        int angle = normalized(degrees);
        if (angle == 0) return;
        double radians = Math.toRadians(angle);
        double cosine = Math.abs(Math.cos(radians));
        double sine = Math.abs(Math.sin(radians));
        float scale = (float)(cosine + sine * Math.max(width / (double)height, height / (double)width));
        canvas.clipRect(0, 0, width, height);
        canvas.scale(scale, scale, width / 2f, height / 2f);
        canvas.rotate(angle, width / 2f, height / 2f);
    }

    private ArtworkRotation() {}
}
