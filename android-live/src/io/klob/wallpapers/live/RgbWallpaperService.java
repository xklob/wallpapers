package io.klob.wallpapers.live;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public final class RgbWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() {
        return new RgbEngine();
    }

    private final class RgbEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final RgbRenderer renderer = new RgbRenderer(RgbWallpaperService.this);
        private final SharedPreferences preferences = Settings.open(RgbWallpaperService.this);
        private final Runnable frame = this::draw;
        private boolean visible;
        private boolean hasSurface;
        private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
            renderer.update(prefs);
            if (visible) draw();
        };

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setOffsetNotificationsEnabled(false);
            renderer.update(preferences);
            preferences.registerOnSharedPreferenceChangeListener(changes);
        }

        @Override public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            renderer.setVisible(visible && hasSurface);
            if (visible) draw();
            else handler.removeCallbacks(frame);
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            hasSurface = true;
            renderer.setVisible(visible);
            if (visible) draw();
        }

        @Override public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            draw();
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            hasSurface = false;
            renderer.setVisible(false);
            handler.removeCallbacks(frame);
            super.onSurfaceDestroyed(holder);
        }

        @Override public void onDestroy() {
            handler.removeCallbacks(frame);
            renderer.setVisible(false);
            preferences.unregisterOnSharedPreferenceChangeListener(changes);
            super.onDestroy();
        }

        private void draw() {
            handler.removeCallbacks(frame);
            if (!hasSurface) return;
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                if (holder.getSurface().isValid()) canvas = holder.lockHardwareCanvas();
                if (canvas != null) renderer.draw(canvas);
            } catch (IllegalStateException | IllegalArgumentException exception) {
                if (holder.getSurface().isValid()) {
                    android.util.Log.w("RgbGeometryLive", "Wallpaper surface draw failed", exception);
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas);
            }
            if (renderer.isAnimating()) handler.postDelayed(frame, RgbRenderer.FRAME_INTERVAL_MS);
        }
    }
}
