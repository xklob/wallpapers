package io.klob.wallpapers.live;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.io.IOException;

final class RegularWallpaper {
    static int apply(Context context, String path, int width, int height, WallpaperScreen screen)
            throws IOException {
        WallpaperManager manager = WallpaperManager.getInstance(context);
        if (!manager.isWallpaperSupported() || !manager.isSetWallpaperAllowed()) {
            throw new IOException("Android has disabled wallpaper changes on this device.");
        }
        Bitmap image = WallpaperArt.renderStatic(context, path, width, height);
        try {
            int flag = screen == WallpaperScreen.LOCK ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM;
            return manager.setBitmap(image, new Rect(0, 0, width, height), false, flag);
        } finally {
            image.recycle();
        }
    }

    private RegularWallpaper() {}
}
