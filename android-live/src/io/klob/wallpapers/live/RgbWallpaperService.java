package io.klob.wallpapers.live;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.view.WindowManager;

public final class RgbWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() {
        return new RgbEngine();
    }

    private final class RgbEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final RgbRenderer renderer = new RgbRenderer(RgbWallpaperService.this);
        private final SharedPreferences preferences = Settings.open(RgbWallpaperService.this);
        private final KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        private final Choreographer clock = Choreographer.getInstance();
        private final FramePacer pace = new FramePacer();
        private final Choreographer.FrameCallback frame = this::onFrame;
        private final Runnable screenCheck = this::checkSharedScreen;
        private WallpaperScreen screen;
        private boolean visible;
        private boolean hasSurface;
        private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
            screen = resolveScreen();
            renderer.update(prefs, screen);
            if (visible) draw();
        };

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setOffsetNotificationsEnabled(false);
            screen = resolveScreen();
            renderer.update(preferences, screen);
            preferences.registerOnSharedPreferenceChangeListener(changes);
        }

        @Override public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            refreshScreen();
            renderer.setVisible(visible && hasSurface);
            scheduleScreenCheck();
            if (visible) draw();
            else stopFrames();
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            hasSurface = true;
            refreshPanel();
            refreshScreen();
            renderer.setVisible(visible);
            scheduleScreenCheck();
            if (visible) draw();
        }

        private void refreshPanel() {
            android.content.Context display = getDisplayContext();
            renderer.setPanel(display.getDisplay(), display.getSystemService(WindowManager.class).getMaximumWindowMetrics());
        }

        @Override public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            refreshPanel();
            if (visible) draw();
        }

        @Override public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            refreshScreen();
            draw();
        }

        @Override public void onWallpaperFlagsChanged(int which) {
            if (refreshScreen() && visible) draw();
            scheduleScreenCheck();
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            hasSurface = false;
            renderer.setVisible(false);
            stopFrames();
            handler.removeCallbacks(screenCheck);
            super.onSurfaceDestroyed(holder);
        }

        @Override public void onDestroy() {
            stopFrames();
            handler.removeCallbacks(screenCheck);
            renderer.setVisible(false);
            preferences.unregisterOnSharedPreferenceChangeListener(changes);
            super.onDestroy();
        }

        private int destinationFlags() {
            return Build.VERSION.SDK_INT >= 34 ? getWallpaperFlags() : 0;
        }

        private WallpaperScreen resolveScreen() {
            int flags = destinationFlags();
            boolean home = (flags & WallpaperManager.FLAG_SYSTEM) != 0;
            boolean lock = (flags & WallpaperManager.FLAG_LOCK) != 0;
            String requested = isPreview() ? preferences.getString(Settings.PICKER_SCREEN, null) : null;
            WallpaperScreen preview = requested == null ? null : WallpaperScreen.fromPreference(requested);
            boolean locked = preview == null && home == lock && keyguard != null && keyguard.isKeyguardLocked();
            return WallpaperScreen.resolve(home, lock, locked, preview);
        }

        private boolean refreshScreen() {
            WallpaperScreen next = resolveScreen();
            if (screen == next) return false;
            screen = next;
            renderer.update(preferences, screen);
            return true;
        }

        private void checkSharedScreen() {
            if (refreshScreen() && visible) draw();
            scheduleScreenCheck();
        }

        private void scheduleScreenCheck() {
            handler.removeCallbacks(screenCheck);
            int flags = destinationFlags() & (WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            boolean shared = flags == 0 || flags == (WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            if (visible && hasSurface && shared && !isPreview()) handler.postDelayed(screenCheck, 150);
        }

        private void stopFrames() {
            clock.removeFrameCallback(frame);
            pace.reset();
        }

        private void onFrame(long vsyncNanos) {
            if (!hasSurface || !renderer.isAnimating()) return;
            if (pace.ready(vsyncNanos, renderer.frameRate())) drawSurface();
            if (renderer.isAnimating()) clock.postFrameCallback(frame);
        }

        private void draw() {
            stopFrames();
            drawSurface();
            if (hasSurface && renderer.isAnimating()) {
                pace.ready(System.nanoTime(), renderer.frameRate());
                clock.postFrameCallback(frame);
            }
        }

        private void drawSurface() {
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
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (IllegalStateException | IllegalArgumentException exception) {
                        if (holder.getSurface().isValid()) {
                            android.util.Log.w("RgbGeometryLive", "Wallpaper surface changed during draw", exception);
                        }
                    }
                }
            }
        }
    }
}
