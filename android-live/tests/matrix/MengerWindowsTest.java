package io.klob.wallpapers.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

final class MengerWindowsTest {
    static void run(Context context) throws Exception {
        Catalog catalog = Catalog.get(context);
        Set<String> frames = new HashSet<>();
        for (Catalog.Model model : catalog.models) {
            for (int mask = 0; mask < 16; mask++) {
                String path = model.path(Settings.MENGER, mask);
                expect(path.equals("menger-frames/" + model.path(0, mask)),
                        "Missing matching Menger frame for " + model.id);
                if (frames.add(path)) checkFrame(context, model, path, mask);
            }
        }
        android.util.Log.i("WallpaperMatrix", "Menger Windows: " + catalog.models.size() + " models, "
                + frames.size() + " distinct overlays rendered");
        double fast = MengerWindows.advance(.27, 800, 4f);
        double slow = MengerWindows.advance(.27, 12800, .25f);
        expect(Math.abs(fast - slow) < 1e-9, "Zoom speed ratio");
        MengerWindows scene = new MengerWindows(context);
        for (int[] size : new int[][]{{320, 200}, {150, 334}, {240, 240}}) {
            Bitmap initial = frame(scene, size, 0);
            Bitmap end = frame(scene, size, 1 - .000001);
            Bitmap middle = frame(scene, size, .4);
            expect(difference(initial, end) < .4, "Zoom jumps at a scale boundary");
            expect(difference(initial, middle) > 10, "Zoom produced an empty or stationary scene");
            initial.recycle();
            end.recycle();
            middle.recycle();
        }
        Catalog.Model tablet = catalog.lookup("pixel_tablet_landscape");
        int w = 384, h = 240;
        for (int mask : new int[]{Catalog.BORDER, Catalog.BORDER | Catalog.DOCK}) {
            String path = tablet.path(Settings.MENGER, mask);
            Bitmap layer = WallpaperArt.render(context, path, w, h, 1f, tablet, null, false);
            expect(Color.alpha(layer.getPixel(w/2, h/2)) == 0, "Frame hides the Menger scene");
            expect(Color.alpha(layer.getPixel(0, 0)) == 255, "Missing black corner gutter");
            expect((Color.alpha(layer.getPixel(w/2, h*90/100)) == 255) == ((mask & Catalog.DOCK) != 0),
                    "Home-only dock matte");
            layer.recycle();
        }
        SharedPreferences prefs = context.getSharedPreferences("menger-test", Context.MODE_PRIVATE);
        RgbRenderer renderer = new RgbRenderer(context);
        Bitmap first = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Bitmap second = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            prefs.edit().putString(Settings.MODEL, tablet.id).putInt(Settings.PATTERN, Settings.MENGER)
                    .putBoolean(Settings.ANIMATE, false).putBoolean(Settings.BORDER, false)
                    .putInt(Settings.ZOOM_SPEED, Settings.MAX_ZOOM).commit();
            renderer.update(prefs, WallpaperScreen.HOME);
            renderer.setVisible(true);
            renderer.draw(new Canvas(first));
            expect(renderer.isAnimating(), "Disabling hue also stopped the zoom");
            SystemClock.sleep(200);
            renderer.setVisible(false);
            renderer.draw(new Canvas(second));
            expect(difference(first, second) > 3, "Zoom is stationary with fixed colors");
            SystemClock.sleep(60);
            renderer.draw(new Canvas(first));
            expect(!renderer.isAnimating() && first.sameAs(second), "Zoom advanced while hidden");
        } finally {
            renderer.setVisible(false);
            first.recycle();
            second.recycle();
            context.deleteSharedPreferences("menger-test");
        }
    }

    private static void checkFrame(Context context, Catalog.Model model, String path, int mask) throws Exception {
        float scale = 240f / Math.max(model.width, model.height);
        int w = Math.max(1, Math.round(model.width * scale)), h = Math.max(1, Math.round(model.height * scale));
        Bitmap overlay = WallpaperArt.render(context, path, w, h, 1f, model, null, false);
        Bitmap reference = WallpaperArt.render(context, model.path(0, mask), w, h);
        int clear = 0, fixed = 0;
        long error = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = overlay.getPixel(x,y);
                if (Color.alpha(c) == 0) clear++;
                if (Color.alpha(c) < 250) continue;
                int r = reference.getPixel(x,y);
                error += Math.abs(Color.red(c)-Color.red(r)) + Math.abs(Color.green(c)-Color.green(r))
                        + Math.abs(Color.blue(c)-Color.blue(r));
                fixed++;
            }
        }
        expect(clear > w*h*.45, "Overlay covers the zoom: " + path);
        expect(error <= 30L * fixed, "Overlay differs from calibrated artwork: " + path);
        overlay.recycle();
        reference.recycle();
    }

    private static Bitmap frame(MengerWindows scene, int[] size, double phase) {
        Bitmap image = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        scene.draw(new Canvas(image), size[0], size[1], phase, null);
        return image;
    }

    private static double difference(Bitmap a, Bitmap b) {
        long delta = 0, samples = 0;
        for (int y = 1; y < a.getHeight(); y += 3) {
            for (int x = 1; x < a.getWidth(); x += 3) {
                int p = a.getPixel(x, y), q = b.getPixel(x, y);
                delta += Math.abs(Color.red(p) - Color.red(q)) + Math.abs(Color.green(p) - Color.green(q))
                        + Math.abs(Color.blue(p) - Color.blue(q));
                samples += 3;
            }
        }
        return (double) delta / samples;
    }

    private static void expect(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
