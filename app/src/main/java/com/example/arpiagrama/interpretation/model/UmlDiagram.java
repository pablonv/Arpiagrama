package com.example.arpiagrama.interpretation.model;
import java.util.ArrayList; import java.util.Collections; import java.util.List;
public final class UmlDiagram {
 private final List<UmlElement> elements;
 public UmlDiagram(List<UmlElement> elements){this.elements=Collections.unmodifiableList(new ArrayList<>(elements));}
 public List<UmlElement> getElements(){return elements;}
}
