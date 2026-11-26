package io.klob.wallpapers.live;

import android.graphics.Canvas;

/** Centered framing of the artwork, independent of the stationary device outlines. */
final class ArtworkTransform {
    static final int FULL_TURN = 360;
    static final int FIT = 100;
    static final int MIN_ZOOM = 25;
    static final int MAX_ZOOM = 400;
    static final int ZOOM_STEP = 5;

    static int angle(int degrees) {
        return Math.floorMod(degrees, FULL_TURN);
    }

    static boolean isActive(int degrees, int zoom) {
        return angle(degrees) != 0 || zoom != FIT;
    }

    static float apply(Canvas canvas, int width, int height, int degrees, int zoom) {
        int angle = angle(degrees);
        if (angle == 0 && zoom == FIT) return 1f;
        double radians = Math.toRadians(angle);
        double cosine = Math.abs(Math.cos(radians));
        double sine = Math.abs(Math.sin(radians));
        double cover = cosine + sine * Math.max(width / (double)height, height / (double)width);
        float scale = (float)(Math.max(1, cover) * zoom / FIT);
        canvas.clipRect(0, 0, width, height);
        canvas.scale(scale, scale, width / 2f, height / 2f);
        if (angle != 0) canvas.rotate(angle, width / 2f, height / 2f);
        return scale;
    }

    private ArtworkTransform() {}
}
