package io.klob.wallpapers.applytest;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowManager;

/** Provides an unobstructed view of the wallpaper selected in the system picker. */
public final class EmptyLauncher extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        setContentView(new android.view.View(this));
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        WindowManager.LayoutParams layout = getWindow().getAttributes();
        layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(layout);
    }
}
