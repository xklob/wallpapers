package io.klob.wallpapers.live;

import android.content.Context;
import android.content.SharedPreferences;

final class Settings {
    static final String PATTERN = "pattern";
    static final String BORDER = "border";
    static final String CAMERA = "camera";
    static final String DOCK = "dock";
    static final String STATUS = "status";
    static final String ANIMATE = "animate";
    static final String PERIOD = "period";
    static final int MIN_PERIOD = 6;
    static final int MAX_PERIOD = 60;

    static final String[] PATTERNS = {
        "Bright Honeycomb", "Spaced Hexagons", "Subtle Honeycomb", "Wireframe Cubes"
    };

    static SharedPreferences open(Context context) {
        return context.getSharedPreferences("live_wallpaper", Context.MODE_PRIVATE);
    }

    static String artPath(SharedPreferences preferences) {
        int pattern = preferences.getInt(PATTERN, 0);
        int variant = (enabled(preferences, BORDER) ? 8 : 0)
                | (enabled(preferences, STATUS) ? 4 : 0)
                | (enabled(preferences, DOCK) ? 2 : 0)
                | (enabled(preferences, CAMERA) ? 1 : 0);
        return "art/" + pattern + "-" + variant + ".webp";
    }

    static boolean enabled(SharedPreferences preferences, String key) {
        return preferences.getBoolean(key, true);
    }

    static int period(SharedPreferences preferences) {
        return preferences.getInt(PERIOD, 18);
    }

    private Settings() {}
}
