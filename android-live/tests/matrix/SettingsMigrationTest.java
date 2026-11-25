package io.klob.wallpapers.live;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;

final class SettingsMigrationTest {
    private static void expect(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void run(Context target) {
        String name = "migration_test";
        SharedPreferences isolated = target.getSharedPreferences(name, Context.MODE_PRIVATE);
        Context context = new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String unused, int mode) {
                return isolated;
            }
        };
        try {
            isolated.edit().clear().commit();
            Settings.open(context);
            Catalog catalog = Catalog.get(context);
            Catalog.Model initial = catalog.initialModel(context);
            expect(initial.id.equals(isolated.getString(Settings.MODEL, "")), "Fresh device model");
            expect(initial.id.equals(isolated.getString("lock_model", "")), "Fresh lock model");
            expect(isolated.getBoolean(Settings.SUGGESTED, false) == !initial.matches(Build.MODEL),
                    "Mark size suggestions");
            if (!initial.matches(Build.MODEL)) {
                expect(!Settings.enabled(isolated, Settings.CAMERA) && !Settings.enabled(isolated, Settings.DOCK)
                        && !Settings.enabled(isolated, Settings.STATUS), "Unknown device overlays default off");
            }

            isolated.edit().clear().putInt("pattern", 3).putBoolean("border", false)
                    .putBoolean("camera", true).putBoolean("dock", true).putBoolean("status", false)
                    .putBoolean("animate", false).commit();
            Settings.open(context);
            expect("pixel_11_pro_xl".equals(isolated.getString("model", "")), "Legacy Home calibration");
            expect("pixel_11_pro_xl".equals(isolated.getString("lock_model", "")), "Legacy Lock calibration");
            expect(Settings.pattern(isolated, WallpaperScreen.LOCK) == 3, "Legacy pattern migrated to Lock");
            expect(!Settings.enabled(isolated, "animate") && !Settings.enabled(isolated, "lock_border"),
                    "Preserve previous animation and frame choices");
            expect(Settings.enabled(isolated, "lock_camera") && !Settings.enabled(isolated, "lock_status"),
                    "Preserve previous overlays");
            expect(Settings.artPath(context, isolated, WallpaperScreen.HOME).contains("home_screen"),
                    "Home row retained");
            expect(!Settings.artPath(context, isolated, WallpaperScreen.LOCK).contains("home_screen"),
                    "Lock excludes Home row");
            isolated.edit().putString("model", "pixel_9").putInt("lock_pattern", 1).commit();
            Settings.open(context);
            expect("pixel_9".equals(isolated.getString("model", "")) && isolated.getInt("lock_pattern", 0) == 1,
                    "Reopen preserves independent choices");

            expect(catalog.lookup("pixel_11_pro_xl").estimatedCamera
                    && !catalog.lookup("pixel_9_pro_xl").estimatedCamera, "Camera accuracy follows the preset");
            expect("pixel_9_pro_xl".equals(catalog.initialModel("Pixel 9 Pro XL", "Google", 1008, 2244).id),
                    "Android display scaling keeps the exact model");
            expect("pixel_9_pro_fold_inner_landscape".equals(catalog.initialModel(
                    "Pixel 9 Pro Fold", "Google", 2152, 2076).id), "Foldable active screen");
            Catalog.Model samsung = catalog.initialModel("SM-S938U", "samsung", 1440, 3120);
            expect("Samsung".equals(samsung.manufacturer) && !samsung.matches("SM-S938U"),
                    "Opaque hardware code offers a same-brand size suggestion");
        } finally {
            target.deleteSharedPreferences(name);
        }
    }
}
