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

import java.util.function.IntFunction;

public final class SettingsActivity extends Activity {
    private static final int AUDIO_PERMISSION = 31;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private Preview preview;
    private SeekBar speed;
    private Switch audioSync;
    private TextView audioStatus;
    private Button microphoneControl;
    private Spinner audioSource;
    private final Runnable audioMeter = new Runnable() {
        @Override public void run() {
            updateAudioStatus();
            if (audioSync.isEnabled() && audioSync.isChecked()) uiHandler.postDelayed(this, 250);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
        preview.refresh(prefs);
        if (Settings.ANIMATE.equals(key) || Settings.AUDIO_SYNC.equals(key) || Settings.MICROPHONE.equals(key)) {
            if (!microphoneEnabled()) MicrophoneAudioService.stop(this);
            refreshAudioStatus();
        }
    };

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

        addToggle(content, "Animate colors", Settings.ANIMATE);
        addAudioSync(content);
        addAudioTuner(content);
        speed = addSlider(content, Settings.PERIOD, Settings.MIN_PERIOD, Settings.MAX_PERIOD,
                Settings.period(preferences), seconds -> "Cycle duration · " + seconds + " s");
        speed.setEnabled(Settings.enabled(preferences, Settings.ANIMATE));

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
    }

    @Override public void onStart() {
        super.onStart();
        preferences.registerOnSharedPreferenceChangeListener(changes);
        preview.refresh(preferences);
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
        ArrayAdapter<String> sources = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Device audio", "Microphone"});
        sources.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        audioSource.setAdapter(sources);
        audioSource.setSelection(Settings.microphone(preferences) ? 1 : 0);
        audioSource.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putBoolean(Settings.MICROPHONE, position == 1).apply();
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
        return Settings.microphone(preferences) && Settings.audioSync(preferences)
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
        boolean microphone = Settings.microphone(preferences);
        boolean microphoneRunning = MicrophoneAudioService.isRunning();
        microphoneControl.setVisibility(microphoneEnabled() ? View.VISIBLE : View.GONE);
        String control = microphoneRunning ? "Stop microphone" : "Start microphone";
        if (!control.contentEquals(microphoneControl.getText())) microphoneControl.setText(control);
        String message;
        if (!audioSync.isChecked()) {
            message = "Audio Sync requires Android’s microphone permission for either audio source.";
        } else if (!audioSync.isEnabled()) {
            message = "Turn on Animate colors to react to audio.";
        } else if (microphone) {
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
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                label.setText(formatLabel.apply(progress));
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

        Preview(Context context, SharedPreferences preferences) {
            super(context);
            renderer = new RgbRenderer(context);
            renderer.update(preferences);
        }

        void refresh(SharedPreferences preferences) {
            renderer.update(preferences);
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
            renderer.draw(canvas);
            if (renderer.isAnimating()) postInvalidateDelayed(RgbRenderer.FRAME_INTERVAL_MS);
        }
    }
}
