package com.example.arpiagrama.visualprocessing.model;

import java.util.Objects;

/** Library-independent rectangle in image coordinates. */
public final class BoundingBox {
    private final float left, top, right, bottom;
    public BoundingBox(float left, float top, float right, float bottom) {
        if (right < left || bottom < top) throw new IllegalArgumentException("Invalid bounding box");
        this.left=left; this.top=top; this.right=right; this.bottom=bottom;
    }
    public float getLeft(){return left;} public float getTop(){return top;}
    public float getRight(){return right;} public float getBottom(){return bottom;}
    public float centerX(){return (left+right)/2f;} public float centerY(){return (top+bottom)/2f;}
    @Override public boolean equals(Object o){if(this==o)return true;if(!(o instanceof BoundingBox))return false;BoundingBox b=(BoundingBox)o;return Float.compare(left,b.left)==0&&Float.compare(top,b.top)==0&&Float.compare(right,b.right)==0&&Float.compare(bottom,b.bottom)==0;}
    @Override public int hashCode(){return Objects.hash(left,top,right,bottom);}
}
