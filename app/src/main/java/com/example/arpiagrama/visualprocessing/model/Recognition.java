package com.example.arpiagrama.visualprocessing.model;

import android.graphics.RectF;

public class Recognition {
    String title;
    float confidence;
    String id;
    RectF location;
    String name;
    String type;
    boolean defined;
    boolean beingDefined;

    public Recognition(String title, float confidence, String id, RectF location, String name) {
        this(title, confidence, id, location, name, title, false, false);
    }

    public Recognition(String title,
                       float confidence,
                       String id,
                       RectF location,
                       String name,
                       String type,
                       boolean defined,
                       boolean beingDefined) {
        this.title = title;
        this.confidence = confidence;
        this.id = id;
        this.location = location;
        this.name=name;
        this.type = type;
        this.defined = defined;
        this.beingDefined = beingDefined;
    }



    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setLabel(String label) {
        this.title = label;
    }


    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public float getConfidence() {
        return confidence;
    }

    public void setConfidence(float confidence) {
        this.confidence = confidence;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public RectF getLocation() {
        return location;
    }

    public void setLocation(RectF location) {
        this.location = location;
    }

    public String getType() { return type; }

    public void setType(String type) { this.type = type; }

    public boolean isDefined() { return defined; }

    public void setDefined(boolean defined) { this.defined = defined; }

    public boolean isBeingDefined() { return beingDefined; }

    public void setBeingDefined(boolean beingDefined) { this.beingDefined = beingDefined; }
}
