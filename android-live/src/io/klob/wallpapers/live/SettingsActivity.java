package io.klob.wallpapers.live;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private SharedPreferences preferences;
    private Preview preview;
    private SeekBar speed;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes =
            (prefs, key) -> preview.refresh(prefs);

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = Settings.open(this);
        preview = new Preview(this, preferences);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = addText(content, "RGB Geometry Live", 30);
        title.setAccessibilityHeading(true);
        addText(content, "Calibrated for the Pixel 11 Pro XL", 14);

        Button apply = new Button(this);
        apply.setText("Apply live wallpaper");
        apply.setAllCaps(false);
        apply.setOnClickListener(view -> openWallpaperPicker());
        content.addView(apply, fullWidth(dp(52), 8));

        LinearLayout.LayoutParams previewLayout = new LinearLayout.LayoutParams(dp(126), dp(280));
        previewLayout.gravity = Gravity.CENTER_HORIZONTAL;
        previewLayout.topMargin = dp(12);
        previewLayout.bottomMargin = dp(16);
        preview.setContentDescription("Live wallpaper preview");
        content.addView(preview, previewLayout);

        TextView patternLabel = addText(content, "Pattern", 16);
        Spinner patterns = new Spinner(this, Spinner.MODE_DROPDOWN);
        patterns.setId(View.generateViewId());
        patternLabel.setLabelFor(patterns.getId());
        ArrayAdapter<String> names = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, Settings.PATTERNS);
        names.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        patterns.setAdapter(names);
        patterns.setSelection(preferences.getInt(Settings.PATTERN, 0));
        patterns.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                preferences.edit().putInt(Settings.PATTERN, pos).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        content.addView(patterns, fullWidth(dp(48), 0));

        addToggle(content, "RGB border", Settings.BORDER);
        addToggle(content, "Camera ring", Settings.CAMERA);
        addToggle(content, "Home row and search outline", Settings.DOCK);
        addToggle(content, "Status bar outline", Settings.STATUS);
        addToggle(content, "Animate colors", Settings.ANIMATE);
        addSpeedControl(content);
    }

    @Override public void onStart() {
        super.onStart();
        preferences.registerOnSharedPreferenceChangeListener(changes);
        preview.refresh(preferences);
    }

    @Override public void onStop() {
        preferences.unregisterOnSharedPreferenceChangeListener(changes);
        super.onStop();
    }

    private void openWallpaperPicker() {
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, RgbWallpaperService.class));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "Open Wallpaper & style and select RGB Geometry Live.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams fullWidth(int height, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = dp(topMargin);
        return params;
    }

    private TextView addText(LinearLayout content, String text, int size) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        content.addView(label);
        return label;
    }

    private void addToggle(LinearLayout content, String title, String key) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(16);
        toggle.setChecked(Settings.enabled(preferences, key));
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            preferences.edit().putBoolean(key, enabled).apply();
            if (Settings.ANIMATE.equals(key) && speed != null) speed.setEnabled(enabled);
        });
        content.addView(toggle, fullWidth(dp(48), 0));
    }

    private void addSpeedControl(LinearLayout content) {
        int period = Settings.period(preferences);
        TextView label = addText(content, speedLabel(period), 16);
        speed = new SeekBar(this);
        speed.setId(View.generateViewId());
        label.setLabelFor(speed.getId());
        speed.setMax(Settings.MAX_PERIOD - Settings.MIN_PERIOD);
        speed.setProgress(period - Settings.MIN_PERIOD);
        speed.setEnabled(Settings.enabled(preferences, Settings.ANIMATE));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                if (!fromUser) return;
                int seconds = progress + Settings.MIN_PERIOD;
                label.setText(speedLabel(seconds));
                preferences.edit().putInt(Settings.PERIOD, seconds).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar slider) {}
            @Override public void onStopTrackingTouch(SeekBar slider) {}
        });
        content.addView(speed, fullWidth(dp(48), 0));
    }

    private static String speedLabel(int seconds) {
        return "Cycle duration · " + seconds + " s";
    }

    private static final class Preview extends View {
        private final RgbRenderer renderer;

        Preview(Context context, SharedPreferences preferences) {
            super(context);
            renderer = new RgbRenderer(context);
            renderer.update(preferences);
        }

        void refresh(SharedPreferences preferences) {
            renderer.update(preferences);
            invalidate();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (renderer != null) {
                renderer.setVisible(visibility == VISIBLE);
                if (visibility == VISIBLE) invalidate();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            renderer.draw(canvas);
            if (renderer.isAnimating()) postInvalidateDelayed(RgbRenderer.FRAME_INTERVAL_MS);
        }
    }
}
