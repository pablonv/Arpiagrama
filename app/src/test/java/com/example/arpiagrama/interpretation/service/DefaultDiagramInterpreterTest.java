package com.example.arpiagrama.interpretation.service;

import com.example.arpiagrama.interpretation.model.Actor;
import com.example.arpiagrama.interpretation.model.UmlDiagram;
import com.example.arpiagrama.visualprocessing.model.BoundingBox;
import com.example.arpiagrama.visualprocessing.model.DetectedElement;
import com.example.arpiagrama.visualprocessing.model.DetectionState;
import com.example.arpiagrama.visualprocessing.model.ElementType;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DefaultDiagramInterpreterTest {
    @Test public void mapsOnlyConfirmedDetectionsToUmlDomain() {
        BoundingBox box = new BoundingBox(0, 0, 10, 20);
        DetectedElement actor = new DetectedElement("a1", ElementType.ACTOR, box, .95f, DetectionState.CONFIRMED);
        DetectedElement pending = new DetectedElement("u1", ElementType.USE_CASE, box, .90f, DetectionState.PENDING);
        UmlDiagram diagram = new DefaultDiagramInterpreter().interpret(Arrays.asList(actor, pending));
        assertEquals(1, diagram.getElements().size());
        assertTrue(diagram.getElements().get(0) instanceof Actor);
        assertEquals("a1", diagram.getElements().get(0).getId());
    }
}
