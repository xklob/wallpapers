package io.klob.wallpapers.live;

import android.content.Context;
import android.os.Build;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Catalog {
    static final int BORDER = 8;
    static final int STATUS = 4;
    static final int DOCK = 2;
    static final int CAMERA = 1;

    static final class Model {
        final String id, name, manufacturer, family;
        final int width, height;
        final boolean estimatedCamera;
        private final JSONObject variants;
        private final String hardwareName;
        private final String hardwareId;
        private final List<String> hardwareModels = new ArrayList<>();

        Model(JSONObject object) throws JSONException {
            id = object.getString("id");
            name = object.getString("name");
            manufacturer = object.getString("manufacturer");
            family = object.getString("family");
            width = object.getInt("width");
            height = object.getInt("height");
            variants = object.getJSONObject("variants");
            estimatedCamera = "estimated".equals(object.optString("cameraAccuracy"));
            // Foldable and tablet entries identify the active screen in the model name.
            hardwareName = hardwareName(name);
            hardwareId = hardwareName(id);
            JSONArray codes = object.optJSONArray("hardwareModels");
            if (codes != null) {
                for (int i = 0; i < codes.length(); i++) hardwareModels.add(normalize(codes.getString(i)));
            }
        }

        boolean matches(String hardware) {
            String model = normalize(hardware);
            return hardwareName.equals(model) || hardwareId.equals(model) || hardwareModels.contains(model);
        }

        private static String hardwareName(String name) {
            return normalize(name).replaceFirst("(inner|cover)?(portrait|landscape)$", "");
        }

        boolean supports(int pattern, int style, int flag) {
            return variants.has(pattern + "-" + (style | flag));
        }

        String path(int pattern, int requested) {
            int style = requested & BORDER;
            int supported = style;
            for (int flag : new int[]{CAMERA, DOCK, STATUS}) {
                if ((requested & flag) != 0 && supports(pattern, style, flag)) supported |= flag;
            }
            String key = pattern + "-" + supported;
            String path = variants.optString(key);
            if (path.isEmpty()) throw new IllegalStateException("Missing catalog variant " + id + "/" + key);
            return path;
        }
    }

    final List<Model> models;
    private final Map<String, Model> byId = new HashMap<>();
    private static Catalog instance;

    private Catalog(Context context) throws IOException, JSONException {
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("catalog.json"), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) != -1) json.append(buffer, 0, n);
        }
        JSONArray rows = new JSONArray(json.toString());
        List<Model> entries = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            Model model = new Model(rows.getJSONObject(i));
            byId.put(model.id, model);
            entries.add(model);
        }
        if (entries.isEmpty()) throw new JSONException("Empty wallpaper catalog");
        models = Collections.unmodifiableList(entries);
    }

    static synchronized Catalog get(Context context) {
        if (instance == null) {
            try {
                instance = new Catalog(context);
            } catch (IOException | JSONException e) {
                throw new IllegalStateException("Cannot read bundled wallpaper catalog", e);
            }
        }
        return instance;
    }

    Model lookup(String id) {
        return byId.get(id);
    }

    Model forOrientation(Model selected, boolean landscape) {
        if (!selected.id.startsWith("pixel_tablet_") || landscape == (selected.width > selected.height))
            return selected;
        Model rotated = lookup(landscape ? "pixel_tablet_landscape" : "pixel_tablet_portrait");
        return rotated == null ? selected : rotated;
    }

    Model initialModel(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        Point size = new Point(metrics.widthPixels, metrics.heightPixels);
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (display != null) display.getRealSize(size);
        return initialModel(Build.MODEL, Build.MANUFACTURER, size.x, size.y);
    }

    Model initialModel(String hardware, String manufacturer, int width, int height) {
        int bestRank = Integer.MAX_VALUE;
        double bestScore = Double.POSITIVE_INFINITY;
        Model closest = null;
        for (Model model : models) {
            int rank = model.matches(hardware) ? 0 : model.manufacturer.equalsIgnoreCase(manufacturer) ? 1 : 2;
            double widthRatio = model.width / (double) Math.max(1, width);
            double heightRatio = model.height / (double) Math.max(1, height);
            double score = 2 * Math.abs(Math.log(widthRatio / heightRatio))
                    + Math.abs(Math.log(widthRatio * heightRatio));
            if (rank < bestRank || (rank == bestRank && score < bestScore)) {
                closest = model;
                bestRank = rank;
                bestScore = score;
            }
        }
        return closest != null ? closest : models.get(0);
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace("+", "plus").replaceAll("[^a-z0-9]", "");
    }
}
