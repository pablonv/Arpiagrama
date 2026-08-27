package com.example.arpiagrama.visualprocessing.tracking;

import com.example.arpiagrama.visualprocessing.model.Recognition;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Multi-objeto simples baseado em tracking-by-detection.
 *
 * - Associação por classe + distância de centro (com fallback por IoU).
 * - Estados com janela de oclusão: PENDING -> CONFIRMADA -> POSSIVEL_OCLUSAO -> REMOVIDA.
 * - Remoção apenas se ausência persistir por tempo em ms (não em frames) e com
 *   heurística de movimento para evitar falsos positivos quando o usuário cobre a peça.
 */
public class PieceTracker {

    private static final long REAPPEAR_GRACE_MS = 5000L;
    public static final float MAX_CENTER_DISTANCE_ACTOR = 90f;
    public static final float MAX_CENTER_DISTANCE_USECASE = 90f;
    public static final float MAX_CENTER_DISTANCE_RELATIONSHIP = 180f;
    public static final float MIN_IOU_SAME_OBJECT = 0.15f;
    public static final int FRAMES_TO_CONFIRM_MOVE = 3;
    public static final int FRAMES_TO_CONFIRM_NEW_OBJECT = 3;
    public static final int FRAMES_TO_KEEP_LOST_TRACK = 12;
    public static final float AMBIGUITY_MARGIN = 0.12f;
    private static final float MAX_SIZE_DELTA_RATIO = 0.55f;
    private static final float WEIGHT_CENTER = 0.45f;
    private static final float WEIGHT_IOU = 0.30f;
    private static final float WEIGHT_SIZE = 0.10f;
    private static final float WEIGHT_PREDICTION = 0.15f;

    public enum TrackState { PENDING, CONFIRMADA, POSSIVEL_OCLUSAO, REMOVIDA }

    public static class TrackedPiece {
        private final long id;
        private final String className;
        private float averageConfidence;
        private RectF estimatedBox;
        private float velocityX;
        private float velocityY;
        private long firstSeenMs;
        private long confirmedAtMs;
        private long lastSeenMs;
        private Long missingSinceMs;
        private TrackState state;
        private boolean matchedThisFrame;
        private float lastMovementPx;
        private long lastMovementMs;
        private int stableMoveFrames;
        private RectF pendingMoveBox;
        private int pendingFrames;
        private int lostFrames;
        private int ambiguousFrames;

        TrackedPiece(long id, String className, Recognition detection, long nowMs) {
            this.id = id;
            this.className = normalizeLabel(className);
            this.estimatedBox = detection.getLocation() != null ? new RectF(detection.getLocation()) : null;
            this.averageConfidence = detection.getConfidence();
            this.firstSeenMs = nowMs;
            this.lastSeenMs = nowMs;
            this.state = TrackState.PENDING;
            this.matchedThisFrame = true;
            this.pendingFrames = 1;
        }

        TrackedPiece(TrackedPiece other) {
            this.id = other.id;
            this.className = other.className;
            this.averageConfidence = other.averageConfidence;
            this.estimatedBox = other.estimatedBox != null ? new RectF(other.estimatedBox) : null;
            this.velocityX = other.velocityX;
            this.velocityY = other.velocityY;
            this.firstSeenMs = other.firstSeenMs;
            this.confirmedAtMs = other.confirmedAtMs;
            this.lastSeenMs = other.lastSeenMs;
            this.missingSinceMs = other.missingSinceMs;
            this.state = other.state;
            this.matchedThisFrame = other.matchedThisFrame;
            this.lastMovementPx = other.lastMovementPx;
            this.lastMovementMs = other.lastMovementMs;
            this.stableMoveFrames = other.stableMoveFrames;
            this.pendingMoveBox = other.pendingMoveBox != null ? new RectF(other.pendingMoveBox) : null;
            this.pendingFrames = other.pendingFrames;
            this.lostFrames = other.lostFrames;
            this.ambiguousFrames = other.ambiguousFrames;
        }

        void updateWithDetection(Recognition detection, long nowMs, long addThresholdMs) {
            if (detection.getLocation() != null) {
                RectF newBox = new RectF(detection.getLocation());
                if (estimatedBox != null) {
                    float dtSec = Math.max(1e-3f, (nowMs - lastSeenMs) / 1000f);
                    float dx = centerX(newBox) - centerX(estimatedBox);
                    float dy = centerY(newBox) - centerY(estimatedBox);
                    velocityX = dx / dtSec;
                    velocityY = dy / dtSec;
                    float disp = (float) Math.hypot(dx, dy);
                    lastMovementPx = disp;
                    lastMovementMs = nowMs;
                }
                if (estimatedBox == null || centerDistance(estimatedBox, newBox) <= 4f) {
                    estimatedBox = newBox;
                    stableMoveFrames = 0;
                    pendingMoveBox = null;
                } else {
                    if (pendingMoveBox == null || centerDistance(pendingMoveBox, newBox) > 8f) {
                        pendingMoveBox = new RectF(newBox);
                        stableMoveFrames = 1;
                    } else {
                        stableMoveFrames++;
                        if (stableMoveFrames >= FRAMES_TO_CONFIRM_MOVE) {
                            estimatedBox = new RectF(pendingMoveBox);
                            pendingMoveBox = null;
                            stableMoveFrames = 0;
                        }
                    }
                }
            }
            float conf = detection.getConfidence();
            averageConfidence = (averageConfidence == 0f) ? conf : (0.6f * averageConfidence + 0.4f * conf);
            lastSeenMs = nowMs;
            matchedThisFrame = true;
            missingSinceMs = null;
            lostFrames = 0;
            if (ambiguousFrames > 0) ambiguousFrames--;

            if (state == TrackState.PENDING) {
                pendingFrames++;
            }
            if (state == TrackState.PENDING && ((nowMs - firstSeenMs) >= addThresholdMs || pendingFrames >= FRAMES_TO_CONFIRM_NEW_OBJECT)) {
                state = TrackState.CONFIRMADA;
                confirmedAtMs = nowMs;
            } else if (state == TrackState.POSSIVEL_OCLUSAO) {
                state = TrackState.CONFIRMADA;
                if (confirmedAtMs == 0L) {
                    confirmedAtMs = nowMs;
                }
            }
        }

        void markMissing(long nowMs,
                         long removeThresholdMs,
                         long occlusionExtensionMs,
                         float movementEvidencePx,
                         int frameWidth,
                         int frameHeight,
                         boolean blockedByHand,
                         long handClearMs) {
            if (state == TrackState.REMOVIDA) return;

            if (missingSinceMs == null) missingSinceMs = nowMs;
            matchedThisFrame = false;
            lostFrames++;
            if (state == TrackState.CONFIRMADA || state == TrackState.PENDING) {
                state = TrackState.POSSIVEL_OCLUSAO;
            }

            if (blockedByHand) {
                // Enquanto a peça estiver em área ocluída por mão/pulso, ela não pode
                // acumular ausência para remoção. Isso evita remover/recriar peça coberta.
                missingSinceMs = nowMs;
                lostFrames = 0;
                return;
            }

            // Prever posição usando velocidade para manter associação em oclusões.
            if (estimatedBox != null) {
                float dtSec = Math.max(0f, (nowMs - lastSeenMs) / 1000f);
                RectF predicted = new RectF(estimatedBox);
                predicted.offset(velocityX * dtSec, velocityY * dtSec);
                clampToFrame(predicted, frameWidth, frameHeight);
                estimatedBox = predicted;
            }

            long missingDuration = nowMs - missingSinceMs;
            long removalDeadline = removeThresholdMs;
            boolean hasMovementEvidence = hasRecentMovement(nowMs, movementEvidencePx);
            if (!hasMovementEvidence) {
                removalDeadline += occlusionExtensionMs;
            }
            if (handClearMs > 0L) {
                removalDeadline += handClearMs;
            }

            if (lostFrames >= FRAMES_TO_KEEP_LOST_TRACK || missingDuration >= removalDeadline) {
                state = TrackState.REMOVIDA;
            }
        }

        private boolean hasRecentMovement(long nowMs, float movementEvidencePx) {
            if (lastMovementMs == 0L) return false;
            boolean displaced = lastMovementPx >= movementEvidencePx;
            boolean recent = (nowMs - lastMovementMs) <= REAPPEAR_GRACE_MS;
            return displaced || recent;
        }

        public long getId() { return id; }
        public String getClassName() { return className; }
        public float getAverageConfidence() { return averageConfidence; }
        public RectF getEstimatedBox() { return estimatedBox != null ? new RectF(estimatedBox) : null; }
        public long getLastSeenMs() { return lastSeenMs; }
        public Long getMissingSinceMs() { return missingSinceMs; }
        public TrackState getState() { return state; }
        public boolean wasUpdatedThisFrame() { return matchedThisFrame; }
        public long getConfirmedAtMs() { return confirmedAtMs; }
        public boolean isAmbiguous() { return ambiguousFrames > 0; }

        public Recognition asRecognition() {
            if (estimatedBox == null) return null;
            return new Recognition(className, averageConfidence, String.valueOf(id), new RectF(estimatedBox), className);
        }

        static float centerX(RectF rect) { return (rect.left + rect.right) * 0.5f; }
        static float centerY(RectF rect) { return (rect.top + rect.bottom) * 0.5f; }
        static float centerDistance(RectF a, RectF b) {
            return (float) Math.hypot(centerX(a) - centerX(b), centerY(a) - centerY(b));
        }
        static void clampToFrame(RectF rect, int frameWidth, int frameHeight) {
            if (frameWidth <= 0 || frameHeight <= 0 || rect == null) return;
            rect.left = Math.max(0f, Math.min(rect.left, frameWidth));
            rect.top = Math.max(0f, Math.min(rect.top, frameHeight));
            rect.right = Math.max(0f, Math.min(rect.right, frameWidth));
            rect.bottom = Math.max(0f, Math.min(rect.bottom, frameHeight));
        }
    }

    private final List<TrackedPiece> tracks = new ArrayList<>();
    private long nextId = 1L;
    private final long addThresholdMs;
    private final long removeThresholdMs;
    private final long occlusionExtensionMs;
    private final float movementEvidencePx;
    private final float baseMatchDistancePx;
    private float maxMatchDistancePx;
    private int frameWidth = 0;
    private int frameHeight = 0;

    public PieceTracker(long addThresholdMs,
                        long removeThresholdMs,
                        long occlusionExtensionMs,
                        float movementEvidencePx,
                        float matchDistancePx) {
        this.addThresholdMs = addThresholdMs;
        this.removeThresholdMs = removeThresholdMs;
        this.occlusionExtensionMs = occlusionExtensionMs;
        this.movementEvidencePx = movementEvidencePx;
        this.baseMatchDistancePx = matchDistancePx;
        this.maxMatchDistancePx = matchDistancePx;
    }

    public void setFrameSize(int width, int height) {
        this.frameWidth = width;
        this.frameHeight = height;
        float diag = (float) Math.hypot(Math.max(1, width), Math.max(1, height));
        this.maxMatchDistancePx = Math.max(baseMatchDistancePx, diag * 0.06f);
    }

    public List<TrackedPiece> update(List<Recognition> detections, long nowMs, OcclusionBlocker occlusionBlocker, long handClearMs) {
        List<Recognition> safeDetections = detections != null ? detections : Collections.emptyList();
        boolean[] detectionMatched = new boolean[safeDetections.size()];
        Set<TrackedPiece> updatedTracks = new HashSet<>();

        // Reset flags for this frame
        for (TrackedPiece track : tracks) {
            track.matchedThisFrame = false;
        }

        List<TrackedPiece> availableTracks = new ArrayList<>();
        for (TrackedPiece track : tracks) {
            if (track.state != TrackState.REMOVIDA) availableTracks.add(track);
        }
        List<MatchCandidate> candidates = buildCandidates(availableTracks, safeDetections, nowMs);
        candidates.sort(Comparator.comparingDouble(c -> c.cost));

        boolean[] trackMatched = new boolean[availableTracks.size()];
        for (MatchCandidate candidate : candidates) {
            if (detectionMatched[candidate.detIndex] || trackMatched[candidate.trackIndex]) continue;
            float second = secondBestCost(candidates, candidate.detIndex, candidate.trackIndex);
            if (!isClearlyBetter(candidate.cost, second)) {
                availableTracks.get(candidate.trackIndex).ambiguousFrames = Math.max(availableTracks.get(candidate.trackIndex).ambiguousFrames, FRAMES_TO_CONFIRM_MOVE);
                continue;
            }
            TrackedPiece bestTrack = availableTracks.get(candidate.trackIndex);
            bestTrack.updateWithDetection(safeDetections.get(candidate.detIndex), nowMs, addThresholdMs);
            updatedTracks.add(bestTrack);
            detectionMatched[candidate.detIndex] = true;
            trackMatched[candidate.trackIndex] = true;
        }

        // Criar trilhas novas para detecções não associadas
        for (int i = 0; i < safeDetections.size(); i++) {
            if (detectionMatched[i]) continue;
            Recognition detection = safeDetections.get(i);
            if (detection.getLocation() == null) continue;
            TrackedPiece newTrack = new TrackedPiece(nextId++, detection.getTitle(), detection, nowMs);
            tracks.add(newTrack);
            updatedTracks.add(newTrack);
        }

        // Marcar ausências e aplicar janela temporal de remoção
        for (TrackedPiece track : tracks) {
            if (!updatedTracks.contains(track)) {
                boolean blocked = occlusionBlocker != null && occlusionBlocker.isBlocked(track, nowMs);
                track.markMissing(nowMs, removeThresholdMs, occlusionExtensionMs, movementEvidencePx, frameWidth, frameHeight, blocked, handClearMs);
            } else if (track.getMissingSinceMs() != null && (nowMs - track.getMissingSinceMs()) <= REAPPEAR_GRACE_MS) {
                track.state = TrackState.CONFIRMADA;
            }
        }

        // Limpeza de trilhas removidas antigas
        tracks.removeIf(track -> track.state == TrackState.REMOVIDA && (nowMs - track.lastSeenMs) > (removeThresholdMs * 2));

        return snapshot();
    }

    public TrackedPiece findById(Long id) {
        if (id == null) return null;
        for (TrackedPiece track : tracks) {
            if (track.id == id) return new TrackedPiece(track);
        }
        return null;
    }

    private List<TrackedPiece> snapshot() {
        List<TrackedPiece> copy = new ArrayList<>();
        for (TrackedPiece track : tracks) {
            copy.add(new TrackedPiece(track));
        }
        return copy;
    }

    public interface OcclusionBlocker {
        boolean isBlocked(TrackedPiece piece, long nowMs);
    }

    private float distanceToTrack(TrackedPiece track, RectF detBox, long nowMs) {
        RectF trackBox = track.estimatedBox != null ? new RectF(track.estimatedBox) : null;
        if (trackBox == null) return Float.MAX_VALUE;

        if (!track.matchedThisFrame && track.missingSinceMs != null) {
            float dtSec = Math.max(0f, (nowMs - track.lastSeenMs) / 1000f);
            trackBox.offset(track.velocityX * dtSec, track.velocityY * dtSec);
            TrackedPiece.clampToFrame(trackBox, frameWidth, frameHeight);
        }

        float centerDist = TrackedPiece.centerDistance(detBox, trackBox);
        float centerGate = maxCenterDistance(track.className);
        if (centerDist > Math.max(centerGate, maxMatchDistancePx)) return Float.MAX_VALUE;
        float iou = iou(trackBox, detBox);
        if (iou < MIN_IOU_SAME_OBJECT && centerDist > (centerGate * 0.7f)) return Float.MAX_VALUE;

        float dw = Math.abs(detBox.width() - trackBox.width()) / Math.max(1f, trackBox.width());
        float dh = Math.abs(detBox.height() - trackBox.height()) / Math.max(1f, trackBox.height());
        float sizePenalty = Math.min(1f, (dw + dh) * 0.5f / MAX_SIZE_DELTA_RATIO);

        RectF predicted = predictBox(track, nowMs);
        float predDist = predicted != null ? TrackedPiece.centerDistance(detBox, predicted) : centerDist;
        float predNorm = Math.min(1f, predDist / Math.max(1f, centerGate));
        float centerNorm = Math.min(1f, centerDist / Math.max(1f, centerGate));
        float iouPenalty = 1f - iou;
        return (WEIGHT_CENTER * centerNorm)
                + (WEIGHT_IOU * iouPenalty)
                + (WEIGHT_SIZE * sizePenalty)
                + (WEIGHT_PREDICTION * predNorm);
    }

    private RectF predictBox(TrackedPiece track, long nowMs) {
        if (track == null || track.estimatedBox == null) return null;
        float dtSec = Math.max(0f, (nowMs - track.lastSeenMs) / 1000f);
        RectF predicted = new RectF(track.estimatedBox);
        predicted.offset(track.velocityX * dtSec, track.velocityY * dtSec);
        TrackedPiece.clampToFrame(predicted, frameWidth, frameHeight);
        return predicted;
    }

    private float maxCenterDistance(String className) {
        String type = normalizeLabel(className);
        if ("relacionamento".equals(type) || "relationship".equals(type)) return MAX_CENTER_DISTANCE_RELATIONSHIP;
        if ("ator".equals(type) || "actor".equals(type)) return MAX_CENTER_DISTANCE_ACTOR;
        return MAX_CENTER_DISTANCE_USECASE;
    }

    private boolean isClearlyBetter(float best, float second) {
        if (!Float.isFinite(best)) return false;
        if (!Float.isFinite(second) || second == Float.MAX_VALUE) return true;
        return (second - best) >= AMBIGUITY_MARGIN;
    }

    private float secondBestCost(List<MatchCandidate> all, int detIndex, int usedTrackIndex) {
        float second = Float.MAX_VALUE;
        for (MatchCandidate candidate : all) {
            if (candidate.detIndex != detIndex || candidate.trackIndex == usedTrackIndex) continue;
            if (candidate.cost < second) second = candidate.cost;
        }
        return second;
    }

    private List<MatchCandidate> buildCandidates(List<TrackedPiece> availableTracks, List<Recognition> detections, long nowMs) {
        List<MatchCandidate> result = new ArrayList<>();
        for (int ti = 0; ti < availableTracks.size(); ti++) {
            TrackedPiece candidate = availableTracks.get(ti);
            for (int di = 0; di < detections.size(); di++) {
                Recognition detection = detections.get(di);
                RectF detBox = detection.getLocation();
                if (detBox == null) continue;
                if (!classesCompat(candidate.className, detection.getTitle())) continue;
                float cost = distanceToTrack(candidate, detBox, nowMs);
                if (Float.isFinite(cost) && cost <= 1.05f) {
                    result.add(new MatchCandidate(di, ti, cost));
                }
            }
        }
        return result;
    }

    private static final class MatchCandidate {
        private final int detIndex;
        private final int trackIndex;
        private final float cost;

        private MatchCandidate(int detIndex, int trackIndex, float cost) {
            this.detIndex = detIndex;
            this.trackIndex = trackIndex;
            this.cost = cost;
        }
    }

    private static boolean classesCompat(String a, String b) {
        String na = normalizeLabel(a);
        String nb = normalizeLabel(b);
        if (na.isEmpty() || nb.isEmpty()) return true;
        return na.equalsIgnoreCase(nb);
    }

    private static String normalizeLabel(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static float iou(RectF a, RectF b) {
        if (a == null || b == null) return 0f;
        float interW = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        float interH = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        float inter = interW * interH;
        float areaA = Math.max(0, a.width()) * Math.max(0, a.height());
        float areaB = Math.max(0, b.width()) * Math.max(0, b.height());
        float uni = areaA + areaB - inter;
        return uni <= 0 ? 0f : inter / uni;
    }
}
