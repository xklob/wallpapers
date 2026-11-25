package io.klob.wallpapers.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.util.Log;
import android.util.LruCache;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;

final class MandelbrotZoom {
    static final String PREFIX = "mandelbrot/";
    private static final double MIN_SCALE = 0.0187;
    private static final double START = -.72, FOCUS = -1.7548776662466927 / (1 - MIN_SCALE);
    private static final float SCENE_SPAN = 3.4f;
    private static final int BASE = 0xff0a2289;
    private static final int PALETTE_SIZE = 4096;
    private static final float PALETTE_MIN_LOG = -40f;
    private static final float PALETTE_LOG_RANGE = 42f;
    private static final double WRAP_START = .90;
    private static final int[] SMALL_LEVELS = {
        R.drawable.mandelbrot_level_0_small, R.drawable.mandelbrot_level_1_small, R.drawable.mandelbrot_level_2_small,
        R.drawable.mandelbrot_level_3_small, R.drawable.mandelbrot_level_4_small, R.drawable.mandelbrot_level_5_small,
        R.drawable.mandelbrot_level_6_small, R.drawable.mandelbrot_level_7_small, R.drawable.mandelbrot_level_8_small,
    };
    private static final int[] LEVELS = {
        R.drawable.mandelbrot_level_0, R.drawable.mandelbrot_level_1, R.drawable.mandelbrot_level_2,
        R.drawable.mandelbrot_level_3, R.drawable.mandelbrot_level_4, R.drawable.mandelbrot_level_5,
        R.drawable.mandelbrot_level_6, R.drawable.mandelbrot_level_7, R.drawable.mandelbrot_level_8,
    };

    // RG stores a 12-bit logarithmic distance, B stores coverage. Raw sampling avoids
    // display color management on the numeric channels. The palette is in sRGB.
    private static final String PROJECTION =
            "uniform shader tile, partner, palette;\n"
            + "uniform float4 region, other;\n"
            + "uniform float4 view;\n"
            + "uniform float3 detail;\n"
            + "uniform float3 lookup;\n"
            + "uniform float phase, gain;\n"
            + "uniform float4 audioWave;\n"
            + "layout(color) uniform half4 base;\n"
            + "\n"
            + "half3 shade(float3 data, float logSpan) {\n"
            + "    float encoded = dot(data.rg, float2(4080, 15));\n"
            + "    float logDistance = -48.0 + (56.0/4095.0) * encoded;\n"
            + "    float index = clamp((logDistance - logSpan - lookup.x) * lookup.y, 0.0, 1.0) * lookup.z;\n"
            + "    return palette.eval(float2(index + .5, .5)).rgb * half(data.b) * half(gain);\n"
            + "}\n"
            + "float3 readTile(float2 p, bool auxiliary) {\n"
            + "    return float3((auxiliary ? partner.eval(p) : tile.eval(p)).rgb);\n"
            + "}\n"
            + "half3 sampleTile(float2 p, bool auxiliary, float4 r, float span) {\n"
            + "    float2 uv = float2(.5 + r.x, .5) + p * r.y;\n"
            + "    if (any(greaterThan(abs(uv - .5), float2(.5)))) return base.rgb * half(gain);\n"
            + "    float2 a = uv * r.z;\n"
            + "    if (detail.z == 0.0) return shade(readTile(a, auxiliary), span);\n"
            + "    // Fractional texture footprints reduce shimmer without blurring the whole field.\n"
            + "    float d = r.y * r.z * .27;\n"
            + "    return (shade(readTile(a + float2(-d, -d), auxiliary), span)\n"
            + "          + shade(readTile(a + float2( d, -d), auxiliary), span)\n"
            + "          + shade(readTile(a + float2(-d,  d), auxiliary), span)\n"
            + "          + shade(readTile(a + float2( d,  d), auxiliary), span)) * .25;\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 p = coord - view.xy;\n"
            + "    if (view.z > .5) p = float2(p.y, -p.x);\n"
            + "    half3 c = sampleTile(p, false, region, detail.x);\n"
            + "    if (view.w > 0.0) c = mix(c, sampleTile(p, true, other, detail.y), half(view.w));\n"
            + "    // Keep the cobalt background and white cores recognizable. Motion affects the halo.\n"
            + "    float band = 1.0 - smoothstep(0.0, .25, abs((coord.x + coord.y) * audioWave.w - audioWave.y));\n"
            + "    float tint = (1.0 - cos(6.2831853 * (phase + audioWave.x * band))) * .5;\n"
            + "    c += half(tint * .09) * max(half(0), c.b - c.r) * half3(1.0, -.12, 0.0);\n"
            + "    return half4(c, 1.0);\n"
            + "}\n";

    private static final Executor loader = Executors.newSingleThreadExecutor();
    private static final Set<Integer> pending = new HashSet<>();
    private static final LruCache<Integer, Texture> textures = new LruCache<Integer, Texture>(72 * 1024) {
        @Override protected int sizeOf(Integer key, Texture texture) { return texture.sizeKiB; }
    };
    private static Bitmap still;
    private static BitmapShader palette;
    private final RuntimeShader projection;
    private final Paint paint = new Paint();
    private Texture overview, current, previous;
    private int sampleSize = 1, lastStage = -1;
    private boolean antialias;
    private double tileReadyPosition;
    private float phase, gain = 1f, wave, travel;
    private Texture mainInput, partnerInput;

    MandelbrotZoom(Context context) {
        RuntimeShader shader = null;
        try {
            RuntimeShader candidate = new RuntimeShader(PROJECTION);
            candidate.setColorUniform("base", BASE);
            candidate.setInputShader("palette", palette());
            candidate.setFloatUniform("lookup", PALETTE_MIN_LOG, 1f / PALETTE_LOG_RANGE, PALETTE_SIZE - 1f);
            shader = candidate;
        } catch (IllegalArgumentException exception) {
            Log.e("RgbGeometryLive", "Could not initialize the Mandelbrot projection", exception);
        }
        projection = shader;
    }

    private static synchronized BitmapShader palette() {
        if (palette == null) {
            // Bake the sRGB palette across log distance once. This saves three
            // pow/exp pairs per GPU sample, or twelve pairs in Ultra mode.
            int[] colors = new int[PALETTE_SIZE];
            for (int i = 0; i < colors.length; ++i) {
                double d = Math.pow(2, PALETTE_MIN_LOG + PALETTE_LOG_RANGE * i / (colors.length - 1));
                double glow = Math.exp(-Math.pow(d / .024, .73));
                double blue = Math.exp(-Math.pow(d / .006, .78));
                double rim = Math.exp(-Math.pow(d / .00115, .80));
                int r = (int)Math.round(10 + 16 * glow + 110 * blue + 119 * rim);
                int g = (int)Math.round(34 + 82 * glow + 105 * blue + 27 * rim);
                int b = (int)Math.round(137 + 100 * glow + 18 * blue - 61 * rim);
                colors[i] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
            palette = new BitmapShader(Bitmap.createBitmap(colors, PALETTE_SIZE, 1, Bitmap.Config.ARGB_8888),
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            palette.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        }
        return palette;
    }

    private static final class Texture {
        final BitmapShader shader;
        final int width, sizeKiB;
        final double center, extent;
        Texture(Bitmap bitmap, int level) {
            width = bitmap.getWidth();
            sizeKiB = bitmap.getAllocationByteCount() / 1024;
            double scale = Math.pow(MIN_SCALE, level / (double) LEVELS.length);
            center = center(scale);
            extent = (level == 0 ? 5.6 : 5.1) * scale;
            shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        }
    }

    private static Texture decode(Context context, int level, int sample) {
        Bitmap image = BitmapFactory.decodeResource(context.getResources(),
                (sample == 2 ? SMALL_LEVELS : LEVELS)[level]);
        return new Texture(image, level);
    }

    private static Texture cached(int level, int sample) {
        return textures.get(2 * level + sample - 1);
    }

    private static void preload(Context context, int level, int sample) {
        int key = 2 * level + sample - 1;
        synchronized (pending) {
            if (textures.get(key) != null || !pending.add(key)) return;
        }
        loader.execute(() -> {
            try {
                textures.put(key, decode(context, level, sample));
            } catch (RuntimeException | OutOfMemoryError exception) {
                Log.w("RgbGeometryLive", "Could not preload Mandelbrot texture " + level, exception);
            } finally {
                synchronized (pending) { pending.remove(key); }
            }
        });
    }

    void setQuality(int quality) {
        int sample = quality == 0 ? 2 : 1;
        antialias = quality == 2;
        if (sampleSize == sample) return;
        sampleSize = sample;
        overview = current = previous = null;
        lastStage = -1;
    }

    void setColor(float phase, float gain, float wave, float travel) {
        this.phase = phase;
        this.gain = gain;
        this.wave = wave;
        this.travel = travel;
    }

    static boolean uses(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    boolean isAvailable() {
        return projection != null;
    }

    void draw(Context context, Canvas canvas, int width, int height, double phase) {
        if (projection == null || !canvas.isHardwareAccelerated()) {
            drawStill(context, canvas, width, height);
            return;
        }
        if (overview == null) {
            overview = cached(0, sampleSize);
            if (overview == null) {
                overview = decode(context, 0, sampleSize);
                textures.put(sampleSize - 1, overview);
            }
        }
        double position = phase * LEVELS.length;
        int stage = Math.min((int) position, LEVELS.length - 1);
        double scale = Math.pow(MIN_SCALE, phase);
        if (lastStage != stage) {
            previous = lastStage == stage - 1 ? current : null;
            current = null;
            lastStage = stage;
        }
        if (current == null) {
            current = stage == 0 ? overview : cached(stage, sampleSize);
            if (current == null) preload(context, stage, sampleSize);
            else tileReadyPosition = position;
        }
        if (stage + 1 < LEVELS.length) preload(context, stage + 1, sampleSize);
        if (current != null && position - tileReadyPosition >= .20) previous = null;
        Texture display = current == null ? (previous == null ? overview : previous) : current;
        Texture blendTo = display;
        double alternateScale = scale;
        float blend = 0;
        float base = sceneSpan(width, height);
        if (phase > WRAP_START) {
            blendTo = overview;
            alternateScale = wrapScale(phase, base);
            blend = ease((phase - WRAP_START) / (1 - WRAP_START));
        } else if (previous != null && display != previous) {
            blendTo = previous;
            blend = 1 - ease((position - tileReadyPosition) / .20);
        }
        float length = Math.max(width, height);
        if (mainInput != display) {
            projection.setInputBuffer("tile", display.shader);
            mainInput = display;
        }
        if (partnerInput != blendTo) {
            projection.setInputBuffer("partner", blendTo.shader);
            partnerInput = blendTo;
        }
        map("region", display, base, length, scale);
        map("other", blendTo, base, length, alternateScale);
        projection.setFloatUniform("view", width / 2f, height / 2f, turned(width, height) ? 1 : 0, blend);
        projection.setFloatUniform("detail", log2(SCENE_SPAN * scale),
                log2(SCENE_SPAN * alternateScale), antialias ? 1 : 0);
        projection.setFloatUniform("phase", this.phase);
        projection.setFloatUniform("gain", gain);
        projection.setFloatUniform("audioWave", wave, travel, 0, 1f / (width + height));
        paint.setShader(projection);
        canvas.drawRect(0, 0, width, height, paint);
    }

    private void map(String uniform, Texture texture, float base, float length, double scale) {
        projection.setFloatUniform(uniform, (float)((center(scale) - texture.center) / texture.extent),
                (float)(base * scale / (length * texture.extent)), texture.width, 0);
    }

    static double wrapScale(double phase, float span) {
        // Keep the incoming overview inside its tile even on the tallest panels,
        // then match the incoming camera's speed at the end of the dissolve.
        double room = (2.79 + START - FOCUS) / (.5 * span + START - FOCUS) - 1;
        return 1 - room * Math.expm1(Math.log(MIN_SCALE) * (1 - phase) / room);
    }

    private static float log2(double x) {
        return (float)(Math.log(x) / Math.log(2));
    }

    private static float ease(double t) {
        return (float)(t * t * (3 - 2 * t));
    }

    private static double center(double scale) {
        return FOCUS + (START - FOCUS) * scale;
    }

    private static boolean turned(int width, int height) {
        return width < height;
    }

    static float sceneSpan(int width, int height) {
        float aspect = Math.max(width, height) / (float)Math.min(width, height);
        return Math.max(SCENE_SPAN, Math.min(5.1f, 1.92f * aspect));
    }

    private static synchronized Bitmap still(Context context) {
        if (still == null) still = BitmapFactory.decodeResource(context.getResources(), R.drawable.mandelbrot_still);
        return still;
    }

    static void drawStill(Context context, Canvas canvas, int width, int height) {
        canvas.drawColor(BASE);
        float half = 2.8f * Math.max(width, height) / sceneSpan(width, height);
        canvas.save();
        canvas.translate(width / 2f, height / 2f);
        if (turned(width, height)) canvas.rotate(90);
        canvas.drawBitmap(still(context), null, new RectF(-half, -half, half, half),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        canvas.restore();
    }
}
