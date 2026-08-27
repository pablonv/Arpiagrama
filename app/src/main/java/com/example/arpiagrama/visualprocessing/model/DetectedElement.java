package com.example.arpiagrama.visualprocessing.model;

/** Stable contract exposed by visual processing; no framework-specific type leaks out. */
public final class DetectedElement {
    private final String id;
    private final ElementType type;
    private final BoundingBox boundingBox;
    private final float confidence;
    private final DetectionState state;

    public DetectedElement(
            String id,
            ElementType type,
            BoundingBox boundingBox,
            float confidence,
            DetectionState state) {
        this.id = id;
        this.type = type == null ? ElementType.UNKNOWN : type;
        this.boundingBox = boundingBox;
        this.confidence = confidence;
        this.state = state == null ? DetectionState.PENDING : state;
    }

    public String getId() { return id; }
    public ElementType getType() { return type; }
    public BoundingBox getBoundingBox() { return boundingBox; }
    public float getConfidence() { return confidence; }
    public DetectionState getState() { return state; }
}
