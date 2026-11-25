package io.klob.wallpapers.live;

import android.Manifest;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.view.KeyEvent;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

public final class SettingsActivity extends Activity {
    private static final ExecutorService wallpaperWriter = Executors.newSingleThreadExecutor();
    private static final int AUDIO_PERMISSION = 31;
    private static final int PICK_WALLPAPER = 32;
    private static final String EDITING_SCREEN = "editing_screen";
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private AppStyle style;
    private ScrollView scroll;
    private TextView mark;
    private Button rainbowButton;
    private ValueAnimator rainbowMotion;
    private int keyProgress;
    private boolean rainbowOn;
    private static final int[] RAINBOW_SEQUENCE = {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_A,
    };
    private LinearLayout audioDetails;
    private LinearLayout qualityPanel;
    private Preview preview;
    private SeekBar speed;
    private LinearLayout zoomControls;
    private LinearLayout colorControls;
    private SeekBar zoomSpeed;
    private Switch audioSync;
    private TextView audioStatus;
    private Button microphoneControl;
    private Spinner audioSource;
    private WallpaperScreen editingScreen = WallpaperScreen.HOME;
    private Spinner patterns;
    private Button modelButton;
    private Button regularButton;
    private final Map<String, Switch> appearanceToggles = new LinkedHashMap<>();
    private boolean refreshingAppearance;
    private final Runnable audioMeter = new Runnable() {
        @Override public void run() {
            updateAudioStatus();
            if (audioSync.isEnabled() && audioSync.isChecked()) uiHandler.postDelayed(this, 250);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        preview.refresh(prefs);
        if (Settings.ZOOM_SPEED.equals(key)) zoomSpeed.setProgress(Settings.zoomSpeed(prefs));
        if (Settings.ANIMATE.equals(key) || Settings.AUDIO_SYNC.equals(key) || Settings.AUDIO_SOURCE.equals(key)) {
            if (!microphoneEnabled()) MicrophoneAudioService.stop(this);
            refreshAudioStatus();
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        preferences = Settings.open(this);
        int theme = preferences.getInt(Settings.THEME, AppStyle.SYSTEM);
        boolean light = AppStyle.light(this, theme);
        setTheme(light ? R.style.AppThemeLight : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        style = new AppStyle(this, light);
        if (savedInstanceState != null) {
            editingScreen = WallpaperScreen.fromPreference(savedInstanceState.getString(EDITING_SCREEN));
        }
        preview = new Preview(this, preferences, editingScreen);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(style.surface);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(16), dp(16), dp(28));
        int windowWidth = getWindowManager().getCurrentWindowMetrics().getBounds().width();
        wrapper.addView(column, new LinearLayout.LayoutParams(Math.min(windowWidth, dp(680)), -2));
        scroll.addView(wrapper, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(brand, fullWidth(-2, 0));
        mark = new TextView(this);
        mark.setText("//");
        style.text(mark, 26, style.accent, true);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(mark);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        style.text(title, 23, style.text, true);
        title.setPadding(dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        brand.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        rainbowButton = style.iconButton(AppStyle.RAINBOW, "Rainbow effect");
        rainbowButton.setOnClickListener(view -> toggleRainbow());
        brand.addView(rainbowButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        addThemePicker(column, theme);
        LinearLayout content = addPanel(column, "Wallpaper");
        addLayoutSelector(content);
        TextView modelLabel = addText(content, "Model", 16);
        modelButton = style.button(false);
        modelButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        android.graphics.drawable.Drawable arrow = style.icon(AppStyle.CHEVRON, style.muted);
        modelButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null);
        modelButton.setCompoundDrawablePadding(dp(6));
        modelButton.setId(View.generateViewId());
        modelLabel.setLabelFor(modelButton.getId());
        modelButton.setAllCaps(false);
        modelButton.setOnClickListener(view -> {
            Catalog.Model current = Settings.model(this, preferences, editingScreen);
            ModelPicker.show(this, Catalog.get(this), current, picked -> {
                preferences.edit().putString(editingScreen.key(Settings.MODEL), picked.id)
                        .remove(editingScreen.key(Settings.SUGGESTED)).apply();
                refreshAppearance();
            });
        });
        content.addView(modelButton, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        TextView patternLabel = addText(content, "Pattern", 16);
        patterns = new Spinner(this, Spinner.MODE_DROPDOWN);
        patterns.setId(View.generateViewId());
        styleSpinner(patterns);
        patternLabel.setLabelFor(patterns.getId());
        ArrayAdapter<String> names = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, Settings.PATTERNS);
        names.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        patterns.setAdapter(names);
        patterns.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!refreshingAppearance && pos != Settings.pattern(preferences, editingScreen)) {
                    preferences.edit().putInt(editingScreen.key(Settings.PATTERN), pos).apply();
                    refreshAppearance();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        content.addView(patterns, fullWidth(-2, 0));

        LinearLayout previewPanel = addPanel(column, "Preview");
        LinearLayout stage = new LinearLayout(this);
        stage.setGravity(Gravity.CENTER);
        stage.setPadding(0, dp(10), 0, dp(10));
        stage.setBackground(style.shape(style.surface, style.line, 8));
        previewPanel.addView(stage, fullWidth(-2, 0));
        preview.setBackground(style.shape(Color.BLACK, 0, 6));
        stage.addView(preview, new LinearLayout.LayoutParams(1, 1));

        Button apply = style.button(true);
        apply.setText("APPLY LIVE WALLPAPER");
        apply.setOnClickListener(view -> openWallpaperPicker());
        previewPanel.addView(apply, fullWidth(-2, 12));

        regularButton = style.button(false);
        regularButton.setOnClickListener(view -> applyRegularWallpaper());
        previewPanel.addView(regularButton, fullWidth(-2, 8));

        LinearLayout outlines = addPanel(column, "Outlines");
        addAppearanceToggle(outlines, "RGB border", Settings.BORDER);
        addAppearanceToggle(outlines, "Camera ring", Settings.CAMERA);
        addAppearanceToggle(outlines, "Home row and search outline", Settings.DOCK);
        addAppearanceToggle(outlines, "Status bar outline", Settings.STATUS);

        zoomControls = addPanel(column, "Zoom & Performance");
        zoomSpeed = addSlider(zoomControls, Settings.ZOOM_SPEED, 1, Settings.MAX_ZOOM, Settings.zoomSpeed(preferences),
                step -> "Zoom speed // " + java.text.NumberFormat.getNumberInstance().format(step / 4.0) + "×");
        addChoice(zoomControls, "Frame-rate cap", Settings.FRAME_RATE, Settings.frameRateIndex(preferences),
                new String[]{"15 fps", "30 fps", "60 fps"});
        qualityPanel = new LinearLayout(this);
        qualityPanel.setOrientation(LinearLayout.VERTICAL);
        zoomControls.addView(qualityPanel, fullWidth(-2, 4));
        addChoice(qualityPanel, "Texture quality", Settings.TEXTURE_QUALITY, Settings.quality(preferences),
                new String[]{"Balanced // half resolution", "High // full resolution", "Ultra // full + antialiasing"});

        colorControls = addPanel(column, "Color & Audio");
        addToggle(colorControls, "Animate colors", Settings.ANIMATE);
        speed = addSlider(colorControls, Settings.PERIOD, Settings.MIN_PERIOD, Settings.MAX_PERIOD,
                Settings.period(preferences), seconds -> "Cycle duration // " + seconds + " s");
        speed.setEnabled(Settings.enabled(preferences, Settings.ANIMATE));
        addAudioSync(colorControls);
        addAudioTuner(audioDetails);
        refreshAppearance();
        if (savedInstanceState != null) scroll.post(() -> scroll.scrollTo(0, savedInstanceState.getInt("scroll_y")));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(EDITING_SCREEN, editingScreen.name());
        state.putInt("scroll_y", scroll.getScrollY());
        super.onSaveInstanceState(state);
    }

    @Override public void onStart() {
        super.onStart();
        preferences.registerOnSharedPreferenceChangeListener(changes);
        refreshAppearance();
        preview.setActive(true);
        refreshAudioStatus();
    }

    @Override public void onStop() {
        preferences.unregisterOnSharedPreferenceChangeListener(changes);
        uiHandler.removeCallbacks(audioMeter);
        preview.setActive(false);
        if (rainbowOn) toggleRainbow();
        super.onStop();
    }

    private void openWallpaperPicker() {
        preferences.edit().putString(Settings.PICKER_SCREEN, editingScreen.name()).apply();
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, RgbWallpaperService.class));
        try {
            startActivityForResult(intent, PICK_WALLPAPER);
        } catch (ActivityNotFoundException exception) {
            preferences.edit().remove(Settings.PICKER_SCREEN).apply();
            Toast.makeText(this, "Open Wallpaper & style and select rgb geometry live.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_WALLPAPER) preferences.edit().remove(Settings.PICKER_SCREEN).apply();
    }

    private void applyRegularWallpaper() {
        WallpaperScreen target = editingScreen;
        android.view.WindowMetrics metrics = getWindowManager().getMaximumWindowMetrics();
        Rect bounds = metrics.getBounds();
        PanelCorners corners = PanelCorners.read(getDisplay(), metrics);
        Catalog.Model model = Catalog.get(this).forOrientation(Settings.model(this, preferences, target),
                bounds.width() > bounds.height());
        String path = Settings.artPath(model, preferences, target);
        Context context = getApplicationContext();
        regularButton.setEnabled(false);
        wallpaperWriter.execute(() -> {
            String message;
            try {
                int id = RegularWallpaper.apply(context, path, bounds.width(), bounds.height(), target, model, corners);
                message = id > 0 ? target + " wallpaper set." : "Android could not set the wallpaper.";
            } catch (IOException | RuntimeException exception) {
                android.util.Log.e("RgbGeometryLive", "Could not set regular wallpaper", exception);
                message = "Could not set the wallpaper. Check that wallpaper changes are allowed.";
            }
            String result = message;
            uiHandler.post(() -> {
                if (!isDestroyed()) regularButton.setEnabled(true);
                Toast.makeText(context, result, Toast.LENGTH_LONG).show();
            });
        });
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

    private TextView addText(LinearLayout content, String value, int size) {
        TextView label = new TextView(this);
        label.setText(value);
        style.text(label, size <= 14 ? 13 : 14, style.muted, false);
        content.addView(label, fullWidth(-2, 4));
        return label;
    }

    private LinearLayout addPanel(LinearLayout column, String name) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(13), dp(14), dp(13));
        panel.setBackground(style.shape(style.panel, style.line, 10));
        column.addView(panel, fullWidth(-2, 12));
        TextView header = new TextView(this);
        header.setText(name);
        header.setAccessibilityHeading(true);
        style.text(header, 17, style.text, true);
        LinearLayout.LayoutParams params = fullWidth(-2, 0);
        params.bottomMargin = dp(8);
        panel.addView(header, params);
        return panel;
    }

    private void addThemePicker(LinearLayout column, int selected) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText("Theme");
        style.text(label, 14, style.muted, false);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        for (int mode = 0; mode <= 2; ++mode) {
            final int choice = mode;
            Button icon = style.iconButton(mode, new String[]{"System theme", "Light theme", "Dark theme"}[mode]);
            icon.setSelected(mode == selected);
            if (icon.isSelected()) icon.setForeground(style.icon(mode, style.accent));
            icon.setOnClickListener(button -> {
                if (selected == choice) return;
                preferences.edit().putInt(Settings.THEME, choice).apply();
                recreate();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
            params.leftMargin = dp(6);
            row.addView(icon, params);
        }
        column.addView(row, fullWidth(-2, 6));
    }

    private void styleSpinner(Spinner spinner) {
        android.graphics.drawable.Drawable bg = spinner.getBackground();
        if (bg != null) bg.mutate().setTint(style.muted);
        spinner.setMinimumHeight(dp(48));
        spinner.setPaddingRelative(dp(8), dp(4), dp(28), dp(4));
        spinner.setPopupBackgroundDrawable(style.shape(style.panel, style.line, 8));
    }

    private void addChoice(LinearLayout panel, String title, String key, int selected, String[] options) {
        TextView label = addText(panel, title, 14);
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        spinner.setId(View.generateViewId());
        label.setLabelFor(spinner.getId());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        styleSpinner(spinner);
        spinner.setSelection(selected);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
                if (position != preferences.getInt(key, 1)) preferences.edit().putInt(key, position).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        panel.addView(spinner, fullWidth(-2, 0));
    }

    private void toggleRainbow() {
        rainbowOn = !rainbowOn;
        rainbowButton.setSelected(rainbowOn);
        if (rainbowMotion != null) rainbowMotion.cancel();
        if (!rainbowOn) {
            scroll.setBackgroundColor(style.surface);
            mark.setScaleX(1);
            return;
        }
        tintRainbow(0);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        rainbowMotion = ValueAnimator.ofFloat(0, 360);
        rainbowMotion.setDuration(7000);
        rainbowMotion.setRepeatCount(ValueAnimator.INFINITE);
        rainbowMotion.addUpdateListener(frame -> tintRainbow((Float) frame.getAnimatedValue()));
        rainbowMotion.start();
    }

    private void tintRainbow(float hue) {
        boolean dark = style.surface == 0xff101010;
        scroll.setBackgroundColor(Color.HSVToColor(new float[]{hue, dark ? .55f : .22f, dark ? .29f : .98f}));
        if (ValueAnimator.areAnimatorsEnabled()) mark.setScaleX(1 + .10f * (float)Math.sin(Math.toRadians(hue)));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_ESCAPE && rainbowOn) {
                toggleRainbow();
                return true;
            }
            View focused = getCurrentFocus();
            if (!(focused instanceof android.widget.EditText) && !(focused instanceof SeekBar)
                    && !(focused instanceof Spinner)) {
                keyProgress = code == RAINBOW_SEQUENCE[keyProgress] ? keyProgress + 1
                        : code == RAINBOW_SEQUENCE[0] ? 1 : 0;
                if (keyProgress == RAINBOW_SEQUENCE.length) {
                    keyProgress = 0;
                    toggleRainbow();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void addLayoutSelector(LinearLayout content) {
        TextView label = addText(content, "Layout to edit", 16);
        Spinner layouts = new Spinner(this, Spinner.MODE_DROPDOWN);
        layouts.setId(View.generateViewId());
        styleSpinner(layouts);
        label.setLabelFor(layouts.getId());
        ArrayAdapter<WallpaperScreen> names = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, WallpaperScreen.values());
        names.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        layouts.setAdapter(names);
        layouts.setSelection(editingScreen.ordinal());
        layouts.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                editingScreen = (WallpaperScreen) parent.getItemAtPosition(position);
                refreshAppearance();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        content.addView(layouts, fullWidth(-2, 0));
    }

    private void addAppearanceToggle(LinearLayout content, String title, String key) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        style.setupSwitch(toggle);
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (!refreshingAppearance) {
                preferences.edit().putBoolean(editingScreen.key(key), enabled).apply();
                if (Settings.BORDER.equals(key)) refreshAppearance();
            }
        });
        appearanceToggles.put(key, toggle);
        content.addView(toggle, fullWidth(-2, 0));
    }

    private void refreshAppearance() {
        refreshingAppearance = true;
        Catalog.Model model = Settings.model(this, preferences, editingScreen);
        if (model.matches(Build.MODEL) && model.manufacturer.equalsIgnoreCase(Build.MANUFACTURER)) {
            Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
            model = Catalog.get(this).forOrientation(model, bounds.width() > bounds.height());
        }
        String suggestion = preferences.getBoolean(editingScreen.key(Settings.SUGGESTED), false)
                ? "Suggested // " : "";
        modelButton.setText(suggestion + model.name + " // " + model.width + " × " + model.height);
        regularButton.setText(editingScreen == WallpaperScreen.LOCK ? "SET LOCK WALLPAPER" : "SET HOME WALLPAPER");
        int pattern = Settings.pattern(preferences, editingScreen);
        zoomControls.setVisibility(Settings.hasZoom(pattern) ? View.VISIBLE : View.GONE);
        qualityPanel.setVisibility(pattern == Settings.MANDELBROT ? View.VISIBLE : View.GONE);
        colorControls.setVisibility(pattern == Settings.MENGER ? View.GONE : View.VISIBLE);
        zoomSpeed.setProgress(Settings.zoomSpeed(preferences));
        int style = Settings.enabled(preferences, editingScreen.key(Settings.BORDER)) ? Catalog.BORDER : 0;
        patterns.setSelection(pattern);
        for (Map.Entry<String, Switch> entry : appearanceToggles.entrySet()) {
            String key = entry.getKey();
            Switch toggle = entry.getValue();
            if (Settings.CAMERA.equals(key)) {
                toggle.setText(model.estimatedCamera ? "Camera ring (estimated)" : "Camera ring");
            } else if (Settings.DOCK.equals(key)) {
                toggle.setText(model.id.equals("pixel_tablet_portrait")
                        ? "Home row and search outline (estimated)" : "Home row and search outline");
            }
            boolean visible = Settings.BORDER.equals(key)
                    || (Settings.CAMERA.equals(key) && model.supports(pattern, style, Catalog.CAMERA))
                    || (Settings.STATUS.equals(key) && model.supports(pattern, style, Catalog.STATUS))
                    || (Settings.DOCK.equals(key) && editingScreen == WallpaperScreen.HOME
                            && model.supports(pattern, style, Catalog.DOCK));
            toggle.setVisibility(visible ? View.VISIBLE : View.GONE);
            toggle.setChecked(visible && Settings.enabled(preferences, editingScreen.key(key)));
        }
        refreshingAppearance = false;
        int availableWidth = Math.max(1, getWindowManager().getCurrentWindowMetrics().getBounds().width() - dp(64));
        float scale = Math.min(Math.min(dp(460), availableWidth) / (float) model.width,
                dp(300) / (float) model.height);
        ViewGroup.LayoutParams size = preview.getLayoutParams();
        int width = Math.max(1, Math.round(model.width * scale));
        int height = Math.max(1, Math.round(model.height * scale));
        if (size.width != width || size.height != height) {
            size.width = width;
            size.height = height;
            preview.setLayoutParams(size);
        }
        preview.setScreen(preferences, editingScreen);
    }

    private void addToggle(LinearLayout content, String title, String key) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        style.setupSwitch(toggle);
        toggle.setChecked(Settings.enabled(preferences, key));
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            preferences.edit().putBoolean(key, enabled).apply();
            if (Settings.ANIMATE.equals(key) && speed != null) speed.setEnabled(enabled);
        });
        content.addView(toggle, fullWidth(-2, 0));
    }

    private void addAudioSync(LinearLayout content) {
        audioSync = new Switch(this);
        audioSync.setText("Audio Sync");
        style.setupSwitch(audioSync);
        audioSync.setChecked(Settings.audioSync(preferences) && OutputAudio.hasPermission(this));
        audioSync.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled && !OutputAudio.hasPermission(this)) {
                audioSync.setChecked(false);
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
                return;
            }
            preferences.edit().putBoolean(Settings.AUDIO_SYNC, enabled).apply();
        });
        content.addView(audioSync, fullWidth(-2, 0));
        audioDetails = new LinearLayout(this);
        audioDetails.setOrientation(LinearLayout.VERTICAL);
        content.addView(audioDetails, fullWidth(-2, 0));
        addAudioSource(audioDetails);
        audioStatus = addText(audioDetails, "", 14);
    }

    private void addAudioSource(LinearLayout content) {
        TextView sourceLabel = addText(content, "Audio source", 16);
        audioSource = new Spinner(this, Spinner.MODE_DROPDOWN);
        audioSource.setId(View.generateViewId());
        styleSpinner(audioSource);
        sourceLabel.setLabelFor(audioSource.getId());
        ArrayAdapter<AudioSource> sources = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                AudioSource.values());
        sources.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        audioSource.setAdapter(sources);
        audioSource.setSelection(Settings.audioSource(preferences).ordinal());
        audioSource.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                AudioSource source = (AudioSource) parent.getItemAtPosition(position);
                preferences.edit().putString(Settings.AUDIO_SOURCE, source.name()).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        content.addView(audioSource, fullWidth(-2, 0));

        microphoneControl = style.button(false);
        microphoneControl.setAllCaps(false);
        microphoneControl.setOnClickListener(view -> {
            try {
                if (MicrophoneAudioService.isRunning()) MicrophoneAudioService.stop(this);
                else MicrophoneAudioService.start(this);
            } catch (IllegalStateException | SecurityException exception) {
                Toast.makeText(this, "Could not start microphone audio.", Toast.LENGTH_SHORT).show();
            }
            refreshAudioStatus();
        });
        content.addView(microphoneControl, fullWidth(dp(48), 4));
    }

    private boolean microphoneEnabled() {
        return Settings.audioSource(preferences) != AudioSource.DEVICE && Settings.audioSync(preferences)
                && Settings.enabled(preferences, Settings.ANIMATE) && OutputAudio.hasPermission(this);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == AUDIO_PERMISSION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            audioSync.setChecked(true);
        }
    }

    private void refreshAudioStatus() {
        audioSync.setEnabled(Settings.enabled(preferences, Settings.ANIMATE));
        uiHandler.removeCallbacks(audioMeter);
        audioMeter.run();
    }

    private void updateAudioStatus() {
        audioDetails.setVisibility(audioSync.isChecked() && audioSync.isEnabled() ? View.VISIBLE : View.GONE);
        AudioSource source = Settings.audioSource(preferences);
        boolean microphoneRunning = MicrophoneAudioService.isRunning();
        microphoneControl.setVisibility(microphoneEnabled() ? View.VISIBLE : View.GONE);
        String control = microphoneRunning ? "Stop microphone" : "Start microphone";
        if (source == AudioSource.AUTO) control += " fallback";
        if (!control.contentEquals(microphoneControl.getText())) microphoneControl.setText(control);
        String message;
        if (!audioSync.isChecked()) {
            message = "Audio Sync requires Android’s microphone permission for either audio source.";
        } else if (!audioSync.isEnabled()) {
            message = "Turn on Animate colors to react to audio.";
        } else if (source == AudioSource.AUTO) {
            message = automaticAudioStatus(microphoneRunning);
        } else if (source == AudioSource.MICROPHONE) {
            if (!microphoneRunning) {
                message = MicrophoneAudioService.failed() ? "Microphone unavailable. Try starting it again."
                        : "Start the microphone to react to sound around you.";
            } else {
                message = MicrophoneAudioService.pulses().hasSignal(SystemClock.uptimeMillis())
                        ? "Microphone audio detected" : "Listening for microphone audio";
            }
        } else if (!OutputAudio.INSTANCE.isAvailable()) {
            message = "Audio output is unavailable. The regular color cycle is still running.";
        } else if (OutputAudio.INSTANCE.pulses().hasSignal(SystemClock.uptimeMillis())) {
            message = "Device audio detected";
        } else {
            message = "Waiting for device audio";
        }
        if (!message.contentEquals(audioStatus.getText())) audioStatus.setText(message);
    }

    private String automaticAudioStatus(boolean microphoneRunning) {
        if (AutoAudio.INSTANCE.isDevicePlaying()) return "Auto · Device audio";
        if (AutoAudio.INSTANCE.isUsingMicrophone()) return "Auto · Microphone";
        if (microphoneRunning) return "Auto · Waiting for a playback pause";
        if (MicrophoneAudioService.failed()) return "Microphone unavailable. Try starting it again.";
        return "Start microphone fallback to react when device playback pauses.";
    }

    private void addAudioTuner(LinearLayout content) {
        Button tune = style.button(false);
        tune.setAllCaps(false);
        tune.setText("Tune audio effects");
        content.addView(tune, fullWidth(dp(48), 6));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        content.addView(panel, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        tune.setOnClickListener(view -> {
            boolean opening = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(opening ? View.VISIBLE : View.GONE);
            tune.setText(opening ? "Close audio tuner" : "Tune audio effects");
        });

        TextView effectLabel = addText(panel, "Effect", 16);
        Spinner effects = new Spinner(this, Spinner.MODE_DROPDOWN);
        effects.setId(View.generateViewId());
        styleSpinner(effects);
        effectLabel.setLabelFor(effects.getId());
        ArrayAdapter<AudioEffect> choices = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, AudioEffect.values());
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        effects.setAdapter(choices);
        effects.setSelection(Settings.audioEffect(preferences).ordinal());
        effects.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                AudioEffect effect = (AudioEffect) parent.getItemAtPosition(position);
                preferences.edit().putString(Settings.AUDIO_EFFECT, effect.name()).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        panel.addView(effects, fullWidth(-2, 0));

        SeekBar intensity = addAudioSlider(panel, "Intensity", Settings.AUDIO_INTENSITY, Settings.DEFAULT_INTENSITY);
        SeekBar smoothness = addAudioSlider(panel, "Smoothness", Settings.AUDIO_SMOOTHNESS, Settings.DEFAULT_SMOOTHNESS);
        SeekBar sensitivity = addAudioSlider(panel, "Bass sensitivity", Settings.BASS_SENSITIVITY, Settings.DEFAULT_SENSITIVITY);

        Button reset = style.button(false);
        reset.setAllCaps(false);
        reset.setText("Reset audio effects");
        reset.setOnClickListener(view -> {
            preferences.edit().remove(Settings.AUDIO_EFFECT).remove(Settings.AUDIO_INTENSITY)
                    .remove(Settings.AUDIO_SMOOTHNESS).remove(Settings.BASS_SENSITIVITY).apply();
            effects.setSelection(AudioEffect.DEFAULT.ordinal());
            intensity.setProgress(Settings.DEFAULT_INTENSITY);
            smoothness.setProgress(Settings.DEFAULT_SMOOTHNESS);
            sensitivity.setProgress(Settings.DEFAULT_SENSITIVITY);
        });
        panel.addView(reset, fullWidth(dp(48), 4));
    }

    private SeekBar addAudioSlider(LinearLayout panel, String label, String key, int fallback) {
        return addSlider(panel, key, 0, 100, Settings.percentage(preferences, key, fallback),
                value -> label + " · " + value + "%");
    }

    private SeekBar addSlider(LinearLayout content, String key, int min, int max, int value,
            IntFunction<String> formatLabel) {
        TextView label = addText(content, formatLabel.apply(value), 16);
        SeekBar slider = new SeekBar(this);
        slider.setId(View.generateViewId());
        label.setLabelFor(slider.getId());
        slider.setMin(min);
        slider.setMax(max);
        slider.setProgress(value);
        slider.setStateDescription(label.getText());
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                label.setText(formatLabel.apply(progress));
                slider.setStateDescription(label.getText());
                if (fromUser) preferences.edit().putInt(key, progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar slider) {}
            @Override public void onStopTrackingTouch(SeekBar slider) {}
        });
        content.addView(slider, fullWidth(dp(48), 0));
        return slider;
    }

    private static final class Preview extends View {
        private final RgbRenderer renderer;
        private final Choreographer clock = Choreographer.getInstance();
        private final FramePacer pace = new FramePacer();
        private final Rect visibleArea = new Rect();
        private final Choreographer.FrameCallback animation = this::onFrame;
        private boolean active;
        private boolean queued;
        private WallpaperScreen screen;

        Preview(Context context, SharedPreferences preferences, WallpaperScreen screen) {
            super(context);
            renderer = new RgbRenderer(context);
            renderer.setPanel(context.getDisplay(), context.getSystemService(android.view.WindowManager.class)
                    .getMaximumWindowMetrics());
            setScreen(preferences, screen);
        }

        void setScreen(SharedPreferences preferences, WallpaperScreen screen) {
            this.screen = screen;
            setContentDescription(screen + " wallpaper preview");
            refresh(preferences);
        }

        void refresh(SharedPreferences preferences) {
            renderer.update(preferences, screen);
            invalidate();
        }

        void setActive(boolean active) {
            this.active = active;
            renderer.setVisible(active && getWindowVisibility() == VISIBLE);
            if (active) invalidate();
            else stopFrames();
        }

        private void stopFrames() {
            clock.removeFrameCallback(animation);
            pace.reset();
            queued = false;
        }

        private void queueFrame() {
            if (queued || !renderer.isAnimating()) return;
            queued = true;
            clock.postFrameCallback(animation);
        }

        private void onFrame(long vsyncNanos) {
            queued = false;
            if (!renderer.isAnimating() || !getLocalVisibleRect(visibleArea)) {
                pace.reset();
                return;
            }
            if (pace.ready(vsyncNanos, renderer.frameRate())) invalidate();
            queueFrame();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (renderer != null) {
                renderer.setVisible(active && visibility == VISIBLE);
                if (active && visibility == VISIBLE) invalidate();
                else stopFrames();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            renderer.draw(canvas, getWidth(), getHeight());
            queueFrame();
        }
    }
}
