package com.example.arpiagrama.visualprocessing.infrastructure;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HandDetectorHelper {
    private static final String TAG = "HandDetectorHelper";
    private static final String MODEL_ASSET = "hand_landmarker.task";
    private static final int DEFAULT_NUM_HANDS = 2;
    private static final float MIN_HAND_CONFIDENCE = 0.25f;
    private static final int[] FINGERTIP_LANDMARKS = new int[]{8, 12, 16, 20};

    private final Context appContext;
    private HandLandmarker handLandmarker;

    public static class HandOcclusion {
        private final RectF handBox;
        private final RectF occlusionBox;
        private final float wristX;
        private final float wristY;
        private final float indexTipX;
        private final float indexTipY;

        public HandOcclusion(RectF handBox,
                             RectF occlusionBox,
                             float wristX,
                             float wristY,
                             float indexTipX,
                             float indexTipY) {
            this.handBox = handBox;
            this.occlusionBox = occlusionBox;
            this.wristX = wristX;
            this.wristY = wristY;
            this.indexTipX = indexTipX;
            this.indexTipY = indexTipY;
        }

        public RectF getHandBox() { return handBox != null ? new RectF(handBox) : null; }
        public RectF getOcclusionBox() { return occlusionBox != null ? new RectF(occlusionBox) : null; }
        public float getWristX() { return wristX; }
        public float getWristY() { return wristY; }
        public float getIndexTipX() { return indexTipX; }
        public float getIndexTipY() { return indexTipY; }
    }

    public HandDetectorHelper(Context context) {
        this.appContext = context.getApplicationContext();
        configurarLandmarker();
    }

    private void configurarLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build();

            HandLandmarker.HandLandmarkerOptions options =
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setMinHandDetectionConfidence(MIN_HAND_CONFIDENCE)
                            .setMinTrackingConfidence(MIN_HAND_CONFIDENCE)
                            .setMinHandPresenceConfidence(MIN_HAND_CONFIDENCE)
                            .setNumHands(DEFAULT_NUM_HANDS)
                            .setRunningMode(RunningMode.IMAGE)
                            .build();

            handLandmarker = HandLandmarker.createFromOptions(appContext, options);
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível inicializar o filtro de mãos. A detecção continuará sem o filtro.", e);
            handLandmarker = null;
        }
    }

    public List<RectF> detectarMaos(Bitmap bitmap) {
        List<HandOcclusion> oclusoes = detectarMaosComOclusao(bitmap);
        List<RectF> boxes = new ArrayList<>(oclusoes.size());
        for (HandOcclusion oclusao : oclusoes) {
            if (oclusao != null && oclusao.getHandBox() != null) {
                boxes.add(oclusao.getHandBox());
            }
        }
        return boxes;
    }

    public List<HandOcclusion> detectarMaosComOclusao(Bitmap bitmap) {
        if (handLandmarker == null || bitmap == null) {
            return Collections.emptyList();
        }
        try {
            MPImage image = new BitmapImageBuilder(bitmap).build();
            HandLandmarkerResult result = handLandmarker.detect(image);
            List<HandOcclusion> boxes = new ArrayList<>();
            if (result == null) {
                return boxes;
            }

            List<List<NormalizedLandmark>> hands = result.landmarks();
            if (hands == null) {
                return boxes;
            }

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();

            for (List<NormalizedLandmark> hand : hands) {
                if (hand == null || hand.isEmpty()) continue;
                float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
                for (NormalizedLandmark lm : hand) {
                    if (lm == null) continue;
                    minX = Math.min(minX, lm.x());
                    minY = Math.min(minY, lm.y());
                    maxX = Math.max(maxX, lm.x());
                    maxY = Math.max(maxY, lm.y());
                }
                if (maxX < minX || maxY < minY) continue;
                RectF handRect = new RectF(
                        clamp(minX * width, 0f, width),
                        clamp(minY * height, 0f, height),
                        clamp(maxX * width, 0f, width),
                        clamp(maxY * height, 0f, height)
                );

                // Landmark 0 do MediaPipe Hands representa o pulso.
                NormalizedLandmark wrist = hand.size() > 0 ? hand.get(0) : null;
                float wristX = wrist != null ? clamp(wrist.x() * width, 0f, width) : handRect.centerX();
                float wristY = wrist != null ? clamp(wrist.y() * height, 0f, height) : handRect.centerY();

                // Preferimos a ponta do indicador (8), mas aceitamos outra ponta visível
                // quando a mão está parcialmente fora do quadro/mesa.
                PointF tip = selecionarPontaDedo(hand, width, height, wristX, wristY);
                float indexTipX = tip.x;
                float indexTipY = tip.y;

                // Área de oclusão: bbox da mão + extensão na direção do pulso/antebraço.
                RectF occlusionRect = expandirParaAntebraco(handRect, wristX, wristY, width, height);
                boxes.add(new HandOcclusion(handRect, occlusionRect, wristX, wristY, indexTipX, indexTipY));
            }
            return boxes;
        } catch (Exception e) {
            Log.w(TAG, "Falha ao processar filtro de mãos. Ignorando quadro.", e);
            return Collections.emptyList();
        }
    }

    private RectF expandirParaAntebraco(RectF handRect, float wristX, float wristY, int frameWidth, int frameHeight) {
        if (handRect == null) return null;

        float handW = Math.max(1f, handRect.width());
        float handH = Math.max(1f, handRect.height());
        float expand = Math.max(handW, handH) * 0.35f;

        RectF expanded = new RectF(
                handRect.left - expand,
                handRect.top - expand,
                handRect.right + expand,
                handRect.bottom + expand
        );

        float centerX = handRect.centerX();
        float centerY = handRect.centerY();
        float dirX = wristX - centerX;
        float dirY = wristY - centerY;
        float norm = (float) Math.hypot(dirX, dirY);
        if (norm > 1e-3f) {
            float ux = dirX / norm;
            float uy = dirY / norm;
            float forearmLen = Math.max(handW, handH) * 1.2f;

            if (ux > 0f) expanded.right += forearmLen * ux;
            else expanded.left += forearmLen * ux;

            if (uy > 0f) expanded.bottom += forearmLen * uy;
            else expanded.top += forearmLen * uy;
        }

        expanded.left = clamp(expanded.left, 0f, frameWidth);
        expanded.top = clamp(expanded.top, 0f, frameHeight);
        expanded.right = clamp(expanded.right, 0f, frameWidth);
        expanded.bottom = clamp(expanded.bottom, 0f, frameHeight);
        return expanded;
    }

    private float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private PointF selecionarPontaDedo(List<NormalizedLandmark> hand,
                                       int width,
                                       int height,
                                       float wristX,
                                       float wristY) {
        if (hand == null || hand.isEmpty()) {
            return new PointF(wristX, wristY);
        }

        PointF melhor = null;
        float melhorDistancia = -1f;
        for (int landmarkIndex : FINGERTIP_LANDMARKS) {
            if (hand.size() <= landmarkIndex) continue;
            NormalizedLandmark landmark = hand.get(landmarkIndex);
            if (landmark == null) continue;

            float x = clamp(landmark.x() * width, 0f, width);
            float y = clamp(landmark.y() * height, 0f, height);
            float distancia = (float) Math.hypot(x - wristX, y - wristY);

            if (distancia > melhorDistancia) {
                melhorDistancia = distancia;
                melhor = new PointF(x, y);
            }
        }

        if (melhor != null) {
            return melhor;
        }

        return new PointF(wristX, wristY);
    }

    public void close() {
        if (handLandmarker != null) {
            try {
                handLandmarker.close();
            } catch (Exception e) {
                Log.w(TAG, "Erro ao liberar HandLandmarker", e);
            }
            handLandmarker = null;
        }
    }
}
