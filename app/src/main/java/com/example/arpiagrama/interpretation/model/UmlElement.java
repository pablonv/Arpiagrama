package com.example.arpiagrama.interpretation.model;

import com.example.arpiagrama.visualprocessing.model.BoundingBox;
public abstract class UmlElement {
    private final String id; private final String name; private final BoundingBox position;
    protected UmlElement(String id,String name,BoundingBox position){this.id=id;this.name=name;this.position=position;}
    public String getId(){return id;} public String getName(){return name;} public BoundingBox getPosition(){return position;}
}
