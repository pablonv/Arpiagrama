package com.example.arpiagrama.interpretation.service;
import com.example.arpiagrama.interpretation.model.UmlDiagram; import com.example.arpiagrama.visualprocessing.model.DetectedElement; import java.util.List;
public interface DiagramInterpreter { UmlDiagram interpret(List<DetectedElement> detections); }
