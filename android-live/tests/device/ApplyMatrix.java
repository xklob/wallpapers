package io.klob.wallpapers.live;

import android.app.Instrumentation;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import android.view.WindowManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Run only on a disposable emulator. This test changes the emulator's actual system wallpaper. */
public final class ApplyMatrix extends Instrumentation {
    private static final String TAG = "WallpaperApplyTest";
    private Bundle options;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        options = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            result.putString("result", runMatrix());
            finish(-1, result);
        } catch (Throwable error) {
            Log.e(TAG, "Wallpaper matrix failed", error);
            result.putString("error", Log.getStackTraceString(error));
            finish(0, result);
        }
    }

    private static final class Selection {
        final String model;
        final int pattern, mask, width, height;
        Selection(Catalog.Model model, int pattern, int mask) {
            this.model = model.id;
            width = model.width;
            height = model.height;
            this.pattern = pattern;
            this.mask = mask;
        }
    }

    private String runMatrix() throws Exception {
        Context context = getTargetContext();
        SharedPreferences prefs = Settings.open(context);
        WallpaperManager manager = WallpaperManager.getInstance(context);
        Map<String, Selection> artwork = new LinkedHashMap<>();
        for (Catalog.Model model : Catalog.get(context).models) {
            for (int pattern = 0; pattern < Settings.PATTERNS.length; pattern++) {
                for (int mask = 0; mask < 16; mask++) {
                    String path = model.path(pattern, mask);
                    artwork.putIfAbsent(path, new Selection(model, pattern, mask));
                }
            }
        }
        String mode = options.getString("mode", "");
        if (!"live".equals(mode) && !"static".equals(mode)) {
            throw new IllegalArgumentException("Pass -e mode live or -e mode static");
        }
        boolean live = "live".equals(mode);
        int startAt = Integer.parseInt(options.getString("start", "0"));
        int endAt = Math.min(artwork.size(), Integer.parseInt(options.getString("end", "99999")));
        if (startAt < 0 || startAt >= endAt) throw new IllegalArgumentException("Empty or invalid case range");
        if (live && "true".equals(options.getString("waitPicker", "false"))) {
            Intent picker = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            picker.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            picker.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new android.content.ComponentName(context, RgbWallpaperService.class));
            context.startActivity(picker);
            long timeout = SystemClock.elapsedRealtime() + 45000;
            while (!isActive(manager, context) && SystemClock.elapsedRealtime() < timeout) {
                SystemClock.sleep(150);
            }
        }
        if (live && !isActive(manager, context)) {
            throw new AssertionError("Apply the target live wallpaper to Home in Android's picker first");
        }
        Intent showWallpaper = new Intent();
        showWallpaper.setClassName("io.klob.wallpapers.applytest", "io.klob.wallpapers.applytest.EmptyLauncher");
        showWallpaper.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(showWallpaper);
        SystemClock.sleep(1300);
        Context display = context.createDisplayContext(context.getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY))
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null);
        Rect bounds = display.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        boolean matchShape = "true".equals(options.getString("matchShape", "false"));
        boolean currentOrientation = "true".equals(options.getString("currentOrientation", "false"));
        List<Map.Entry<String, Selection>> cases = new ArrayList<>(artwork.entrySet());
        if (matchShape) {
            cases.sort(Comparator.comparingInt((Map.Entry<String, Selection> c) -> c.getValue().width)
                    .thenComparingInt(c -> c.getValue().height));
        }
        int count = 0, shapes = 0, verified = 0;
        for (Map.Entry<String, Selection> device : cases) {
            int index = count++;
            if (index < startAt || index >= endAt) continue;
            String path = device.getKey();
            Selection selection = device.getValue();
            if (currentOrientation && (selection.width > selection.height) != (bounds.width() > bounds.height())) continue;
            if (matchShape) {
                float scale = 240f / Math.min(selection.width, selection.height);
                int w = Math.round(selection.width * scale), h = Math.round(selection.height * scale);
                if (bounds.width() != w || bounds.height() != h) {
                    bounds = resize(display, w, h);
                    shapes++;
                }
            }
            prefs.edit().putString(Settings.MODEL, selection.model).putInt(Settings.PATTERN, selection.pattern)
                    .putBoolean(Settings.BORDER, (selection.mask & Catalog.BORDER) != 0)
                    .putBoolean(Settings.CAMERA, (selection.mask & Catalog.CAMERA) != 0)
                    .putBoolean(Settings.DOCK, (selection.mask & Catalog.DOCK) != 0)
                    .putBoolean(Settings.STATUS, (selection.mask & Catalog.STATUS) != 0)
                    .putBoolean(Settings.ANIMATE, false).commit();
            if (!path.equals(Settings.artPath(context, prefs, WallpaperScreen.HOME))) {
                throw new AssertionError("Persisted selection mismatch at " + index + " " + path);
            }
            Catalog.Model selected = Catalog.get(context).lookup(selection.model);
            Catalog.Model visible = Catalog.get(context).forOrientation(selected, bounds.width() > bounds.height());
            String visiblePath = Settings.artPath(visible, prefs, WallpaperScreen.HOME);
            PanelCorners corners = PanelCorners.read(display.getDisplay(),
                    display.getSystemService(WindowManager.class).getMaximumWindowMetrics());
            if (!live) {
                int id = RegularWallpaper.apply(context, visiblePath, bounds.width(), bounds.height(),
                        WallpaperScreen.HOME, visible, corners);
                if (id <= 0 || manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) != id) {
                    throw new AssertionError("Android did not commit the wallpaper at " + index + " " + path);
                }
            }
            float[] ring = matchShape && (selection.mask & Catalog.CAMERA) != 0
                    ? cameraRing(context, visiblePath, bounds) : null;
            checkVisible(context, visiblePath, bounds, index, live, ring, visible, corners);
            verified++;
            if (verified == 1 || verified % 50 == 0) Log.i(TAG, "Verified " + verified + ", case " + index + " / " + endAt + " " + path);
        }
        if (verified == 0) throw new AssertionError("No selected assets matched this panel orientation");
        return (live ? "live " : "static ") + "[" + startAt + "," + endAt + ") of " + artwork.size()
                + " distinct assets, " + verified + " verified " + (matchShape ? "with matching model aspects across " + shapes + " resizes"
                        : "at " + bounds.width() + "x" + bounds.height());
    }

    private boolean isActive(WallpaperManager manager, Context context) throws Exception {
        android.app.WallpaperInfo info = manager.getWallpaperInfo();
        if (info != null && context.getPackageName().equals(info.getPackageName())) return true;
        // Android 13 can hide WallpaperInfo from apps without broad package visibility.
        String component = "mWallpaperComponent=ComponentInfo{" + context.getPackageName() + "/"
                + RgbWallpaperService.class.getName() + "}";
        try (java.io.BufferedReader lines = new java.io.BufferedReader(new java.io.InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("dumpsys wallpaper"))))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.contains("Lock wallpaper state:")) return false;
                if (line.contains(component)) return true;
            }
        }
        return false;
    }

    private Rect resize(Context display, int width, int height) throws Exception {
        String command = "wm size " + width + "x" + height;
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand(command))) {
            while (output.read() != -1) {}
        }
        WindowManager window = display.getSystemService(WindowManager.class);
        long timeout = SystemClock.elapsedRealtime() + 8000;
        do {
            Rect bounds = window.getMaximumWindowMetrics().getBounds();
            if (bounds.width() == width && bounds.height() == height) {
                Bitmap screenshot = getUiAutomation().takeScreenshot();
                boolean ready = screenshot != null && screenshot.getWidth() == width && screenshot.getHeight() == height;
                if (screenshot != null) screenshot.recycle();
                if (ready) return bounds;
            }
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < timeout);
        throw new AssertionError("Display failed to reach " + width + "x" + height);
    }

    private static float[] cameraRing(Context context, String path, Rect bounds) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        try (InputStream stream = context.getAssets().open(WallpaperArt.assetPath(path))) {
            parser.setInput(stream, null);
            float left = 0f, top = 0f, scale = 1f;
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG) continue;
                if ("svg".equals(parser.getName())) {
                    String[] view = parser.getAttributeValue(null, "viewBox").trim().split("[ ,]+");
                    float w = Float.parseFloat(view[2]), h = Float.parseFloat(view[3]);
                    scale = Math.max(bounds.width() / w, bounds.height() / h);
                    left = (bounds.width() - w * scale) / 2 - Float.parseFloat(view[0]) * scale;
                    top = (bounds.height() - h * scale) / 2 - Float.parseFloat(view[1]) * scale;
                } else if ("circle".equals(parser.getName())) {
                    return new float[]{left + scale * Float.parseFloat(parser.getAttributeValue(null, "cx")),
                            top + scale * Float.parseFloat(parser.getAttributeValue(null, "cy")),
                            scale * Float.parseFloat(parser.getAttributeValue(null, "r"))};
                }
            }
        }
        throw new AssertionError("Missing camera circle in " + path);
    }

    private static int[] ringLight(Bitmap frame, float[] ring) {
        int[] light = new int[4];
        for (int y = Math.max(0, (int)(ring[1] - ring[2] - 3)); y <= Math.min(frame.getHeight() - 1, ring[1] + ring[2] + 3); y++) {
            for (int x = Math.max(0, (int)(ring[0] - ring[2] - 3)); x <= Math.min(frame.getWidth() - 1, ring[0] + ring[2] + 3); x++) {
                if (Math.abs(Math.hypot(x + 0.5 - ring[0], y + 0.5 - ring[1]) - ring[2]) > 2) continue;
                int p = frame.getPixel(x, y);
                light[(x > ring[0] ? 1 : 0) + (y > ring[1] ? 2 : 0)] += Math.max(p & 255,
                        Math.max((p >> 8) & 255, (p >> 16) & 255));
            }
        }
        return light;
    }

    private void checkVisible(Context context, String path, Rect bounds, int index, boolean live, float[] ring,
                              Catalog.Model model, PanelCorners corners) throws Exception {
        boolean zooming = live && WallpaperArt.hasScene(path);
        Bitmap expected = WallpaperArt.render(context, path, bounds.width(), bounds.height(), 1f, model, corners,
                !zooming);
        int[] target = samples(expected, live);
        int[] lastFrame = null;
        int[] expectedRing = ring == null ? null : ringLight(expected, ring);
        expected.recycle();
        long deadline = SystemClock.elapsedRealtime() + 4000;
        double error;
        do {
            waitForIdleSync();
            Bitmap screenshot = getUiAutomation().takeScreenshot();
            if (screenshot == null) throw new AssertionError("Android returned an empty screenshot");
            // The camera ring occupies very few pixels. Verify all four arcs separately from the whole frame.
            boolean ringVisible = true;
            if (expectedRing != null) {
                int[] measured = ringLight(screenshot, ring);
                for (int i = 0; i < 4; i++) {
                    if (expectedRing[i] == 0 || measured[i] < 0.35 * expectedRing[i]) ringVisible = false;
                }
            }
            int[] actual = samples(screenshot, live);
            screenshot.recycle();
            error = difference(target, actual, zooming);
            // With color animation off, only the fractal moves. Verify the stationary
            // frame at the selected geometry and motion between consecutive screenshots.
            boolean moved = !zooming || (lastFrame != null && difference(lastFrame, actual, false) > .015);
            if (error < (live ? 0.10 : 0.24) && ringVisible && moved) return;
            lastFrame = actual;
            SystemClock.sleep(zooming ? 150 : 24);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Visible wallpaper mismatch at " + index + " " + path + " error=" + error);
    }

    private static int[] samples(Bitmap bitmap, boolean live) {
        if (!live) return blockAverages(bitmap);
        Bitmap reduced = Bitmap.createScaledBitmap(bitmap, 99, 139, true);
        int[] rgb = new int[99 * 139];
        reduced.getPixels(rgb, 0, 99, 0, 0, 99, 139);
        if (reduced != bitmap) reduced.recycle();
        return rgb;
    }

    // Box-filter static frames. Android resamples the submitted bitmap at its resting zoom.
    private static int[] blockAverages(Bitmap bitmap) {
        int columns = Math.min(40, bitmap.getWidth()), rows = Math.min(60, bitmap.getHeight());
        int[] result = new int[columns * rows];
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int red = 0, green = 0, blue = 0, count = 0;
                for (int y = row * height / rows; y < (row + 1) * height / rows; y++) {
                    for (int x = col * width / columns; x < (col + 1) * width / columns; x++) {
                        int p = pixels[y * width + x];
                        red += (p >> 16) & 255;
                        green += (p >> 8) & 255;
                        blue += p & 255;
                        count++;
                    }
                }
                result[row * columns + col] = ((red / count) << 16) | ((green / count) << 8) | (blue / count);
            }
        }
        return result;
    }

    // Normalize by expected brightness so dark geometry cannot pass with an empty screen.
    private static double difference(int[] expected, int[] actual, boolean opaqueOnly) {
        long difference = 0, signal = 0;
        for (int i = 0; i < expected.length; i++) {
            if (opaqueOnly && (expected[i] >>> 24) < 250) continue;
            for (int shift = 0; shift <= 16; shift += 8) {
                int channel = (expected[i] >> shift) & 255;
                difference += Math.abs(channel - ((actual[i] >> shift) & 255));
                signal += channel;
            }
        }
        if (signal == 0 && opaqueOnly && difference == 0) return 0;
        return signal > 0 ? difference / (double) signal : Double.POSITIVE_INFINITY;
    }
}
