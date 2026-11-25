package io.klob.wallpapers.live;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.HardwareBufferRenderer;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** API 34+ test APK, self-instrumented so the installed wallpaper and preferences stay untouched. */
public final class RenderMatrix extends Instrumentation {
    private static final String TAG = "WallpaperMatrix";
    private Context context;
    private SharedPreferences preferences;
    private RgbRenderer renderer;
    private FrameCapture capture;
    private int selections, comparisons, failures;
    private float launcherZoom;
    private final Map<String, float[]> rings = new HashMap<>();
    private final Set<String> models = new HashSet<>();
    private final Set<String> rendered = new HashSet<>();

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        String detail;
        try {
            context = getTargetContext();
            SettingsMigrationTest.run(context);
            int scaleId = context.getResources().getIdentifier("config_wallpaperMaxScale", "dimen", "android");
            launcherZoom = scaleId == 0 ? 1.10f : Math.max(1f, context.getResources().getFloat(scaleId));
            try (BufferedReader data = new BufferedReader(new InputStreamReader(context.getAssets().open("rings.tsv")))) {
                for (String line; (line = data.readLine()) != null; ) {
                    String[] ring = line.split("\t");
                    rings.put(ring[0], new float[]{Float.parseFloat(ring[1]), Float.parseFloat(ring[2]), Float.parseFloat(ring[3])});
                }
            }
            preferences = Settings.open(context);
            preferences.edit().putBoolean(Settings.ANIMATE, true)
                    .putBoolean(Settings.AUDIO_SYNC, false).putInt(Settings.PERIOD, 6).apply();
            renderer = new RgbRenderer(context);
            renderer.setVisible(true);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("cases.tsv")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        check(line.split("\t"));
                    } catch (Exception | AssertionError e) {
                        failures++;
                        if (failures <= 25) Log.e(TAG, line, e);
                    }
                    if (++selections % 250 == 0) Log.i(TAG, summary());
                }
            }
            require(models.size() == Catalog.get(context).models.size(), "unexpected or missing picker model");
            detail = summary();
        } catch (Throwable error) {
            failures++;
            detail = Log.getStackTraceString(error);
        } finally {
            if (renderer != null) renderer.setVisible(false);
            if (capture != null) capture.close();
        }
        Log.i(TAG, detail);
        Bundle result = new Bundle();
        result.putString("stream", detail + "\n");
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private String summary() {
        return models.size() + " models, " + selections + " selections, " + rendered.size()
                + " distinct assets, " + comparisons + " compared frames, " + failures + " failures";
    }

    private void check(String[] row) throws Exception {
        String id = row[0], file = row[5], reference = row[6];
        int pattern = Integer.parseInt(row[1]), mask = Integer.parseInt(row[2]);
        int width = Integer.parseInt(row[3]), height = Integer.parseInt(row[4]);
        models.add(id);
        Catalog.Model model = Catalog.get(context).lookup(id);
        require(model != null && model.width == width && model.height == height, "wrong picker dimensions");
        String path = null;
        for (WallpaperScreen screen : WallpaperScreen.values()) {
            if (screen == WallpaperScreen.LOCK && (mask & Catalog.DOCK) != 0) continue;
            preferences.edit().putString(screen.key(Settings.MODEL), id)
                    .putInt(screen.key(Settings.PATTERN), pattern)
                    .putBoolean(screen.key(Settings.CAMERA), (mask & Catalog.CAMERA) != 0)
                    .putBoolean(Settings.DOCK, (mask & Catalog.DOCK) != 0)
                    .putBoolean(screen.key(Settings.STATUS), (mask & Catalog.STATUS) != 0)
                    .putBoolean(screen.key(Settings.BORDER), (mask & Catalog.BORDER) != 0).apply();
            path = Settings.artPath(context, preferences, screen);
            require(path.equals("art/" + file) || path.equals("display-art/" + file), "selected " + path);
        }
        // Aliases selecting the same path exercise the same bitmap and shader. Resolve every alias above.
        if (!rendered.add(path)) return;

        Bitmap gold = gold(reference);
        int w = gold.getWidth(), h = gold.getHeight();
        Bitmap regular = WallpaperArt.renderStatic(context, path, w, h);
        Bitmap expectedStatic = gold(reference + "-static");
        compare(regular, expectedStatic);
        expectedStatic.recycle();
        regular.recycle();
        Bitmap nativeSize = WallpaperArt.renderStatic(context, path, width, height);
        require(nativeSize.getWidth() == width && nativeSize.getHeight() == height, "wrong static bitmap size");
        require(hasColor(nativeSize), "blank native-sized static wallpaper");
        if ((mask & Catalog.CAMERA) != 0) checkRing(nativeSize, rings.get(reference));
        nativeSize.recycle();

        renderer.update(preferences, WallpaperScreen.HOME);
        Bitmap idle = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        renderer.draw(new Canvas(idle));
        compare(idle, gold);
        gold.recycle();
        require(renderer.isAnimating(), "animation inactive");
        if (capture == null || capture.w != w || capture.h != h) {
            if (capture != null) capture.close();
            capture = new FrameCapture(w, h);
        }
        Bitmap animated = capture.draw(renderer);
        if (!colorChanged(idle, animated)) {
            // A frame at the start of the hue cycle should match the static image.
            animated.recycle();
            SystemClock.sleep(100);
            animated = capture.draw(renderer);
        }
        require(colorChanged(idle, animated), "GPU shader produced no hue change");
        animated.recycle();
        idle.recycle();
    }

    private void checkRing(Bitmap frame, float[] ring) {
        require(ring != null, "missing camera geometry");
        float cx = frame.getWidth() / 2f + (ring[0] - frame.getWidth() / 2f) / launcherZoom;
        float cy = frame.getHeight() / 2f + (ring[1] - frame.getHeight() / 2f) / launcherZoom;
        float radius = ring[2] / launcherZoom;
        require((frame.getPixel(Math.round(cx), Math.round(cy)) & 0xffffff) == 0, "camera aperture filled");
        for (int spoke = 0; spoke < 24; spoke++) {
            double angle = spoke * Math.PI / 12;
            int brightest = 0;
            for (float r = radius * 0.82f; r <= radius; r += 0.5f) {
                int x = (int) Math.floor(cx + Math.cos(angle) * r);
                int y = (int) Math.floor(cy + Math.sin(angle) * r);
                int color = frame.getPixel(x, y);
                brightest = Math.max(brightest, Math.max((color >> 16) & 255, Math.max((color >> 8) & 255, color & 255)));
            }
            require(brightest >= 40, "camera ring missing at spoke " + spoke);
        }
    }

    private Bitmap gold(String name) throws Exception {
        try (InputStream stream = context.getAssets().open("gold/" + name + ".png")) {
            return BitmapFactory.decodeStream(stream);
        }
    }

    private static boolean hasColor(Bitmap bitmap) {
        int[] strip = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 32)) {
            bitmap.getPixels(strip, 0, strip.length, 0, y, strip.length, 1);
            for (int color : strip) if ((color & 0xf0f0f0) != 0) return true;
        }
        return false;
    }

    private static int[] pixels(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] result = new int[w * h];
        bitmap.getPixels(result, 0, w, 0, 0, w, h);
        return result;
    }

    /** Compare local color averages to allow subpixel differences between Skia and resvg. */
    private void compare(Bitmap actual, Bitmap expected) {
        int w = expected.getWidth(), h = expected.getHeight();
        require(actual.getWidth() == w && actual.getHeight() == h, "geometry mismatch");
        int[] a = pixels(actual), e = pixels(expected);
        long difference = 0, signal = 0;
        for (int y = 0; y < h; y += 8) {
            for (int x = 0; x < w; x += 8) {
                for (int shift = 0; shift <= 16; shift += 8) {
                    int observed = 0, target = 0;
                    for (int j = y; j < Math.min(y + 8, h); j++) {
                        for (int i = x; i < Math.min(x + 8, w); i++) {
                            int p = j * w + i;
                            require((a[p] >>> 24) == 255, "transparent pixel");
                            observed += (a[p] >> shift) & 255;
                            target += (e[p] >> shift) & 255;
                        }
                    }
                    difference += Math.abs(observed - target);
                    signal += target;
                }
            }
        }
        double ratio = difference / (double) Math.max(1, signal);
        if ((signal <= 0 || ratio > 0.23) && failures == 0) {
            try {
                for (String label : new String[]{"observed", "expected"}) {
                    try (java.io.FileOutputStream out = context.openFileOutput(label + ".png", Context.MODE_PRIVATE)) {
                        (label.equals("expected") ? expected : actual).compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                }
            } catch (java.io.IOException e1) {
                Log.w(TAG, "Could not save failing pair", e1);
            }
        }
        require(signal > 0 && ratio <= 0.23, "local RGB mismatch " + ratio);
        comparisons++;
    }

    private static boolean colorChanged(Bitmap base, Bitmap animated) {
        int[] a = pixels(base), b = pixels(animated);
        int colored = 0, changed = 0, moved = 0;
        for (int i = 0; i < a.length; i++) {
            int r = (a[i] >> 16) & 255, g = (a[i] >> 8) & 255, bl = a[i] & 255;
            int rr = (b[i] >> 16) & 255, gg = (b[i] >> 8) & 255, bb = b[i] & 255;
            int v = Math.max(r, Math.max(g, bl));
            if (Math.abs(v - Math.max(rr, Math.max(gg, bb))) > 5) moved++;
            if (v - Math.min(r, Math.min(g, bl)) < 10) continue;
            colored++;
            if (Math.max(Math.abs(r - rr), Math.max(Math.abs(g - gg), Math.abs(bl - bb))) >= 3) changed++;
        }
        require(moved <= a.length / 500, "hue shader changed geometry or brightness");
        return colored > 0 && changed >= Math.max(1, colored / 8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FrameCapture implements AutoCloseable {
        final int w, h;
        final RenderNode node = new RenderNode("wallpaper-matrix");
        final HardwareBuffer buffer;
        final HardwareBufferRenderer gpu;

        FrameCapture(int width, int height) {
            w = width;
            h = height;
            buffer = HardwareBuffer.create(w, h, HardwareBuffer.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            gpu = new HardwareBufferRenderer(buffer);
            node.setPosition(0, 0, w, h);
            gpu.setContentRoot(node);
        }

        Bitmap draw(RgbRenderer renderer) throws Exception {
            Canvas canvas = node.beginRecording(w, h);
            renderer.draw(canvas);
            node.endRecording();
            LinkedBlockingQueue<HardwareBufferRenderer.RenderResult> queue = new LinkedBlockingQueue<>(1);
            gpu.obtainRenderRequest().draw(Runnable::run, queue::add);
            HardwareBufferRenderer.RenderResult result = queue.poll(10, TimeUnit.SECONDS);
            require(result != null && result.getStatus() == HardwareBufferRenderer.RenderResult.SUCCESS,
                    "hardware frame timed out or failed");
            try (SyncFence fence = result.getFence()) {
                require(fence.await(Duration.ofSeconds(5)), "hardware fence timed out");
            }
            Bitmap frame = Bitmap.wrapHardwareBuffer(buffer, null);
            require(frame != null, "no hardware bitmap");
            Bitmap readback = frame.copy(Bitmap.Config.ARGB_8888, false);
            frame.recycle();
            return readback;
        }

        @Override public void close() {
            node.discardDisplayList();
            gpu.close();
            buffer.close();
        }
    }
}
