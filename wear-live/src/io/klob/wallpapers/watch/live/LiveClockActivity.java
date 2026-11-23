package io.klob.wallpapers.watch.live;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

public final class LiveClockActivity extends Activity implements SensorEventListener, LiveClockView.Listener {
    private static final String PREFS = "wear_live";
    private static final String KEY_MODE = "mode";
    private static final String KEY_SPEED = "speed";
    private static final String PERM_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE";
    private static final int REQ_HEART_RATE = 101;

    private LiveClockView clockView;
    private SensorManager sensorManager;
    private Sensor heartRateSensor;
    private boolean batteryRegistered;
    private boolean sensorRegistered;
    private boolean askedPermission;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LiveClockView.Mode mode = parseEnum(LiveClockView.Mode.class, prefs.getString(KEY_MODE, null),
                LiveClockView.Mode.FULL);
        LiveClockView.Speed speed = parseEnum(LiveClockView.Speed.class, prefs.getString(KEY_SPEED, null),
                LiveClockView.Speed.MEDIUM);

        clockView = new LiveClockView(this, mode, speed, this);
        setContentView(clockView);
        applyBrightness(mode);
        clockView.showHud(mode.label + " // " + speed.label);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        }

        hideSystemBars();
        ensureHeartRatePermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        clockView.setRunning(true);

        Intent sticky = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        batteryRegistered = true;
        if (sticky != null) {
            updateBattery(sticky);
        }
        startHeartRateSensor();
    }

    @Override
    protected void onPause() {
        clockView.setRunning(false);
        if (batteryRegistered) {
            unregisterReceiver(batteryReceiver);
            batteryRegistered = false;
        }
        if (sensorRegistered && sensorManager != null) {
            sensorManager.unregisterListener(this);
            sensorRegistered = false;
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_HEART_RATE) {
            startHeartRateSensor();
        }
    }

    @Override
    public void onStateChanged(LiveClockView.Mode mode, LiveClockView.Speed speed) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.name())
                .putString(KEY_SPEED, speed.name())
                .apply();
        applyBrightness(mode);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_HEART_RATE && event.values.length > 0) {
            int bpm = Math.round(event.values[0]);
            if (bpm > 0) {
                clockView.setHeartRate(bpm);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void applyBrightness(LiveClockView.Mode mode) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = (mode == LiveClockView.Mode.DIM)
                ? 0.16f
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(params);
    }

    private void hideSystemBars() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private String heartRatePermission() {
        return Build.VERSION.SDK_INT >= 36 ? PERM_READ_HEART_RATE : Manifest.permission.BODY_SENSORS;
    }

    private boolean hasHeartRatePermission() {
        return checkSelfPermission(PERM_READ_HEART_RATE) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureHeartRatePermission() {
        if (!hasHeartRatePermission() && !askedPermission) {
            askedPermission = true;
            requestPermissions(new String[] { heartRatePermission() }, REQ_HEART_RATE);
        }
    }

    private void startHeartRateSensor() {
        if (sensorManager == null || heartRateSensor == null || sensorRegistered) return;
        if (!hasHeartRatePermission()) return;
        sensorRegistered = sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void updateBattery(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) {
            clockView.setBatteryPercent(Math.round(level * 100f / scale));
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value, T fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
