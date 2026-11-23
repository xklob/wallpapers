package io.klob.wallpapers.watch.tesseract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact port of Poly's 6-solid geometry and morph choreography from Codex CLI:
 * Cube -> Cuboctahedron -> Octahedron -> Icosahedron -> Tetrahedron -> Truncated tetrahedron -> Cube.
 *
 * Cube/cuboctahedron/octahedron and tetrahedron/truncated-tetrahedron transitions use
 * true geometric truncation; other transitions use Poly's core-fold contraction/rebuild.
 */
final class PolyModel {
    private static final double EPS = 1e-6;
    private static final int MORPH_STEPS = 18;
    static final int NUM_SHAPES = 6;
    static final float HOLD_SECONDS = 1.20f;
    static final float MORPH_SECONDS = 0.25f;
    static final float STAGE_SECONDS = HOLD_SECONDS + MORPH_SECONDS;

    static final class Mesh {
        final float[][] vertices;
        final float[][] faceNormals;
        final int[] edgeA;
        final int[] edgeB;
        final int[] edgeFace0;
        final int[] edgeFace1;

        Mesh(float[][] vertices, float[][] faceNormals,
                int[] edgeA, int[] edgeB, int[] edgeFace0, int[] edgeFace1) {
            this.vertices = vertices;
            this.faceNormals = faceNormals;
            this.edgeA = edgeA;
            this.edgeB = edgeB;
            this.edgeFace0 = edgeFace0;
            this.edgeFace1 = edgeFace1;
        }
    }

    static final class FrameState {
        Mesh mesh;
        float scale;
    }

    private static final double[][] CUBE;
    private static final double[][] OCTA = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final double[][] TETRA = {
            {0, 1, 0},
            {-Math.sqrt(2.0 / 3.0), -1.0 / 3.0, Math.sqrt(2.0) / 3.0},
            {Math.sqrt(2.0 / 3.0), -1.0 / 3.0, Math.sqrt(2.0) / 3.0},
            {0, -1.0 / 3.0, -2.0 * Math.sqrt(2.0) / 3.0}
    };
    private static final double[][] ICO;

    static {
        List<double[]> c = new ArrayList<>();
        double[] signs = {-1.0, 1.0};
        for (double x : signs) {
            for (double y : signs) {
                for (double z : signs) {
                    c.add(new double[] {x, y, z});
                }
            }
        }
        CUBE = c.toArray(new double[0][]);

        double phi = (1.0 + Math.sqrt(5.0)) / 2.0;
        List<double[]> ic = new ArrayList<>();
        for (double a : signs) {
            for (double b : signs) {
                ic.add(new double[] {0, a, b * phi});
                ic.add(new double[] {a, b * phi, 0});
                ic.add(new double[] {b * phi, 0, a});
            }
        }
        ICO = ic.toArray(new double[0][]);
    }

    // 0: cube, 1: cuboctahedron, 2: octahedron, 3: icosahedron, 4: tetrahedron, 5: truncated-tetrahedron
    private final Mesh[] baseMeshes = new Mesh[NUM_SHAPES];
    private final Mesh[] cubeToCubocta = new Mesh[MORPH_STEPS];
    private final Mesh[] cuboctaToOcta = new Mesh[MORPH_STEPS];
    private final Mesh[] tetraToTrunc = new Mesh[MORPH_STEPS];

    PolyModel() {
        baseMeshes[0] = cutCube(3.0);
        baseMeshes[1] = cutCube(2.0);
        baseMeshes[2] = cutCube(1.0);
        baseMeshes[3] = hull(normalize(ICO));
        baseMeshes[4] = cutTetra(0.0);
        baseMeshes[5] = cutTetra(1.0 / 3.0);

        for (int i = 0; i < MORPH_STEPS; i++) {
            double progress = (i + 1.0) / (MORPH_STEPS + 1.0);
            double t = ease(progress);
            cubeToCubocta[i] = cutCube(3.0 - t);
            cuboctaToOcta[i] = cutCube(2.0 - t);
            tetraToTrunc[i] = cutTetra(t / 3.0);
        }
    }

    void sample(float timelineSec, FrameState out) {
        float cycleSec = NUM_SHAPES * STAGE_SECONDS;
        float pos = ((timelineSec % cycleSec) + cycleSec) % cycleSec;
        int stage = Math.min(NUM_SHAPES - 1, (int) (pos / STAGE_SECONDS));
        float inStage = pos - stage * STAGE_SECONDS;

        if (inStage < HOLD_SECONDS) {
            out.mesh = baseMeshes[stage];
            // Poly's subtle hold breath
            float holdFrac = inStage / HOLD_SECONDS;
            out.scale = 1.0f + 0.015f * (float) Math.sin(holdFrac * Math.PI * 2.0);
            return;
        }

        float morphProgress = Math.max(0f, Math.min(1f, (inStage - HOLD_SECONDS) / MORPH_SECONDS));
        int nextStage = (stage + 1) % NUM_SHAPES;

        if (stage == 0) {
            out.mesh = pickStep(cubeToCubocta, morphProgress);
            out.scale = 1.0f;
        } else if (stage == 1) {
            out.mesh = pickStep(cuboctaToOcta, morphProgress);
            out.scale = 1.0f;
        } else if (stage == 4) {
            out.mesh = pickStep(tetraToTrunc, morphProgress);
            out.scale = 1.0f;
        } else {
            // Core-fold: physical contraction and rebuild around the persistent core
            double t = ease(morphProgress);
            boolean arriving = t >= 0.5;
            double p = arriving ? (t - 0.5) * 2.0 : 1.0 - t * 2.0;
            out.mesh = baseMeshes[arriving ? nextStage : stage];
            out.scale = (float) (0.12 + 0.88 * p);
        }
    }

    private static Mesh pickStep(Mesh[] steps, float progress) {
        int idx = Math.round(progress * (steps.length - 1));
        if (idx < 0) idx = 0;
        if (idx >= steps.length) idx = steps.length - 1;
        return steps[idx];
    }

    private static double ease(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static Mesh cutCube(double t) {
        double[][] verts;
        double[] signs = {-1.0, 1.0};
        if (t >= 3.0 - EPS) {
            verts = CUBE;
        } else if (t <= 1.0 + EPS) {
            verts = OCTA;
        } else if (t >= 2.0) {
            List<double[]> raw = new ArrayList<>();
            for (double x : signs) {
                for (double y : signs) {
                    for (double z : signs) {
                        addPermutations(raw, x, y, z * (t - 2.0));
                    }
                }
            }
            verts = unique(raw);
        } else {
            List<double[]> raw = new ArrayList<>();
            for (double x : signs) {
                for (double y : signs) {
                    addPermutations(raw, x, y * (t - 1.0), 0.0);
                }
            }
            verts = unique(raw);
        }
        return hull(normalize(verts));
    }

    private static Mesh cutTetra(double t) {
        double[][] verts;
        if (t < EPS) {
            verts = TETRA;
        } else {
            List<double[]> raw = new ArrayList<>();
            for (int i = 0; i < TETRA.length; i++) {
                for (int j = 0; j < TETRA.length; j++) {
                    if (i != j) {
                        raw.add(lerp(TETRA[i], TETRA[j], t));
                    }
                }
            }
            verts = unique(raw);
        }
        return hull(normalize(verts));
    }

    private static void addPermutations(List<double[]> out, double a, double b, double c) {
        out.add(new double[] {a, b, c});
        out.add(new double[] {a, c, b});
        out.add(new double[] {b, a, c});
        out.add(new double[] {b, c, a});
        out.add(new double[] {c, a, b});
        out.add(new double[] {c, b, a});
    }

    private static double[][] unique(List<double[]> list) {
        Map<String, double[]> map = new LinkedHashMap<>();
        for (double[] v : list) {
            String key = Math.round(v[0] / EPS) + "," + Math.round(v[1] / EPS) + "," + Math.round(v[2] / EPS);
            if (!map.containsKey(key)) map.put(key, v);
        }
        return map.values().toArray(new double[0][]);
    }

    private static double[][] normalize(double[][] vs) {
        double maxR = 0.0;
        for (double[] v : vs) {
            maxR = Math.max(maxR, len(v));
        }
        double inv = maxR > EPS ? 1.0 / maxR : 1.0;
        double[][] out = new double[vs.length][3];
        for (int i = 0; i < vs.length; i++) {
            out[i][0] = vs[i][0] * inv;
            out[i][1] = vs[i][1] * inv;
            out[i][2] = vs[i][2] * inv;
        }
        return out;
    }

    private static Mesh hull(double[][] vertices) {
        int n = vertices.length;
        Map<String, int[]> faceIdsMap = new LinkedHashMap<>();
        Map<String, double[]> faceNormalMap = new LinkedHashMap<>();

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    double[] normal = cross(sub(vertices[j], vertices[i]), sub(vertices[k], vertices[i]));
                    double nl = len(normal);
                    if (nl < EPS) continue;
                    normal = mul(normal, 1.0 / nl);
                    double d = dot(normal, vertices[i]);

                    boolean hasPos = false;
                    boolean hasNeg = false;
                    for (int m = 0; m < n; m++) {
                        double dist = dot(normal, vertices[m]) - d;
                        if (dist > EPS) hasPos = true;
                        else if (dist < -EPS) hasNeg = true;
                        if (hasPos && hasNeg) break;
                    }
                    if (hasPos && hasNeg) continue;
                    if (hasPos) {
                        normal = mul(normal, -1.0);
                        d = -d;
                    }

                    List<Integer> onPlane = new ArrayList<>();
                    for (int m = 0; m < n; m++) {
                        if (Math.abs(dot(normal, vertices[m]) - d) < EPS) {
                            onPlane.add(m);
                        }
                    }
                    if (onPlane.size() < 3) continue;

                    StringBuilder keyBuilder = new StringBuilder();
                    for (int idx = 0; idx < onPlane.size(); idx++) {
                        if (idx > 0) keyBuilder.append(',');
                        keyBuilder.append(onPlane.get(idx));
                    }
                    String key = keyBuilder.toString();
                    if (faceIdsMap.containsKey(key)) continue;

                    double[] center = new double[3];
                    for (int id : onPlane) {
                        center[0] += vertices[id][0];
                        center[1] += vertices[id][1];
                        center[2] += vertices[id][2];
                    }
                    center = mul(center, 1.0 / onPlane.size());
                    double[] u = unit(sub(vertices[onPlane.get(0)], center));
                    double[] v = cross(normal, u);

                    final double[] fCenter = center;
                    final double[] fU = u;
                    final double[] fV = v;
                    onPlane.sort(Comparator.comparingDouble(id -> {
                        double[] rel = sub(vertices[id], fCenter);
                        return Math.atan2(dot(rel, fV), dot(rel, fU));
                    }));

                    int[] ordered = new int[onPlane.size()];
                    for (int idx = 0; idx < ordered.length; idx++) ordered[idx] = onPlane.get(idx);
                    faceIdsMap.put(key, ordered);
                    faceNormalMap.put(key, normal);
                }
            }
        }

        List<int[]> faces = new ArrayList<>(faceIdsMap.values());
        List<double[]> normals = new ArrayList<>(faceNormalMap.values());

        Map<Long, int[]> edgeMap = new LinkedHashMap<>();
        for (int fi = 0; fi < faces.size(); fi++) {
            int[] ids = faces.get(fi);
            for (int i = 0; i < ids.length; i++) {
                int a = ids[i];
                int b = ids[(i + 1) % ids.length];
                int lo = Math.min(a, b);
                int hi = Math.max(a, b);
                long eKey = (((long) lo) << 32) | (hi & 0xffffffffL);
                int[] entry = edgeMap.get(eKey);
                if (entry == null) {
                    edgeMap.put(eKey, new int[] {lo, hi, fi, -1});
                } else if (entry[3] == -1) {
                    entry[3] = fi;
                }
            }
        }

        float[][] vOut = new float[n][3];
        for (int i = 0; i < n; i++) {
            vOut[i][0] = (float) vertices[i][0];
            vOut[i][1] = (float) vertices[i][1];
            vOut[i][2] = (float) vertices[i][2];
        }
        float[][] nOut = new float[normals.size()][3];
        for (int i = 0; i < normals.size(); i++) {
            double[] nm = normals.get(i);
            nOut[i][0] = (float) nm[0];
            nOut[i][1] = (float) nm[1];
            nOut[i][2] = (float) nm[2];
        }

        int ec = edgeMap.size();
        int[] eA = new int[ec];
        int[] eB = new int[ec];
        int[] eF0 = new int[ec];
        int[] eF1 = new int[ec];
        int ei = 0;
        for (int[] entry : edgeMap.values()) {
            eA[ei] = entry[0];
            eB[ei] = entry[1];
            eF0[ei] = entry[2];
            eF1[ei] = entry[3] >= 0 ? entry[3] : entry[2];
            ei++;
        }

        return new Mesh(vOut, nOut, eA, eB, eF0, eF1);
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] mul(double[] a, double s) {
        return new double[] {a[0] * s, a[1] * s, a[2] * s};
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[] {
                a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t
        };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static double len(double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    private static double[] unit(double[] a) {
        double l = len(a);
        return l > EPS ? mul(a, 1.0 / l) : new double[] {1, 0, 0};
    }
}
