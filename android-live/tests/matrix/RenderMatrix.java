package io.klob.wallpapers.live;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.HardwareBufferRenderer;
import android.graphics.Paint;
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
import java.util.function.Consumer;

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
            PanelCornersTest.run(context);
            MengerWindowsTest.run(context);
            checkMandelbrot();
            checkFramePacing();
            checkRotation();
            checkToneAdjustments();
            checkFramingZoom();
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

    private void checkMandelbrot() throws Exception {
        for (Catalog.Model model : Catalog.get(context).models) {
            for (int mask = 0; mask < 16; mask++) {
                String path = model.path(Settings.MANDELBROT, mask);
                require(MandelbrotZoom.uses(path) && WallpaperArt.assetPath(path).equals(model.path(Settings.MENGER, mask)),
                        "Wrong Mandelbrot overlay for " + model.id);
            }
        }
        MandelbrotZoom scene = new MandelbrotZoom(context);
        require(scene.isAvailable(), "Mandelbrot projection shader did not compile");
        for (int[] size : new int[][]{{270, 600}, {640, 400}, {400, 400}, {180, 640}}) {
            int width = size[0], height = size[1];
            try (FrameCapture frames = new FrameCapture(width, height)) {
                Bitmap still = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                MandelbrotZoom.drawStill(context, new Canvas(still), width, height);
                for (int quality = 0; quality < 3; ++quality) {
                    scene.setQuality(quality);
                    Bitmap start = frames.draw(canvas -> scene.draw(context, canvas, width, height, 0));
                    Bitmap wrap = frames.draw(canvas -> scene.draw(context, canvas, width, height, .999999));
                    Bitmap middle = frames.draw(canvas -> scene.draw(context, canvas, width, height, .47));
                    require(hasColor(start) && hasColor(middle), "Blank Mandelbrot scene");
                    require(meanChannelDifference(start, wrap) < .8, "Mandelbrot zoom jumps at the wrap");
                    require(meanChannelDifference(start, middle) > 5, "Mandelbrot did not zoom with colors fixed");
                    double difference = meanChannelDifference(start, still);
                    require(difference < 5, "Mandelbrot still differs from the initial view "
                            + width + "x" + height + " quality " + quality + " mean " + difference);
                    require(Color.red(start.getPixel(width / 2, height / 2)) <= 1
                            && Color.blue(start.getPixel(width / 2, height / 2)) <= 1, "Mandelbrot interior is not black");
                    require(Color.blue(start.getPixel(3, height - 4)) >= 115, "Mandelbrot lost the cobalt background");
                    start.recycle();
                    wrap.recycle();
                    middle.recycle();
                }
                still.recycle();
            }
        }
        Log.i(TAG, "Mandelbrot: all 603 model profiles, four aspect ratios, three quality modes, GPU wraps and stills verified");
    }

    private static void checkFramePacing() {
        for (int refresh : new int[]{60, 90, 120}) {
            for (int target : new int[]{15, 30, 60}) {
                FramePacer pace = new FramePacer();
                int frames = 0;
                for (int tick = 0; tick < refresh * 4; tick++) {
                    if (pace.ready(tick * 1_000_000_000L / refresh, target)) frames++;
                }
                require(Math.abs(frames - target * 4) <= 1,
                        "Wrong frame cap at " + refresh + " Hz / " + target + " fps: " + frames);
            }
        }
    }

    private void checkRotation() throws Exception {
        SharedPreferences angles = context.getSharedPreferences("rotation-fixture", Context.MODE_PRIVATE);
        Catalog.Model model = Catalog.get(context).lookup("pixel_11_pro_xl");
        angles.edit().clear().putBoolean(Settings.ANIMATE, false)
                .putString(Settings.MODEL, model.id)
                .putString(WallpaperScreen.LOCK.key(Settings.MODEL), model.id)
                .putInt(WallpaperScreen.LOCK.key(Settings.ROTATION), 315).apply();
        require(Settings.rotation(angles, WallpaperScreen.HOME) == 0, "Existing Home layout must start at zero");
        final int w = 270, h = 600;
        RgbRenderer scene = new RgbRenderer(context);
        for (int pattern = 0; pattern < Settings.PATTERNS.length; pattern++) {
            angles.edit().putInt(Settings.PATTERN, pattern).putInt(Settings.ROTATION, 0).apply();
            String path = Settings.artPath(model, angles, WallpaperScreen.HOME);
            scene.update(angles, WallpaperScreen.HOME);
            Bitmap before = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            scene.draw(new Canvas(before));
            Bitmap still = WallpaperArt.renderStatic(context, path, w, h, model, null, 0);
            Bitmap overlay = WallpaperArt.hasScene(path)
                    ? WallpaperArt.render(context, path, w, h, 1, model, null, false)
                    : WallpaperArt.renderLayer(context, path, w, h, model, null, WallpaperArt.Layer.FRAME);
            require(Color.alpha(overlay.getPixel(w / 2, h / 2)) == 0, "The frame layer hides the pattern");
            for (int angle : new int[]{37, 90, 225, 360}) {
                angles.edit().putInt(Settings.ROTATION, angle).apply();
                scene.update(angles, WallpaperScreen.HOME);
                Bitmap live = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                scene.draw(new Canvas(live));
                Bitmap regular = WallpaperArt.renderStatic(context, path, w, h, model, null, angle);
                if (angle == 360) {
                    require(before.sameAs(live) && still.sameAs(regular), "360° changed the unrotated image");
                } else {
                    require(meanChannelDifference(before, live) > 2, "Live art did not rotate for pattern " + pattern);
                    require(meanChannelDifference(still, regular) > 2, "Still art did not rotate for pattern " + pattern);
                    checkFixedOutlines(overlay, live);
                }
                live.recycle();
                regular.recycle();
            }
            before.recycle();
            still.recycle();
            overlay.recycle();
        }
        require(Settings.rotation(angles, WallpaperScreen.LOCK) == 315, "Home edits changed the Lock angle");
        Log.i(TAG, "Rotation: all five patterns, live and still angles, fixed outlines, Home and Lock isolation");
    }

    private static void checkFixedOutlines(Bitmap overlay, Bitmap wallpaper) {
        int visible = 0, changed = 0;
        for (int y = 0; y < overlay.getHeight(); y++) {
            for (int x = 0; x < overlay.getWidth(); x++) {
                int pixel = overlay.getPixel(x, y);
                if (Color.alpha(pixel) < 255 || (pixel & 0x00ffffff) == 0) continue;
                visible++;
                int drawn = wallpaper.getPixel(x, y);
                if (Math.abs(Color.red(pixel) - Color.red(drawn)) > 2
                        || Math.abs(Color.green(pixel) - Color.green(drawn)) > 2
                        || Math.abs(Color.blue(pixel) - Color.blue(drawn)) > 2) changed++;
            }
        }
        require(visible > 50 && changed * 50 <= visible, "Device outlines moved or disappeared with rotation");
    }

    private void checkToneAdjustments() throws Exception {
        int sample = Color.rgb(96, 64, 32);
        int dim = filteredPixel(sample, new WallpaperTone(50, 100, 100));
        require(dim == Color.rgb(48, 32, 16), "Brightness should scale RGB");
        int flat = filteredPixel(sample, new WallpaperTone(100, 0, 100));
        require(Math.abs(Color.red(flat) - 128) <= 1 && Color.red(flat) == Color.green(flat)
                && Color.green(flat) == Color.blue(flat), "Zero contrast should flatten midtones");
        int vivid = filteredPixel(sample, new WallpaperTone(100, 100, 200));
        require(Color.red(vivid) - Color.blue(vivid) > 64, "Saturation should increase color separation");

        SharedPreferences colors = context.getSharedPreferences("tone-fixture", Context.MODE_PRIVATE);
        Catalog.Model model = Catalog.get(context).lookup("pixel_11_pro_xl");
        colors.edit().clear().putString(Settings.MODEL, model.id).putBoolean(Settings.ANIMATE, false)
                .putInt(WallpaperScreen.LOCK.key(Settings.BRIGHTNESS), 83)
                .putInt(WallpaperScreen.LOCK.key(Settings.CONTRAST), 58)
                .putInt(WallpaperScreen.LOCK.key(Settings.SATURATION), 40).apply();
        final int width = 240, height = 530;
        RgbRenderer scene = new RgbRenderer(context);
        WallpaperTone adjusted = new WallpaperTone(70, 135, 0);
        require(Settings.tone(colors, WallpaperScreen.HOME, WallpaperTone.NEUTRAL) == WallpaperTone.NEUTRAL,
                "Existing settings should keep the original colors");
        try (FrameCapture frames = new FrameCapture(width, height)) {
            for (int pattern = 0; pattern < Settings.PATTERNS.length; pattern++) {
                colors.edit().putInt(Settings.PATTERN, pattern).apply();
                String path = Settings.artPath(model, colors, WallpaperScreen.HOME);
                for (int rotation : new int[]{0, 37}) {
                    colors.edit().putInt(Settings.ROTATION, rotation).remove(Settings.BRIGHTNESS)
                            .remove(Settings.CONTRAST).remove(Settings.SATURATION).apply();
                    scene.update(colors, WallpaperScreen.HOME);
                    Bitmap base = frames.draw(scene);
                    Bitmap still = WallpaperArt.renderStatic(context, path, width, height, model, null, rotation);
                    colors.edit().putInt(Settings.BRIGHTNESS, 70).putInt(Settings.CONTRAST, 135)
                            .putInt(Settings.SATURATION, 0).apply();
                    scene.update(colors, WallpaperScreen.HOME);
                    Bitmap live = frames.draw(scene);
                    Bitmap regular = WallpaperArt.renderStatic(context, path, width, height, model, null, rotation, adjusted);
                    checkColorAdjustment(base, live, adjusted, "live pattern " + pattern);
                    checkColorAdjustment(still, regular, adjusted, "still pattern " + pattern);
                    base.recycle();
                    still.recycle();
                    live.recycle();
                    regular.recycle();
                }
            }
        }
        require(Settings.tone(colors, WallpaperScreen.LOCK, WallpaperTone.NEUTRAL).matches(83, 58, 40),
                "Home adjustments changed the Lock settings");
        Log.i(TAG, "Color adjustments: all five patterns, hardware and still output, with and without rotation");
    }

    private static int filteredPixel(int color, WallpaperTone tone) {
        Bitmap src = Bitmap.createBitmap(new int[]{color}, 1, 1, Bitmap.Config.ARGB_8888);
        Bitmap result = filteredImage(src, tone);
        int pixel = result.getPixel(0, 0);
        src.recycle();
        result.recycle();
        return pixel;
    }

    private static Bitmap filteredImage(Bitmap source, WallpaperTone tone) {
        Bitmap result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setColorFilter(tone.filter());
        new Canvas(result).drawBitmap(source, 0, 0, paint);
        return result;
    }

    private static void checkColorAdjustment(Bitmap before, Bitmap after, WallpaperTone tone, String description) {
        Bitmap expected = filteredImage(before, tone);
        require(meanChannelDifference(before, after) > 2, "Color sliders had no effect on " + description);
        double error = meanChannelDifference(expected, after);
        expected.recycle();
        require(error < 2, "Color filter disagrees with composed output on " + description + " (mean " + error + ")");
        for (int y = 0; y < after.getHeight(); y += 9) {
            for (int x = 0; x < after.getWidth(); x += 9) {
                int color = after.getPixel(x, y);
                require(Math.abs(Color.red(color) - Color.green(color)) <= 1
                        && Math.abs(Color.green(color) - Color.blue(color)) <= 1,
                        "Zero saturation left visible hue on " + description);
            }
        }
    }

    private void checkFramingZoom() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("framing-fixture", Context.MODE_PRIVATE);
        Catalog.Model model = Catalog.get(context).lookup("pixel_11_pro_xl");
        prefs.edit().clear().putString(Settings.MODEL, model.id).putBoolean(Settings.ANIMATE, false)
                .putInt(Settings.ZOOM_SPEED, 7).putInt(WallpaperScreen.LOCK.key(Settings.FRAMING_ZOOM), 335).apply();
        require(Settings.framingZoom(prefs, WallpaperScreen.HOME) == ArtworkTransform.FIT, "Unexpected default crop");

        final int width = 240, height = 530;
        RgbRenderer scene = new RgbRenderer(context);
        try (FrameCapture frames = new FrameCapture(width, height)) {
            for (int pattern = 0; pattern < Settings.PATTERNS.length; pattern++) {
                prefs.edit().putInt(Settings.PATTERN, pattern).putInt(Settings.ROTATION, 0)
                        .remove(Settings.FRAMING_ZOOM).apply();
                String path = Settings.artPath(model, prefs, WallpaperScreen.HOME);
                scene.update(prefs, WallpaperScreen.HOME);
                Bitmap base = frames.draw(scene);
                Bitmap still = WallpaperArt.renderStatic(context, path, width, height, model, null);
                Bitmap overlay = WallpaperArt.hasScene(path)
                        ? WallpaperArt.render(context, path, width, height, 1, model, null, false)
                        : WallpaperArt.renderLayer(context, path, width, height, model, null, WallpaperArt.Layer.FRAME);
                Bitmap previous = base;
                for (int zoom : new int[]{25, 55, 95, 175, 300, 400}) {
                    int rotation = zoom == 400 ? 37 : 0;
                    prefs.edit().putInt(Settings.FRAMING_ZOOM, zoom).putInt(Settings.ROTATION, rotation).apply();
                    scene.update(prefs, WallpaperScreen.HOME);
                    Bitmap live = frames.draw(scene);
                    Bitmap regular = WallpaperArt.renderStatic(context, path, width, height, model, null,
                            rotation, zoom, WallpaperTone.NEUTRAL);
                    require(meanChannelDifference(previous, live) > 1.5,
                            "Framing did not change at " + zoom + "% for live pattern " + pattern);
                    require(meanChannelDifference(still, regular) > 1.5,
                            "Framing did not change at " + zoom + "% for still pattern " + pattern);
                    checkFixedOutlines(overlay, live);
                    if (zoom == ArtworkTransform.MIN_ZOOM && pattern < Settings.MENGER) {
                        checkWideGrid(live, pattern, "live", 1);
                        int id = context.getResources().getIdentifier("config_wallpaperMaxScale", "dimen", "android");
                        float zoomAtRest = id == 0 ? 1.10f : Math.max(1, context.getResources().getFloat(id));
                        checkWideGrid(regular, pattern, "still", 1 / zoomAtRest);
                        int wideRotation = 37;
                        prefs.edit().putInt(Settings.ROTATION, wideRotation).apply();
                        scene.update(prefs, WallpaperScreen.HOME);
                        Bitmap turned = frames.draw(scene);
                        checkFixedOutlines(overlay, turned);
                        checkWideGrid(turned, pattern, "rotated", 1);
                        turned.recycle();
                    }
                    if (previous != base) previous.recycle();
                    previous = live;
                    regular.recycle();
                }
                previous.recycle();
                prefs.edit().remove(Settings.FRAMING_ZOOM).putInt(Settings.ROTATION, 0).apply();
                scene.update(prefs, WallpaperScreen.HOME);
                Bitmap restored = frames.draw(scene);
                require(restored.sameAs(base), "Returning to 1× did not restore the original fit");
                restored.recycle();
                overlay.recycle();
                still.recycle();
                base.recycle();
            }
        }
        require(Settings.framingZoom(prefs, WallpaperScreen.LOCK) == 335, "Home edits changed the Lock zoom");
        require(Settings.zoomSpeed(prefs) == 7, "Framing zoom changed the animation speed");
        prefs.edit().putInt(Settings.FRAMING_ZOOM, 999).apply();
        require(Settings.framingZoom(prefs, WallpaperScreen.HOME) == ArtworkTransform.MAX_ZOOM, "Wrong zoom cap");
        prefs.edit().putInt(Settings.FRAMING_ZOOM, -5).apply();
        require(Settings.framingZoom(prefs, WallpaperScreen.HOME) == ArtworkTransform.MIN_ZOOM, "Wrong minimum zoom");
        Log.i(TAG, "Framing zoom: 0.25× to 4×, edge-to-edge grids, fixed outlines and fit/speed isolation");
    }

    private static void checkWideGrid(Bitmap image, int pattern, String kind, float paddingScale) {
        // Test four bands outside the original artwork footprint. Ignore the
        // outermost border and launcher padding so only the repeated art counts.
        int[][] bands = {{8, 11, 24, 75}, {76, 11, 92, 75}, {24, 9, 76, 25}, {24, 68, 76, 80}};
        for (int[] band : bands) {
            int lit = 0, total = 0;
            for (int y = at(band[1], image.getHeight(), paddingScale); y < at(band[3], image.getHeight(), paddingScale); y++) {
                for (int x = at(band[0], image.getWidth(), paddingScale); x < at(band[2], image.getWidth(), paddingScale); x++) {
                    int pixel = image.getPixel(x, y);
                    if (Math.max(Color.red(pixel), Math.max(Color.green(pixel), Color.blue(pixel))) > 64) lit++;
                    total++;
                }
            }
            require(lit > total * .025, "Blank edge at 0.25×, " + kind + " pattern " + pattern);
        }
    }

    private static int at(int percent, int length, float paddingScale) {
        return Math.round(length * (.5f + (percent / 100f - .5f) * paddingScale));
    }

    private static double meanChannelDifference(Bitmap first, Bitmap second) {
        long difference = 0, count = 0;
        for (int y = 0; y < first.getHeight(); y += 3) {
            for (int x = 0; x < first.getWidth(); x += 3) {
                int a = first.getPixel(x, y), b = second.getPixel(x, y);
                difference += Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                        + Math.abs(Color.blue(a) - Color.blue(b));
                count += 3;
            }
        }
        return (double) difference / count;
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
            return draw(renderer::draw);
        }

        Bitmap draw(Consumer<Canvas> draw) throws Exception {
            Canvas canvas = node.beginRecording(w, h);
            draw.accept(canvas);
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
