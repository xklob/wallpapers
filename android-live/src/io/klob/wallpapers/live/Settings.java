package io.klob.wallpapers.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

final class Settings {
    static final String MODEL = "model";
    static final String SUGGESTED = "suggested_model";
    static final String PATTERN = "pattern";
    static final String BORDER = "border";
    static final String CAMERA = "camera";
    static final String DOCK = "dock";
    static final String STATUS = "status";
    static final String FRAMING_ZOOM = "framing_zoom";
    static final String ROTATION = "rotation_degrees";
    static final String BRIGHTNESS = "brightness";
    static final String CONTRAST = "contrast";
    static final String SATURATION = "saturation";
    static final String ANIMATE = "animate";
    static final String AUDIO_SYNC = "audio_sync";
    static final String AUDIO_SOURCE = "audio_source";
    static final String AUDIO_EFFECT = "audio_effect";
    static final String AUDIO_INTENSITY = "audio_intensity";
    static final String AUDIO_SMOOTHNESS = "audio_smoothness";
    static final String BASS_SENSITIVITY = "bass_sensitivity";
    static final int DEFAULT_INTENSITY = 65;
    static final int DEFAULT_SMOOTHNESS = 35;
    static final int DEFAULT_SENSITIVITY = 50;
    static final String PERIOD = "period";
    static final int MIN_PERIOD = 6;
    static final int MAX_PERIOD = 60;
    static final String ZOOM_SPEED = "zoom_speed";
    static final int DEFAULT_ZOOM = 4; // quarter-speed increments; 4 is 1×
    static final int MAX_ZOOM = 16;
    static final String TEXTURE_QUALITY = "mandelbrot_texture_quality";
    static final String FRAME_RATE = "zoom_frame_rate";
    static final String THEME = "app_theme";
    static final int[] FRAME_RATES = {15, 30, 60};

    static int quality(SharedPreferences prefs) {
        return Math.max(0, Math.min(2, prefs.getInt(TEXTURE_QUALITY, 1)));
    }

    static int frameRateIndex(SharedPreferences prefs) {
        return Math.max(0, Math.min(2, prefs.getInt(FRAME_RATE, 1)));
    }

    static final int MANDELBROT = 3;

    static final String[] PATTERNS = {
        "Bright Honeycomb", "Spaced Hexagons", "Wireframe Cubes", "Mandelbrot Zoom"
    };

    static synchronized SharedPreferences open(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("live_wallpaper", Context.MODE_PRIVATE);
        if (!preferences.contains(MODEL)) {
            Catalog catalog = Catalog.get(context);
            boolean upgrading = !preferences.getAll().isEmpty();
            Catalog.Model initial = upgrading ? catalog.lookup("pixel_11_pro_xl") : null;
            if (initial == null) initial = catalog.initialModel(context);
            boolean suggested = !upgrading && !initial.matches(Build.MODEL);
            SharedPreferences.Editor defaults = preferences.edit();
            for (WallpaperScreen screen : WallpaperScreen.values()) {
                defaults.putString(screen.key(MODEL), initial.id);
                if (suggested) {
                    defaults.putBoolean(screen.key(SUGGESTED), true)
                            .putBoolean(screen.key(CAMERA), false)
                            .putBoolean(screen.key(STATUS), false);
                    if (screen == WallpaperScreen.HOME) defaults.putBoolean(DOCK, false);
                }
            }
            defaults.apply();
        }
        if (!preferences.getBoolean("three_pattern_catalog", false)) {
            SharedPreferences.Editor migration = preferences.edit();
            int home = preferences.getInt(PATTERN, 0);
            String lockPattern = WallpaperScreen.LOCK.key(PATTERN);
            int lock = preferences.getInt(lockPattern, home);
            if (!preferences.contains(lockPattern)) {
                for (String key : new String[]{BORDER, CAMERA, STATUS}) {
                    migration.putBoolean(WallpaperScreen.LOCK.key(key), enabled(preferences, key));
                }
            }
            migration.putInt(PATTERN, migratePattern(home))
                    .putInt(lockPattern, migratePattern(lock))
                    .putBoolean("three_pattern_catalog", true).apply();
        }
        if (!preferences.getBoolean("four_pattern_catalog", false)) {
            SharedPreferences.Editor migration = preferences.edit();
            if (!preferences.contains(ZOOM_SPEED) && preferences.contains("menger_zoom_speed")) {
                migration.putInt(ZOOM_SPEED, preferences.getInt("menger_zoom_speed", DEFAULT_ZOOM));
            }
            for (WallpaperScreen screen : WallpaperScreen.values()) {
                String key = screen.key(PATTERN);
                int previous = preferences.getInt(key, 0);
                // Former Menger (3) and Mandelbrot (4) both select the remaining zoom.
                if (previous == 3 || previous == 4) migration.putInt(key, MANDELBROT);
            }
            migration.remove("menger_zoom_speed").putBoolean("four_pattern_catalog", true).apply();
        }
        return preferences;
    }

    private static int migratePattern(int previous) {
        // The four-pattern catalog stored Spaced Hexagons at 1 and Wireframe Cubes at 3.
        if (previous == 1) return 1;
        if (previous == 3) return 2;
        return 0;
    }

    static int pattern(SharedPreferences preferences, WallpaperScreen screen) {
        return Math.max(0, Math.min(PATTERNS.length - 1, preferences.getInt(screen.key(PATTERN), 0)));
    }

    static int rotation(SharedPreferences preferences, WallpaperScreen screen) {
        return Math.max(0, Math.min(ArtworkTransform.FULL_TURN, preferences.getInt(screen.key(ROTATION), 0)));
    }

    static int framingZoom(SharedPreferences prefs, WallpaperScreen screen) {
        int zoom = prefs.getInt(screen.key(FRAMING_ZOOM), ArtworkTransform.FIT);
        return Math.max(ArtworkTransform.MIN_ZOOM, Math.min(ArtworkTransform.MAX_ZOOM, zoom));
    }

    static int adjustment(SharedPreferences prefs, WallpaperScreen screen, String key) {
        return Math.max(0, Math.min(WallpaperTone.MAX,
                prefs.getInt(screen.key(key), WallpaperTone.DEFAULT)));
    }

    static WallpaperTone tone(SharedPreferences prefs, WallpaperScreen screen, WallpaperTone current) {
        int brightness = adjustment(prefs, screen, BRIGHTNESS);
        int contrast = adjustment(prefs, screen, CONTRAST);
        int saturation = adjustment(prefs, screen, SATURATION);
        if (current.matches(brightness, contrast, saturation)) return current;
        if (WallpaperTone.NEUTRAL.matches(brightness, contrast, saturation)) return WallpaperTone.NEUTRAL;
        return new WallpaperTone(brightness, contrast, saturation);
    }

    static boolean hasZoom(int pattern) {
        return pattern == MANDELBROT;
    }

    static Catalog.Model model(Context context, SharedPreferences preferences, WallpaperScreen screen) {
        Catalog catalog = Catalog.get(context);
        Catalog.Model selected = catalog.lookup(preferences.getString(screen.key(MODEL), null));
        return selected != null ? selected : catalog.initialModel(context);
    }

    static String artPath(Context context, SharedPreferences preferences, WallpaperScreen screen) {
        return artPath(model(context, preferences, screen), preferences, screen);
    }

    static String artPath(Catalog.Model model, SharedPreferences preferences, WallpaperScreen screen) {
        int variant = screen.variant(enabled(preferences, screen.key(BORDER)),
                enabled(preferences, screen.key(STATUS)), enabled(preferences, DOCK),
                enabled(preferences, screen.key(CAMERA)));
        return model.path(pattern(preferences, screen), variant);
    }

    static boolean enabled(SharedPreferences preferences, String key) {
        return preferences.getBoolean(key, true);
    }

    static boolean audioSync(SharedPreferences preferences) {
        return preferences.getBoolean(AUDIO_SYNC, false);
    }

    static AudioSource audioSource(SharedPreferences preferences) {
        return AudioSource.fromPreference(preferences.getString(AUDIO_SOURCE, null),
                preferences.getBoolean("audio_microphone", false));
    }

    static AudioEffect audioEffect(SharedPreferences preferences) {
        return AudioEffect.fromPreference(preferences.getString(AUDIO_EFFECT, AudioEffect.DEFAULT.name()));
    }

    static int percentage(SharedPreferences preferences, String key, int fallback) {
        return Math.max(0, Math.min(100, preferences.getInt(key, fallback)));
    }

    static int period(SharedPreferences preferences) {
        return preferences.getInt(PERIOD, 18);
    }

    static int zoomSpeed(SharedPreferences preferences) {
        return Math.max(1, Math.min(MAX_ZOOM, preferences.getInt(ZOOM_SPEED, DEFAULT_ZOOM)));
    }

    private Settings() {}
}
