package com.example.arpiagrama.visualprocessing.infrastructure;


import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class YoloV8TfliteDetector {

    public static class Det {
        public final RectF box;      // x1,y1,x2,y2 em pixels (na ROI)
        public final int classId;
        public final float score;
        public final String label;

        public Det(RectF box, int classId, float score, String label) {
            this.box = box;
            this.classId = classId;
            this.score = score;
            this.label = label;
        }
    }

    private final Interpreter interpreter;
    private final List<String> labels;

    private final int inW, inH;
    private final float confThres;
    private final float iouThres;

    private final ByteBuffer inputBuffer;
    private float[][][] output3; // usado quando output é float32 e 3D

    public YoloV8TfliteDetector(Context ctx,
                                String modelAssetName,
                                String yamlAssetName,
                                float confThres,
                                float iouThres,
                                int numThreads) throws IOException {

        this.labels = loadLabelsFromYaml(ctx.getAssets(), yamlAssetName);
        this.confThres = confThres;
        this.iouThres = iouThres;

        Interpreter.Options opt = new Interpreter.Options();
        opt.setNumThreads(Math.max(1, numThreads));
        this.interpreter = new Interpreter(loadModelFile(ctx.getAssets(), modelAssetName), opt);

        int[] inShape = interpreter.getInputTensor(0).shape();  // [1,H,W,3]
        this.inH = inShape[1];
        this.inW = inShape[2];

        // input float32: 1*H*W*3*4 bytes
        this.inputBuffer = ByteBuffer.allocateDirect(1 * inH * inW * 3 * 4);
        this.inputBuffer.order(ByteOrder.nativeOrder());

        Log.i("YoloV8TfliteDetector", "Loaded model. in=" + inW + "x" + inH + " labels=" + labels.size());
    }

    /** Detecta no bitmap da ROI e retorna boxes em pixels (na ROI). */
    public List<Det> detect(Bitmap roiBitmap) {
        if (roiBitmap == null) return Collections.emptyList();

        // 1) Preprocess
        Bitmap resized = Bitmap.createScaledBitmap(roiBitmap, inW, inH, true);
        inputBuffer.rewind();

        int[] pixels = new int[inW * inH];
        resized.getPixels(pixels, 0, inW, 0, 0, inW, inH);

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            float r = ((p >> 16) & 0xFF) / 255.0f;
            float g = ((p >> 8) & 0xFF) / 255.0f;
            float b = (p & 0xFF) / 255.0f;
            inputBuffer.putFloat(r);
            inputBuffer.putFloat(g);
            inputBuffer.putFloat(b);
        }

        // 2) Inferência
        Object output = allocateOutputIfNeeded();
        interpreter.run(inputBuffer, output);

        // 3) Decodificar
        List<Det> dets = decode(output, roiBitmap.getWidth(), roiBitmap.getHeight());

        // 4) NMS
        return nms(dets, iouThres);
    }

    // -------------------- decode + helpers --------------------

    /**
     * YOLOv8 TFLite comumente sai como:
     *  - [1, (4+nc), N]  ou
     *  - [1, N, (4+nc)]
     * Algumas exports têm objectness:
     *  - (5+nc)
     */
    private List<Det> decode(Object outObj, int w0, int h0) {
        float[][][] out = (float[][][]) outObj;

        int d1 = out[0].length;
        int d2 = out[0][0].length;

        // Vamos padronizar para [N, C]
        // Caso A: [1, C, N] => C=d1, N=d2
        // Caso B: [1, N, C] => N=d1, C=d2
        boolean isCN = d1 < d2; // heurística comum
        int C = isCN ? d1 : d2;
        int N = isCN ? d2 : d1;

        int nc = labels.size();
        boolean hasObj = (C == 5 + nc);
        boolean noObj  = (C == 4 + nc);

        if (!hasObj && !noObj) {
            Log.w("YoloV8TfliteDetector", "Formato inesperado: C=" + C + " N=" + N + " nc=" + nc);
        }

        List<Det> results = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            float xc, yc, bw, bh;

            if (isCN) {
                xc = out[0][0][i];
                yc = out[0][1][i];
                bw = out[0][2][i];
                bh = out[0][3][i];
            } else {
                xc = out[0][i][0];
                yc = out[0][i][1];
                bw = out[0][i][2];
                bh = out[0][i][3];
            }

            float obj = 1f;
            int clsStart = 4;

            if (hasObj) {
                obj = isCN ? out[0][4][i] : out[0][i][4];
                clsStart = 5;
            }

            // max class
            int bestCid = -1;
            float bestScore = 0f;

            for (int c = 0; c < nc; c++) {
                float cs = isCN ? out[0][clsStart + c][i] : out[0][i][clsStart + c];
                float score = cs * obj;
                if (score > bestScore) {
                    bestScore = score;
                    bestCid = c;
                }
            }

            if (bestCid < 0 || bestScore < confThres) continue;

            // xywh normalizado -> xyxy pixel na ROI
            float x1 = (xc - bw / 2f) * w0;
            float y1 = (yc - bh / 2f) * h0;
            float x2 = (xc + bw / 2f) * w0;
            float y2 = (yc + bh / 2f) * h0;

            // clamp
            x1 = clamp(x1, 0, w0 - 1);
            y1 = clamp(y1, 0, h0 - 1);
            x2 = clamp(x2, 0, w0 - 1);
            y2 = clamp(y2, 0, h0 - 1);

            String label = (bestCid < labels.size()) ? labels.get(bestCid) : ("class_" + bestCid);
            results.add(new Det(new RectF(x1, y1, x2, y2), bestCid, bestScore, label));
        }

        // ordena por score desc
        results.sort((a, b) -> Float.compare(b.score, a.score));
        return results;
    }

    private List<Det> nms(List<Det> dets, float iouThres) {
        List<Det> picked = new ArrayList<>();
        boolean[] removed = new boolean[dets.size()];

        for (int i = 0; i < dets.size(); i++) {
            if (removed[i]) continue;
            Det a = dets.get(i);
            picked.add(a);

            for (int j = i + 1; j < dets.size(); j++) {
                if (removed[j]) continue;
                Det b = dets.get(j);

                // NMS class-agnóstico (igual seu script). Se quiser por-classe: if (a.classId != b.classId) continue;
                if (iou(a.box, b.box) >= iouThres) {
                    removed[j] = true;
                }
            }
        }
        return picked;
    }

    private float iou(RectF a, RectF b) {
        float xx1 = Math.max(a.left, b.left);
        float yy1 = Math.max(a.top, b.top);
        float xx2 = Math.min(a.right, b.right);
        float yy2 = Math.min(a.bottom, b.bottom);

        float w = Math.max(0f, xx2 - xx1);
        float h = Math.max(0f, yy2 - yy1);

        float inter = w * h;
        float areaA = Math.max(0f, a.right - a.left) * Math.max(0f, a.bottom - a.top);
        float areaB = Math.max(0f, b.right - b.left) * Math.max(0f, b.bottom - b.top);

        float union = areaA + areaB - inter;
        return union <= 0f ? 0f : (inter / union);
    }

    private float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private Object allocateOutputIfNeeded() {
        int[] outShape = interpreter.getOutputTensor(0).shape();
        // Geralmente [1, C, N] ou [1, N, C]
        if (output3 == null ||
                output3.length != outShape[0] ||
                output3[0].length != outShape[1] ||
                output3[0][0].length != outShape[2]) {

            output3 = new float[outShape[0]][outShape[1]][outShape[2]];
        }
        return output3;
    }

    // -------------------- assets loaders --------------------

    private ByteBuffer loadModelFile(AssetManager am, String assetName) throws IOException {
        // Lê o asset inteiro em memória (ok para modelos “pequenos/médios”)
        // Se quiser mais eficiente: FileUtil.loadMappedFile (tflite-support).
        byte[] bytes = new byte[am.open(assetName).available()];
        int read = am.open(assetName).read(bytes);
        ByteBuffer bb = ByteBuffer.allocateDirect(bytes.length);
        bb.order(ByteOrder.nativeOrder());
        bb.put(bytes);
        bb.rewind();
        return bb;
    }

    /** Parse mínimo para data.yaml do Ultralytics (names + nc). */
    private List<String> loadLabelsFromYaml(AssetManager am, String yamlAssetName) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(am.open(yamlAssetName)));
        String line;
        boolean inNames = false;
        int sequentialId = 0;

        HashMap<Integer, String> map = new HashMap<>();
        int maxId = -1;

        while ((line = br.readLine()) != null) {
            String t = line.trim();

            if (t.startsWith("names:")) {
                inNames = true;
                continue;
            }
            if (inNames) {
                if (t.isEmpty()) {
                    continue;
                }

                // Formato em lista do Ultralytics:
                // names:
                // - Ator
                // - Caso de uso
                if (t.startsWith("-")) {
                    String value = t.substring(1).trim();
                    if (!value.isEmpty()) {
                        map.put(sequentialId, value.replace("\"", "").replace("'", ""));
                        maxId = Math.max(maxId, sequentialId);
                        sequentialId++;
                    }
                    continue;
                }

                // Formato com índice explícito:
                // 0: Ator
                int idx = t.indexOf(':');
                if (idx > 0) {
                    String left = t.substring(0, idx).trim();
                    String right = t.substring(idx + 1).trim();
                    try {
                        int id = Integer.parseInt(left);
                        map.put(id, right.replace("\"", "").replace("'", ""));
                        maxId = Math.max(maxId, id);
                        sequentialId = Math.max(sequentialId, id + 1);
                        continue;
                    } catch (NumberFormatException ignore) {
                        // Entrou em outro bloco do YAML. Finaliza parsing de names.
                        inNames = false;
                    }
                } else {
                    // Entrou em outro bloco do YAML. Finaliza parsing de names.
                    inNames = false;
                }
            }
        }
        br.close();

        ArrayList<String> labels = new ArrayList<>();
        for (int i = 0; i <= maxId; i++) labels.add(map.getOrDefault(i, "class_" + i));
        return labels;
    }
}
