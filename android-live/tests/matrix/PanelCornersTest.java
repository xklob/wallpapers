package io.klob.wallpapers.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.RoundedCorner;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class PanelCornersTest {
    static void run(Context context) throws Exception {
        checkPaths();
        Catalog catalog = Catalog.get(context);
        Catalog.Model model = catalog.lookup("galaxy_s25");
        RoundedCorner[] none = new RoundedCorner[4];
        PanelCorners panel = new PanelCorners(1080, 2340, none);
        require(panel.matches(model, "SM-S931U1", "samsung", 180, 390), "matching scaled preview");
        require(!panel.matches(model, "SM-S911U1", "samsung", 180, 390), "unrelated phone with same resolution");
        require(!panel.matches(model, "SM-S931U1", "samsung", 390, 180), "opposite preview orientation");
        require(!panel.matches(catalog.lookup("pixel_9_pro_fold_inner_portrait"), "Pixel 9 Pro Fold", "Google",
                1080, 2340), "foldable inner and cover panel");
        Catalog.Model portrait = catalog.lookup("pixel_tablet_portrait");
        Catalog.Model landscape = catalog.lookup("pixel_tablet_landscape");
        require(catalog.forOrientation(landscape, false) == portrait && catalog.forOrientation(portrait, true) == landscape,
                "rotated tablet should resolve sibling artwork");
        // Make sure the packaged frame really carries all five replacement targets.
        String path = model.path(0, Catalog.BORDER);
        try (InputStream stream = context.getAssets().open(path)) {
            InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            StringBuilder svg = new StringBuilder();
            char[] buf = new char[4096];
            int count;
            while ((count = reader.read(buf)) >= 0) svg.append(buf, 0, count);
            if (svg.indexOf("data-device-frame=") >= 0) {
                String fitted = panel.fit(svg.toString(), false);
                require(!fitted.contains("id=\"device-"), "packaged clip and all sides are fitted");
                checkAndroidRendering(context, path);
            }
        }
    }

    private static void checkAndroidRendering(Context context, String asset) throws Exception {
        // Exercise both public render paths on any Android test device with a simulated matching panel.
        Catalog.Model hardware = new Catalog.Model(new JSONObject().put("id", "test").put("name", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER).put("family", "Phones").put("width", 1080)
                .put("height", 2340).put("variants", new JSONObject()));
        PanelCorners panel = new PanelCorners(1080, 2340, new RoundedCorner[]{
                new RoundedCorner(0, 320, 320, 320), null, null, null});
        for (boolean regular : new boolean[]{false, true}) {
            Bitmap baseline = regular ? WallpaperArt.renderStatic(context, asset, 360, 780)
                    : WallpaperArt.render(context, asset, 360, 780);
            Bitmap fitted = regular ? WallpaperArt.renderStatic(context, asset, 360, 780, hardware, panel)
                    : WallpaperArt.render(context, asset, 360, 780, 1f, hardware, panel);
            int differences = 0;
            for (int y = 0; y < 155; y++) {
                for (int x = 0; x < 140; x++) {
                    if (fitted.getPixel(x, y) != baseline.getPixel(x, y)) differences++;
                }
            }
            require(differences > 200, "renderer applies the measured top-left curve");
            for (int y = 155; y < fitted.getHeight() - 120; y += 9) {
                for (int x = 0; x < fitted.getWidth(); x += 9) {
                    require(fitted.getPixel(x, y) == baseline.getPixel(x, y), "center and straight edges preserved");
                }
            }
            baseline.recycle();
            fitted.recycle();
        }
    }

    static void checkPaths() {
        // A 100 x 200 panel with a 5px authored gutter and unequal physical radii.
        PanelCorners panel = new PanelCorners(100, 200, new RoundedCorner[]{
                new RoundedCorner(0, 25, 25, 25), new RoundedCorner(1, 15, 85, 15),
                null, new RoundedCorner(3, 4, 4, 196)});
        String input = "<svg data-device-frame=\"0.05 0.025 0.9 0.95 0.1 0.1 5 5 90 190 5 5 90 190\">"
                + "<rect id=\"device-clip\"/><path id=\"device-top\" d=\"old\" stroke=\"red\"/>"
                + "<path id=\"device-right\" d=\"old\"/><path id=\"device-bottom\" d=\"old\"/>"
                + "<path id=\"device-left\" d=\"old\"/><rect id=\"launcher\"/></svg>";
        String fitted = panel.fit(input, false);
        require(fitted.contains("A20.0,20.0 0 0 1 25.0,5.0L85.0,5.0A10.0,10.0"), "top radii and original gutter");
        require(fitted.contains("A9.0,19.0"), "missing bottom-right report preserves its authored radius");
        require(!fitted.contains("device-clip") && !fitted.contains("d=\"old\"")
                && fitted.contains("id=\"launcher\"") && fitted.contains("stroke=\"red\""), "only frame shapes change");
        require(panel.fit("<svg/>", false).equals("<svg/>"), "published silhouette is kept");
        PanelCorners rotated = new PanelCorners(200, 100, new RoundedCorner[]{
                new RoundedCorner(0, 15, 15, 15), null, new RoundedCorner(2, 4, 196, 96),
                new RoundedCorner(3, 25, 25, 75)});
        require(rotated.fit(input, false).equals(fitted), "rotate all four physical corner reports back into artwork space");
    }

    private static void require(boolean ok, String detail) {
        if (!ok) throw new AssertionError(detail);
    }
}
