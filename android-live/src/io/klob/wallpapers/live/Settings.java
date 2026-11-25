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
    static final String PREVIEW_SCREEN = "preview_screen";
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

    static final String[] PATTERNS = {
        "Bright Honeycomb", "Spaced Hexagons", "Subtle Honeycomb", "Wireframe Cubes"
    };

    static SharedPreferences open(Context context) {
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
        if (!preferences.contains(WallpaperScreen.LOCK.key(PATTERN))) {
            preferences.edit()
                    .putInt(WallpaperScreen.LOCK.key(PATTERN), pattern(preferences, WallpaperScreen.HOME))
                    .putBoolean(WallpaperScreen.LOCK.key(BORDER), enabled(preferences, BORDER))
                    .putBoolean(WallpaperScreen.LOCK.key(CAMERA), enabled(preferences, CAMERA))
                    .putBoolean(WallpaperScreen.LOCK.key(STATUS), enabled(preferences, STATUS))
                    .apply();
        }
        return preferences;
    }

    static int pattern(SharedPreferences preferences, WallpaperScreen screen) {
        return Math.max(0, Math.min(PATTERNS.length - 1, preferences.getInt(screen.key(PATTERN), 0)));
    }

    static Catalog.Model model(Context context, SharedPreferences preferences, WallpaperScreen screen) {
        Catalog catalog = Catalog.get(context);
        Catalog.Model selected = catalog.lookup(preferences.getString(screen.key(MODEL), null));
        return selected != null ? selected : catalog.initialModel(context);
    }

    static String artPath(Context context, SharedPreferences preferences, WallpaperScreen screen) {
        int variant = screen.variant(enabled(preferences, screen.key(BORDER)),
                enabled(preferences, screen.key(STATUS)), enabled(preferences, DOCK),
                enabled(preferences, screen.key(CAMERA)));
        return model(context, preferences, screen).path(pattern(preferences, screen), variant);
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

    private Settings() {}
}
