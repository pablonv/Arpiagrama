package com.example.arpiagrama.interpretation.service;

import com.example.arpiagrama.interpretation.model.Actor;
import com.example.arpiagrama.interpretation.model.Relationship;
import com.example.arpiagrama.interpretation.model.UmlDiagram;
import com.example.arpiagrama.interpretation.model.UmlElement;
import com.example.arpiagrama.interpretation.model.UseCase;
import com.example.arpiagrama.visualprocessing.model.DetectedElement;
import com.example.arpiagrama.visualprocessing.model.DetectionState;
import com.example.arpiagrama.visualprocessing.model.ElementType;

import java.util.ArrayList;
import java.util.List;

/** Converts confirmed vision-domain detections into the UML domain boundary. */
public final class DefaultDiagramInterpreter implements DiagramInterpreter {
    @Override
    public UmlDiagram interpret(List<DetectedElement> detections) {
        List<UmlElement> elements = new ArrayList<>();
        if (detections == null) {
            return new UmlDiagram(elements);
        }

        for (DetectedElement detection : detections) {
            if (detection == null || detection.getState() != DetectionState.CONFIRMED) {
                continue;
            }
            addUmlElement(elements, detection);
        }
        return new UmlDiagram(elements);
    }

    private void addUmlElement(List<UmlElement> elements, DetectedElement detection) {
        String name = defaultName(detection.getType());
        switch (detection.getType()) {
            case ACTOR:
                elements.add(new Actor(detection.getId(), name, detection.getBoundingBox()));
                break;
            case USE_CASE:
                elements.add(new UseCase(detection.getId(), name, detection.getBoundingBox()));
                break;
            case RELATIONSHIP:
                elements.add(new Relationship(detection.getId(), name, detection.getBoundingBox()));
                break;
            default:
                // Unknown visual objects are deliberately kept outside the UML domain.
        }
    }

    private String defaultName(ElementType type) {
        switch (type) {
            case ACTOR:
                return "Ator";
            case USE_CASE:
                return "Caso de uso";
            case RELATIONSHIP:
                return "Relacionamento";
            default:
                return "Elemento";
        }
    }
}
