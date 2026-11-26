package io.klob.wallpapers.live;

import android.Manifest;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.view.KeyEvent;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public final class SettingsActivity extends Activity {
    private static final ExecutorService wallpaperWriter = Executors.newSingleThreadExecutor();
    // The app can recreate while Android is committing a large still.
    private static final Set<SettingsActivity> writerObservers = new LinkedHashSet<>();
    private static WallpaperScreen writingStill;
    private static final int AUDIO_PERMISSION = 31;
    private static final int PICK_WALLPAPER = 32;
    private static final String EDITING_SCREEN = "editing_screen";
    private static final String PICKER_REQUEST = "picker_request";
    private static final String EXPANDED = "expanded_preview";
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
    private SeekBar rotationSlider;
    private SeekBar framingZoomSlider;
    private final Map<String, SeekBar> toneSliders = new LinkedHashMap<>();
    private Switch audioSync;
    private TextView audioStatus;
    private Button microphoneControl;
    private Spinner audioSource;
    private WallpaperScreen editingScreen = WallpaperScreen.HOME;
    private Spinner patterns;
    private Button modelButton;
    private Button regularButton;
    private Button liveButton;
    private TextView pickerHint;
    private LinearLayout previewTile;
    private FrameLayout compactStage;
    private AlertDialog expandedDialog;
    private int compactWidth, compactHeight;
    private boolean started;
    private long pickerRequest;
    private final Map<WallpaperScreen, Button> screenButtons = new EnumMap<>(WallpaperScreen.class);
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
            pickerRequest = savedInstanceState.getLong(PICKER_REQUEST);
        } else if (PreviewRequest.current() != null) {
            editingScreen = PreviewRequest.current();
        }
        preview = new Preview(this, preferences, editingScreen);

        android.content.res.Configuration config = getResources().getConfiguration();
        boolean side = config.screenWidthDp >= 600 && config.screenWidthDp > config.screenHeightDp;
        boolean shortWindow = config.screenHeightDp < 480;
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(style.surface);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(28));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(style.surface);
        LinearLayout workspace = new LinearLayout(this);
        workspace.setOrientation(side ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int windowWidth = getWindowManager().getCurrentWindowMetrics().getBounds().width();
        // WRAP_CONTENT-like outer width with a tablet limit, constrained by actual insets.
        int available = Math.min(windowWidth, dp(Math.min(config.screenWidthDp, side ? 900 : 680)));
        root.addView(workspace, new FrameLayout.LayoutParams(available, -1, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        LinearLayout studio = createStudio(side, shortWindow);
        LinearLayout.LayoutParams studioSize = new LinearLayout.LayoutParams(side ? dp(222) : -1, -2);
        studioSize.setMargins(dp(12), dp(8), dp(12), dp(4));
        workspace.addView(studio, studioSize);
        workspace.addView(scroll, side ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

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
        addPreviewActions(addPanel(column, "Apply Wallpaper"));
        LinearLayout content = addPanel(column, "Wallpaper");
        addText(content, "If this layout is already live, edits show immediately.", 13);
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
        patterns.setAdapter(wrappingChoices(Settings.PATTERNS));
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
        rotationSlider = addSlider(content, "Rotation", 0, ArtworkTransform.FULL_TURN,
                Settings.rotation(preferences, editingScreen), degrees -> degrees + "°",
                degrees -> preferences.edit().putInt(editingScreen.key(Settings.ROTATION), degrees).apply());
        rotationSlider.setKeyProgressIncrement(1);
        int maxZoomStep = (ArtworkTransform.MAX_ZOOM - ArtworkTransform.MIN_ZOOM) / ArtworkTransform.ZOOM_STEP;
        framingZoomSlider = addSlider(content, "Framing zoom", 0, maxZoomStep, framingZoomStep(),
                step -> java.text.NumberFormat.getNumberInstance().format(
                        (ArtworkTransform.MIN_ZOOM + step * ArtworkTransform.ZOOM_STEP) / 100.0) + "×",
                step -> preferences.edit().putInt(editingScreen.key(Settings.FRAMING_ZOOM),
                        ArtworkTransform.MIN_ZOOM + step * ArtworkTransform.ZOOM_STEP).apply());
        framingZoomSlider.setKeyProgressIncrement(1);

        addToneControls(addPanel(column, "Color Adjustments"));

        LinearLayout outlines = addPanel(column, "Outlines");
        addAppearanceToggle(outlines, "RGB border", Settings.BORDER);
        addAppearanceToggle(outlines, "Camera ring", Settings.CAMERA);
        addAppearanceToggle(outlines, "Home row and search outline", Settings.DOCK);
        addAppearanceToggle(outlines, "Status bar outline", Settings.STATUS);

        zoomControls = addPanel(column, "Zoom & Performance");
        addText(zoomControls, "Shared by Home and Lock. Zoom speed changes motion, not framing.", 13);
        zoomSpeed = addSlider(zoomControls, "Fractal zoom speed", Settings.ZOOM_SPEED, 1, Settings.MAX_ZOOM,
                Settings.zoomSpeed(preferences), step -> java.text.NumberFormat.getNumberInstance().format(step / 4.0) + "×");
        addChoice(zoomControls, "Frame-rate cap", Settings.FRAME_RATE, Settings.frameRateIndex(preferences),
                new String[]{"15 fps", "30 fps", "60 fps"});
        qualityPanel = new LinearLayout(this);
        qualityPanel.setOrientation(LinearLayout.VERTICAL);
        zoomControls.addView(qualityPanel, fullWidth(-2, 4));
        addChoice(qualityPanel, "Texture quality", Settings.TEXTURE_QUALITY, Settings.quality(preferences),
                new String[]{"Balanced // half resolution", "High // full resolution", "Ultra // full + antialiasing"});

        colorControls = addPanel(column, "Color & Audio");
        addText(colorControls, "Shared by Home and Lock.", 13);
        addToggle(colorControls, "Animate colors", Settings.ANIMATE);
        speed = addSlider(colorControls, "Cycle duration", Settings.PERIOD, Settings.MIN_PERIOD, Settings.MAX_PERIOD,
                Settings.period(preferences), seconds -> seconds + " s");
        speed.setEnabled(Settings.enabled(preferences, Settings.ANIMATE));
        addAudioSync(colorControls);
        addAudioTuner(audioDetails);
        refreshAppearance();
        if (savedInstanceState != null) scroll.post(() -> {
            scroll.scrollTo(0, savedInstanceState.getInt("scroll_y"));
            if (savedInstanceState.getBoolean(EXPANDED) && started) showExpandedPreview();
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(EDITING_SCREEN, editingScreen.name());
        state.putInt("scroll_y", scroll.getScrollY());
        state.putLong(PICKER_REQUEST, pickerRequest);
        state.putBoolean(EXPANDED, expandedDialog != null);
        super.onSaveInstanceState(state);
    }

    @Override public void onStart() {
        super.onStart();
        started = true;
        writerObservers.add(this);
        preferences.registerOnSharedPreferenceChangeListener(changes);
        refreshAppearance();
        preview.setActive(true);
        refreshAudioStatus();
    }

    @Override public void onStop() {
        started = false;
        writerObservers.remove(this);
        preferences.unregisterOnSharedPreferenceChangeListener(changes);
        uiHandler.removeCallbacks(audioMeter);
        preview.setActive(false);
        if (rainbowOn) toggleRainbow();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (!isChangingConfigurations()) PreviewRequest.end(pickerRequest);
        if (expandedDialog != null) expandedDialog.dismiss();
        super.onDestroy();
    }

    private void openWallpaperPicker() {
        pickerRequest = PreviewRequest.begin(editingScreen);
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, RgbWallpaperService.class));
        try {
            startActivityForResult(intent, PICK_WALLPAPER);
        } catch (ActivityNotFoundException exception) {
            finishPickerRequest();
            Toast.makeText(this, "Open Wallpaper & style and select rgb geometry live.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_WALLPAPER) finishPickerRequest();
    }

    private void finishPickerRequest() {
        PreviewRequest.end(pickerRequest);
        pickerRequest = 0;
    }

    private static void setWritingStill(WallpaperScreen target) {
        writingStill = target;
        for (SettingsActivity observer : writerObservers) observer.refreshActions();
    }

    private void applyRegularWallpaper() {
        if (writingStill != null) return;
        WallpaperScreen target = editingScreen;
        android.view.WindowMetrics metrics = getWindowManager().getMaximumWindowMetrics();
        Rect bounds = metrics.getBounds();
        PanelCorners corners = PanelCorners.read(getDisplay(), metrics);
        Catalog.Model model = Catalog.get(this).forOrientation(Settings.model(this, preferences, target),
                bounds.width() > bounds.height());
        String path = Settings.artPath(model, preferences, target);
        int rotation = Settings.rotation(preferences, target);
        int zoom = Settings.framingZoom(preferences, target);
        WallpaperTone tone = Settings.tone(preferences, target, WallpaperTone.NEUTRAL);
        Context context = getApplicationContext();
        setWritingStill(target);
        wallpaperWriter.execute(() -> {
            String message;
            try {
                int id = RegularWallpaper.apply(context, path, bounds.width(), bounds.height(),
                        target, model, corners, rotation, zoom, tone);
                message = id > 0 ? target + " wallpaper set." : "Android could not set the wallpaper.";
            } catch (IOException | RuntimeException exception) {
                android.util.Log.e("RgbGeometryLive", "Could not set regular wallpaper", exception);
                message = "Could not set the wallpaper. Check that wallpaper changes are allowed.";
            }
            String result = message;
            uiHandler.post(() -> {
                setWritingStill(null);
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

    private <T> ArrayAdapter<T> wrappingChoices(T[] items) {
        ArrayAdapter<T> adapter = new ArrayAdapter<T>(this, android.R.layout.simple_spinner_item, items) {
            private View wrap(View row) {
                TextView text = (TextView) row;
                text.setSingleLine(false);
                text.setMaxLines(Integer.MAX_VALUE);
                text.setEllipsize(null);
                text.setMinimumHeight(dp(48));
                return text;
            }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return wrap(super.getView(position, convertView, parent));
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return wrap(super.getDropDownView(position, convertView, parent));
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void addChoice(LinearLayout panel, String title, String key, int selected, String[] options) {
        TextView label = addText(panel, title, 14);
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        spinner.setId(View.generateViewId());
        label.setLabelFor(spinner.getId());
        spinner.setAdapter(wrappingChoices(options));
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

    private LinearLayout createStudio(boolean side, boolean shortWindow) {
        LinearLayout studio = new LinearLayout(this);
        studio.setOrientation(side ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        studio.setGravity(Gravity.TOP);
        studio.setPadding(dp(10), dp(10), dp(10), dp(10));
        studio.setBackground(style.shape(style.panel, style.line, 10));

        compactWidth = dp(side ? 192 : 82);
        compactHeight = dp(side ? 132 : shortWindow ? 64 : 100);
        previewTile = new LinearLayout(this);
        previewTile.setOrientation(LinearLayout.VERTICAL);
        previewTile.setGravity(Gravity.CENTER);
        previewTile.setBackground(style.shape(style.surface, style.line, 8));
        previewTile.setFocusable(true);
        previewTile.setOnClickListener(view -> showExpandedPreview());
        compactStage = new FrameLayout(this);
        previewTile.addView(compactStage, new LinearLayout.LayoutParams(-1, compactHeight + dp(6)));
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        compactStage.addView(preview, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        TextView enlarge = new TextView(this);
        style.text(enlarge, 12, style.muted, true);
        enlarge.setText("Enlarge");
        enlarge.setGravity(Gravity.CENTER);
        enlarge.setPadding(0, 0, 0, dp(6));
        enlarge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        previewTile.addView(enlarge, new LinearLayout.LayoutParams(-1, -2));
        studio.addView(previewTile, new LinearLayout.LayoutParams(side ? -1 : compactWidth, -2));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams controlsSize = side ? fullWidth(-2, 8) : new LinearLayout.LayoutParams(0, -2, 1);
        if (!side) controlsSize.setMarginStart(dp(10));
        studio.addView(controls, controlsSize);

        TextView scope = addText(controls, "Layout to edit", 13);
        scope.setAccessibilityHeading(true);
        LinearLayout tabs = new LinearLayout(this);
        controls.addView(tabs, fullWidth(-2, 4));
        for (WallpaperScreen screen : WallpaperScreen.values()) {
            Button button = style.segmentButton(shortName(screen));
            button.setContentDescription("Edit " + screen);
            button.setOnClickListener(view -> {
                if (screen == editingScreen) return;
                editingScreen = screen;
                refreshAppearance();
            });
            LinearLayout.LayoutParams tab = new LinearLayout.LayoutParams(0, -1, 1);
            if (screen == WallpaperScreen.LOCK) tab.setMarginStart(dp(6));
            tabs.addView(button, tab);
            screenButtons.put(screen, button);
        }
        return studio;
    }

    private void addPreviewActions(LinearLayout controls) {
        liveButton = style.button(true);
        liveButton.setOnClickListener(view -> openWallpaperPicker());
        controls.addView(liveButton, fullWidth(-2, 8));
        regularButton = style.button(false);
        regularButton.setOnClickListener(view -> applyRegularWallpaper());
        controls.addView(regularButton, fullWidth(-2, 6));
        pickerHint = addText(controls, "", 13);
    }

    private static String shortName(WallpaperScreen screen) {
        return screen == WallpaperScreen.LOCK ? "Lock" : "Home";
    }

    private void refreshActions() {
        String screen = shortName(editingScreen);
        for (Map.Entry<WallpaperScreen, Button> entry : screenButtons.entrySet()) {
            entry.getValue().setSelected(entry.getKey() == editingScreen);
        }
        previewTile.setContentDescription("Enlarge " + editingScreen + " wallpaper preview");
        liveButton.setText("Preview " + screen + " live");
        regularButton.setEnabled(writingStill == null);
        regularButton.setText(writingStill == null ? "Set " + screen + " still"
                : "Setting " + shortName(writingStill) + " still…");
        pickerHint.setText(editingScreen == WallpaperScreen.LOCK && Build.VERSION.SDK_INT == 33
                ? "Choose where to apply in Android’s picker. For live Lock, your picker may require both screens."
                : "Choose where to apply in Android’s picker. Each screen uses its own layout.");
    }

    private Catalog.Model previewModel() {
        Catalog.Model model = Settings.model(this, preferences, editingScreen);
        if (model.matches(Build.MODEL) && model.manufacturer.equalsIgnoreCase(Build.MANUFACTURER)) {
            Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
            return Catalog.get(this).forOrientation(model, bounds.width() > bounds.height());
        }
        return model;
    }

    private void resizePreview(Catalog.Model model) {
        int maxWidth = compactWidth - dp(6);
        int maxHeight = compactHeight;
        if (expandedDialog != null) {
            android.content.res.Configuration config = getResources().getConfiguration();
            maxWidth = dp(Math.max(40, Math.min(config.screenWidthDp - 84, 480)));
            maxHeight = dp(Math.max(64, Math.min(config.screenHeightDp - 180, 520)));
        }
        float scale = Math.min(maxWidth / (float) model.width, maxHeight / (float) model.height);
        int width = Math.max(1, Math.round(model.width * scale));
        int height = Math.max(1, Math.round(model.height * scale));
        ViewGroup.LayoutParams size = preview.getLayoutParams();
        if (size.width != width || size.height != height) {
            size.width = width;
            size.height = height;
            preview.setLayoutParams(size);
        }
    }

    private void showExpandedPreview() {
        if (expandedDialog != null || !started) return;
        preview.setActive(false);
        compactStage.removeView(preview);
        FrameLayout stage = new FrameLayout(this);
        stage.setPadding(dp(10), dp(8), dp(10), dp(8));
        stage.addView(preview, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        expandedDialog = new AlertDialog.Builder(this).setTitle(editingScreen + " preview")
                .setView(stage).setPositiveButton("Done", null).create();
        expandedDialog.setOnDismissListener(dialog -> {
            preview.setActive(false);
            expandedDialog = null;
            stage.removeView(preview);
            compactStage.addView(preview, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
            preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            resizePreview(previewModel());
            preview.setActive(started);
        });
        resizePreview(previewModel());
        expandedDialog.show();
        if (expandedDialog.getWindow() != null) {
            expandedDialog.getWindow().setBackgroundDrawable(style.shape(style.panel, style.line, 10));
        }
        preview.setActive(true);
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
        Catalog.Model model = previewModel();
        String suggestion = preferences.getBoolean(editingScreen.key(Settings.SUGGESTED), false)
                ? "Suggested // " : "";
        modelButton.setText(suggestion + model.name + " // " + model.width + " × " + model.height);
        refreshActions();
        int pattern = Settings.pattern(preferences, editingScreen);
        zoomControls.setVisibility(Settings.hasZoom(pattern) ? View.VISIBLE : View.GONE);
        qualityPanel.setVisibility(pattern == Settings.MANDELBROT ? View.VISIBLE : View.GONE);
        colorControls.setVisibility(pattern == Settings.MENGER ? View.GONE : View.VISIBLE);
        zoomSpeed.setProgress(Settings.zoomSpeed(preferences));
        rotationSlider.setProgress(Settings.rotation(preferences, editingScreen));
        framingZoomSlider.setProgress(framingZoomStep());
        refreshToneControls();
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
        resizePreview(model);
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
        content.addView(microphoneControl, fullWidth(-2, 4));
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
        content.addView(tune, fullWidth(-2, 6));

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
        panel.addView(reset, fullWidth(-2, 4));
    }

    private int framingZoomStep() {
        return (Settings.framingZoom(preferences, editingScreen) - ArtworkTransform.MIN_ZOOM)
                / ArtworkTransform.ZOOM_STEP;
    }

    private void addToneControls(LinearLayout panel) {
        addToneSlider(panel, "Brightness", Settings.BRIGHTNESS);
        addToneSlider(panel, "Contrast", Settings.CONTRAST);
        addToneSlider(panel, "Saturation", Settings.SATURATION);
        Button reset = style.button(false);
        reset.setText("Reset color adjustments");
        reset.setOnClickListener(view -> {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : toneSliders.keySet()) editor.remove(editingScreen.key(key));
            editor.apply();
            refreshToneControls();
        });
        panel.addView(reset, fullWidth(-2, 4));
    }

    private void addToneSlider(LinearLayout panel, String title, String key) {
        SeekBar slider = addSlider(panel, title, 0, WallpaperTone.MAX, WallpaperTone.DEFAULT,
                value -> value + "%",
                value -> preferences.edit().putInt(editingScreen.key(key), value).apply());
        slider.setKeyProgressIncrement(1);
        toneSliders.put(key, slider);
    }

    private void refreshToneControls() {
        for (Map.Entry<String, SeekBar> entry : toneSliders.entrySet()) {
            entry.getValue().setProgress(Settings.adjustment(preferences, editingScreen, entry.getKey()));
        }
    }

    private SeekBar addAudioSlider(LinearLayout panel, String label, String key, int fallback) {
        return addSlider(panel, label, key, 0, 100, Settings.percentage(preferences, key, fallback),
                value -> value + "%");
    }

    private SeekBar addSlider(LinearLayout content, String title, String key, int min, int max, int value,
            IntFunction<String> formatValue) {
        return addSlider(content, title, min, max, value, formatValue,
                progress -> preferences.edit().putInt(key, progress).apply());
    }

    private SeekBar addSlider(LinearLayout content, String title, int min, int max, int value,
            IntFunction<String> formatValue, IntConsumer saveValue) {
        TextView label = addText(content, title + " // " + formatValue.apply(value), 16);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(title);
        slider.setMin(min);
        slider.setMax(max);
        slider.setProgress(value);
        slider.setStateDescription(formatValue.apply(value));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                String valueText = formatValue.apply(progress);
                label.setText(title + " // " + valueText);
                slider.setStateDescription(valueText);
                if (fromUser) saveValue.accept(progress);
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
