package io.klob.wallpapers.live;

import android.Manifest;
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
        super.onCreate(savedInstanceState);
        preferences = Settings.open(this);
        if (savedInstanceState != null) {
            editingScreen = WallpaperScreen.fromPreference(savedInstanceState.getString(EDITING_SCREEN));
        }
        preview = new Preview(this, preferences, editingScreen);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = addText(content, getString(R.string.app_name), 30);
        title.setAccessibilityHeading(true);
        addLayoutSelector(content);
        TextView modelLabel = addText(content, "Model", 16);
        modelButton = new Button(this);
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
        content.addView(patterns, fullWidth(dp(48), 0));

        addAppearanceToggle(content, "RGB border", Settings.BORDER);
        addAppearanceToggle(content, "Camera ring", Settings.CAMERA);
        addAppearanceToggle(content, "Home row and search outline", Settings.DOCK);
        addAppearanceToggle(content, "Status bar outline", Settings.STATUS);
        zoomControls = new LinearLayout(this);
        zoomControls.setOrientation(LinearLayout.VERTICAL);
        content.addView(zoomControls, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        zoomSpeed = addSlider(zoomControls, Settings.ZOOM_SPEED, 1, Settings.MAX_ZOOM, Settings.zoomSpeed(preferences),
                step -> "Zoom speed · " + java.text.NumberFormat.getNumberInstance().format(step / 4.0) + "×");
        LinearLayout.LayoutParams previewLayout = new LinearLayout.LayoutParams(1, 1);
        previewLayout.gravity = Gravity.CENTER_HORIZONTAL;
        previewLayout.topMargin = dp(12);
        previewLayout.bottomMargin = dp(8);
        content.addView(preview, previewLayout);

        Button apply = new Button(this);
        apply.setText("APPLY LIVE WALLPAPER");
        apply.setOnClickListener(view -> openWallpaperPicker());
        content.addView(apply, fullWidth(dp(52), 4));

        regularButton = new Button(this);
        regularButton.setOnClickListener(view -> applyRegularWallpaper());
        content.addView(regularButton, fullWidth(dp(52), 0));

        colorControls = new LinearLayout(this);
        colorControls.setOrientation(LinearLayout.VERTICAL);
        content.addView(colorControls, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        addToggle(colorControls, "Animate colors", Settings.ANIMATE);
        addAudioSync(colorControls);
        addAudioTuner(colorControls);
        speed = addSlider(colorControls, Settings.PERIOD, Settings.MIN_PERIOD, Settings.MAX_PERIOD,
                Settings.period(preferences), seconds -> "Cycle duration · " + seconds + " s");
        speed.setEnabled(Settings.enabled(preferences, Settings.ANIMATE));
        refreshAppearance();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(EDITING_SCREEN, editingScreen.name());
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

    private TextView addText(LinearLayout content, String text, int size) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        content.addView(label);
        return label;
    }

    private void addLayoutSelector(LinearLayout content) {
        TextView label = addText(content, "Layout to edit", 16);
        Spinner layouts = new Spinner(this, Spinner.MODE_DROPDOWN);
        layouts.setId(View.generateViewId());
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
        content.addView(layouts, fullWidth(dp(48), 0));
    }

    private void addAppearanceToggle(LinearLayout content, String title, String key) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(16);
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (!refreshingAppearance) {
                preferences.edit().putBoolean(editingScreen.key(key), enabled).apply();
                if (Settings.BORDER.equals(key)) refreshAppearance();
            }
        });
        appearanceToggles.put(key, toggle);
        content.addView(toggle, fullWidth(dp(48), 0));
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
        int availableWidth = Math.max(1, getWindowManager().getCurrentWindowMetrics().getBounds().width() - dp(48));
        float scale = Math.min(Math.min(dp(300), availableWidth) / (float) model.width,
                dp(180) / (float) model.height);
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
        toggle.setTextSize(16);
        toggle.setChecked(Settings.enabled(preferences, key));
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            preferences.edit().putBoolean(key, enabled).apply();
            if (Settings.ANIMATE.equals(key) && speed != null) speed.setEnabled(enabled);
        });
        content.addView(toggle, fullWidth(dp(48), 0));
    }

    private void addAudioSync(LinearLayout content) {
        audioSync = new Switch(this);
        audioSync.setText("Audio Sync");
        audioSync.setTextSize(16);
        audioSync.setChecked(Settings.audioSync(preferences) && OutputAudio.hasPermission(this));
        audioSync.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled && !OutputAudio.hasPermission(this)) {
                audioSync.setChecked(false);
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
                return;
            }
            preferences.edit().putBoolean(Settings.AUDIO_SYNC, enabled).apply();
        });
        content.addView(audioSync, fullWidth(dp(48), 0));
        addAudioSource(content);
        audioStatus = addText(content, "", 14);
    }

    private void addAudioSource(LinearLayout content) {
        TextView sourceLabel = addText(content, "Audio source", 16);
        audioSource = new Spinner(this, Spinner.MODE_DROPDOWN);
        audioSource.setId(View.generateViewId());
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
        content.addView(audioSource, fullWidth(dp(48), 0));

        microphoneControl = new Button(this);
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
        Button tune = new Button(this);
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
        panel.addView(effects, fullWidth(dp(48), 0));

        SeekBar intensity = addAudioSlider(panel, "Intensity", Settings.AUDIO_INTENSITY, Settings.DEFAULT_INTENSITY);
        SeekBar smoothness = addAudioSlider(panel, "Smoothness", Settings.AUDIO_SMOOTHNESS, Settings.DEFAULT_SMOOTHNESS);
        SeekBar sensitivity = addAudioSlider(panel, "Bass sensitivity", Settings.BASS_SENSITIVITY, Settings.DEFAULT_SENSITIVITY);

        Button reset = new Button(this);
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
        private boolean active;
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
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (renderer != null) {
                renderer.setVisible(active && visibility == VISIBLE);
                if (active && visibility == VISIBLE) invalidate();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            renderer.draw(canvas, getWidth(), getHeight());
            if (renderer.isAnimating()) postInvalidateDelayed(RgbRenderer.FRAME_INTERVAL_MS);
        }
    }
}
