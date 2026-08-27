/* Copyright 2019 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package com.example.arpiagrama.operational.rendering;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;

import com.example.arpiagrama.acquisition.camera.ImageUtils;
import com.example.arpiagrama.visualprocessing.model.Recognition;

import java.text.Normalizer;
import java.util.LinkedList;
import java.util.Locale;
import java.util.List;
import java.util.Queue;



/** A tracker that handles non-max suppression and matches existing objects to new detections. */
public class MultiBoxTracker {
  private static final float TEXT_SIZE_DIP = 18;
  private static final float REDUCED_TEXT_SIZE_DIP = 16;
  private static final float RELATION_TEXT_SIZE_DIP = 16;
  private static final float MIN_SIZE = 16.0f;
  private static final int ACTOR_COLOR = Color.parseColor("#00AA00"); // verde
  private static final int USE_CASE_COLOR = Color.parseColor("#0066FF"); // azul
  private static final int RELATION_COLOR = Color.parseColor("#FF0000"); // vermelho
  private static final int UNDEFINED_COLOR = Color.parseColor("#FFFF00"); // amarelo
  private static final int DEFINING_COLOR = Color.parseColor("#00FF00"); // verde
  private static final int[] COLORS = {
    Color.BLUE,
    Color.RED,
    Color.GREEN,
    Color.YELLOW,
    Color.CYAN,
    Color.MAGENTA,
    Color.WHITE,
    Color.parseColor("#55FF55"),
    Color.parseColor("#FFA500"),
    Color.parseColor("#FF8888"),
    Color.parseColor("#AAAAFF"),
    Color.parseColor("#FFFFAA"),
    Color.parseColor("#55AAAA"),
    Color.parseColor("#AA33AA"),
    Color.parseColor("#0D0068")
  };
  final List<Pair<Float, RectF>> screenRects = new LinkedList<Pair<Float, RectF>>();
 // private final Logger logger = new Logger();
  private final Queue<Integer> availableColors = new LinkedList<Integer>();
  private final List<TrackedRecognition> trackedObjects = new LinkedList<TrackedRecognition>();
  private final Paint boxPaint = new Paint();
  private final float textSizePx;
  private final float relationTextSizePx;
  private final BorderedText borderedText;
  private final BorderedText reducedBorderedText;
  private final BorderedText relationshipBorderedText;
  private final Paint subtleGuideLinePaint = new Paint();
  private final Rect textBounds = new Rect();
  private Matrix frameToCanvasMatrix;
  private int frameWidth;
  private int frameHeight;
  private int sensorOrientation;

  public MultiBoxTracker(final Context context) {
    for (final int color : COLORS) {
      availableColors.add(color);
    }

    boxPaint.setColor(Color.RED);
    boxPaint.setStyle(Style.STROKE);
    boxPaint.setStrokeWidth(4.0f);
    boxPaint.setStrokeCap(Cap.ROUND);
    boxPaint.setStrokeJoin(Join.ROUND);
    boxPaint.setStrokeMiter(100);

    textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
    relationTextSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, RELATION_TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
    final float reducedTextSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, REDUCED_TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    reducedBorderedText = new BorderedText(reducedTextSizePx);
    relationshipBorderedText = new BorderedText(relationTextSizePx);

    subtleGuideLinePaint.setStyle(Style.STROKE);
    subtleGuideLinePaint.setStrokeWidth(1.0f);
    subtleGuideLinePaint.setColor(Color.argb(38, 255, 255, 255));
  }

  public synchronized void setFrameConfiguration(
      final int width, final int height, final int sensorOrientation) {
    frameWidth = width;
    frameHeight = height;
    this.sensorOrientation = sensorOrientation;
  }

  public synchronized void drawDebug(final Canvas canvas) {
    final Paint textPaint = new Paint();
    textPaint.setColor(Color.WHITE);
    textPaint.setTextSize(60.0f);

    final Paint boxPaint = new Paint();
    boxPaint.setColor(Color.RED);
    boxPaint.setAlpha(200);
    boxPaint.setStyle(Style.STROKE);

    for (final Pair<Float, RectF> detection : screenRects) {
      final RectF rect = detection.second;
      canvas.drawRect(rect, boxPaint);

      canvas.drawText("" + detection.first, rect.left, rect.top, textPaint);
      borderedText.drawText(canvas, rect.centerX(), rect.centerY(), "" + detection.first);
    }
  }

  public synchronized void trackResults(final List<Recognition> results, final long timestamp) {
    processResults(results);
  }

  private Matrix getFrameToCanvasMatrix() {
    return frameToCanvasMatrix;
  }

  public synchronized void draw(final Canvas canvas) {
      Log.d("tryDrawRect","inside draw 2");
    final boolean rotated = sensorOrientation % 180 == 90;
    final float imageWidth = rotated ? frameHeight : frameWidth;
    final float imageHeight = rotated ? frameWidth : frameHeight;

    final float scale = Math.max(
        canvas.getWidth() / imageWidth,
        canvas.getHeight() / imageHeight);

    final int scaledWidth = Math.round(imageWidth * scale);
    final int scaledHeight = Math.round(imageHeight * scale);

    frameToCanvasMatrix =
        ImageUtils.getTransformationMatrix(
            frameWidth,
            frameHeight,
            scaledWidth,
            scaledHeight,
            sensorOrientation,
            false);

    final float dx = (canvas.getWidth() - scaledWidth) / 2.0f;
    final float dy = (canvas.getHeight() - scaledHeight) / 2.0f;
    frameToCanvasMatrix.postTranslate(dx, dy);

      Log.d("tryDrawRect","inside draw "+trackedObjects.size());
    for (final TrackedRecognition recognition : trackedObjects) {
      final RectF trackedPos = new RectF(recognition.location);

      getFrameToCanvasMatrix().mapRect(trackedPos);
      boxPaint.setColor(recognition.color);

      float cornerSize = Math.min(trackedPos.width(), trackedPos.height()) / 8.0f;
      if (!isDefinedRelationship(recognition)) {
        canvas.drawRoundRect(trackedPos, cornerSize, cornerSize, boxPaint);
      }

      final String labelString =
          !TextUtils.isEmpty(recognition.title)
              ? String.format("%s", recognition.title)
              : "";

      Log.d("tryDrawRect",labelString);
      if (!TextUtils.isEmpty(labelString)) {
        final LabelLayout labelLayout = buildLabelLayout(labelString, recognition.type, recognition.smallLabel);
        boolean drawOutsideTop = shouldDrawOutsideTop(recognition);
        drawCenteredTopLabel(canvas, trackedPos, cornerSize, labelLayout, drawOutsideTop);
      }
    }
  }

  private void drawCenteredTopLabel(
      final Canvas canvas,
      final RectF trackedPos,
      final float cornerSize,
      final LabelLayout labelLayout,
      final boolean drawOutsideTop) {
    if (labelLayout == null) return;

    final float textTopPadding = Math.max(4.0f, cornerSize * 0.2f);
    final float baseline = drawOutsideTop
        ? trackedPos.top - 8.0f
        : trackedPos.top + textTopPadding + labelLayout.textPainter.getTextSize();

    if (!drawOutsideTop && labelLayout.drawGuideLine) {
      float lineY = trackedPos.top + textTopPadding - 2.0f;
      canvas.drawLine(
          trackedPos.left + cornerSize,
          lineY,
          trackedPos.right - cornerSize,
          lineY,
          subtleGuideLinePaint);
    }

    if (labelLayout.secondLine == null) {
      float centeredX = computeCenteredTextX(labelLayout.textPainter, labelLayout.firstLine, trackedPos.centerX());
      labelLayout.textPainter.drawText(canvas, centeredX, baseline, labelLayout.firstLine, boxPaint);
      return;
    }

    float firstLineBaseline = drawOutsideTop
        ? baseline - labelLayout.textPainter.getTextSize() - 2.0f
        : baseline;
    float secondLineBaseline = drawOutsideTop
        ? baseline
        : baseline + labelLayout.textPainter.getTextSize() + 2.0f;
    float centeredFirstX =
        computeCenteredTextX(labelLayout.textPainter, labelLayout.firstLine, trackedPos.centerX());
    float centeredSecondX =
        computeCenteredTextX(labelLayout.textPainter, labelLayout.secondLine, trackedPos.centerX());
    labelLayout.textPainter.drawText(canvas, centeredFirstX, firstLineBaseline, labelLayout.firstLine, boxPaint);
    labelLayout.textPainter.drawText(canvas, centeredSecondX, secondLineBaseline, labelLayout.secondLine, boxPaint);
  }

  private float computeCenteredTextX(
      final BorderedText painter, final String text, final float centerX) {
    if (painter == null || text == null) return centerX;
    painter.getTextBounds(text, 0, text.length(), textBounds);
    return centerX - (textBounds.width() / 2.0f);
  }

  private LabelLayout buildLabelLayout(final String label, final String type, final boolean smallLabel) {
    if (smallLabel) {
      return new LabelLayout(relationshipBorderedText, label, null, true);
    }

    final String[] words = label.trim().split("\\s+");
    if (isActorType(type) || isUseCaseType(type)) {
      if (words.length > 2) {
        return buildTwoLineLabelLayout(words, false);
      }
      return new LabelLayout(reducedBorderedText, label, null, false);
    }

    if (isRelationshipType(type)) {
      return new LabelLayout(reducedBorderedText, label, null, true);
    }

    if (words.length <= 2) {
      return new LabelLayout(reducedBorderedText, label, null, false);
    }

    return buildTwoLineLabelLayout(words, false);
  }

  private LabelLayout buildTwoLineLabelLayout(final String[] words, final boolean drawGuideLine) {
    if (words == null || words.length == 0) {
      return new LabelLayout(reducedBorderedText, "", null, drawGuideLine);
    }
    if (words.length == 1) {
      return new LabelLayout(reducedBorderedText, words[0], null, drawGuideLine);
    }
    final int splitIndex = (int) Math.ceil(words.length / 2.0);
    final StringBuilder firstLine = new StringBuilder();
    final StringBuilder secondLine = new StringBuilder();
    for (int i = 0; i < words.length; i++) {
      if (i < splitIndex) {
        if (firstLine.length() > 0) firstLine.append(' ');
        firstLine.append(words[i]);
      } else {
        if (secondLine.length() > 0) secondLine.append(' ');
        secondLine.append(words[i]);
      }
    }
    return new LabelLayout(
        reducedBorderedText,
        firstLine.toString(),
        secondLine.length() > 0 ? secondLine.toString() : null,
        drawGuideLine);
  }

  private void processResults(final List<Recognition> results) {
    final List<Pair<Float, Recognition>> rectsToTrack = new LinkedList<Pair<Float, Recognition>>();

    screenRects.clear();
    final Matrix rgbFrameToScreen = new Matrix(getFrameToCanvasMatrix());

    for (final Recognition result : results) {
      if (result.getLocation() == null) {
        continue;
      }
      final RectF detectionFrameRect = new RectF(result.getLocation());

      final RectF detectionScreenRect = new RectF();
      rgbFrameToScreen.mapRect(detectionScreenRect, detectionFrameRect);

//      logger.v(
//          "Result! Frame: " + result.getLocation() + " mapped to screen:" + detectionScreenRect);

      screenRects.add(new Pair<Float, RectF>(result.getConfidence(), detectionScreenRect));

      if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
        //logger.w("Degenerate rectangle! " + detectionFrameRect);
        continue;
      }

      rectsToTrack.add(new Pair<Float, Recognition>(result.getConfidence(), result));
    }

    trackedObjects.clear();
    if (rectsToTrack.isEmpty()) {
     // logger.v("Nothing to track, aborting.");
      return;
    }

    for (final Pair<Float, Recognition> potential : rectsToTrack) {
      final TrackedRecognition trackedRecognition = new TrackedRecognition();
      trackedRecognition.detectionConfidence = potential.first;
      trackedRecognition.location = new RectF(potential.second.getLocation());
      trackedRecognition.type = potential.second.getType() != null
          ? potential.second.getType()
          : potential.second.getTitle();
      trackedRecognition.name = potential.second.getName();
      trackedRecognition.defined = potential.second.isDefined();
      trackedRecognition.title =
          buildDisplayTitle(
              potential.second.getTitle(),
              potential.second.getType(),
              trackedRecognition.name,
              trackedRecognition.defined);
      trackedRecognition.smallLabel = shouldUseSmallLabel(potential.second.getType(), trackedRecognition.title);
      trackedRecognition.color = getColorForRecognition(potential.second, trackedObjects.size());
      trackedObjects.add(trackedRecognition);

    }
  }

  private String buildDisplayTitle(
      final String rawTitle, final String rawType, final String definedName, final boolean defined) {
    if (defined
        && !TextUtils.isEmpty(definedName)
        && (isActorType(rawType) || isUseCaseType(rawType))) {
      return definedName;
    }

    String translatedTitle = translateTitle(rawTitle);
    if (!isRelationshipType(rawType)) {
      return translatedTitle;
    }
    String shortLabel = abbreviateRelationshipDefinition(translatedTitle);
    return shortLabel != null ? shortLabel : translatedTitle;
  }

  private boolean isDefinedRelationship(final TrackedRecognition recognition) {
    return recognition != null
        && recognition.defined
        && isRelationshipType(recognition.type);
  }

  private boolean shouldDrawOutsideTop(final TrackedRecognition recognition) {
    return recognition != null
        && recognition.defined
        && !TextUtils.isEmpty(recognition.title)
        && (isActorType(recognition.type) || isUseCaseType(recognition.type));
  }

  private boolean shouldUseSmallLabel(final String rawType, final String displayTitle) {
    if (!isRelationshipType(rawType) || displayTitle == null) {
      return false;
    }
    return "ass.".equals(displayTitle)
        || "inc.".equals(displayTitle)
        || "ext.".equals(displayTitle)
        || "gen.".equals(displayTitle);
  }

  private boolean isRelationshipType(final String rawType) {
    if (rawType == null) return false;
    String normalized = normalize(rawType);
    return normalized.contains("relacionamento") || normalized.contains("relationship");
  }

  private boolean isActorType(final String rawType) {
    if (rawType == null) return false;
    String normalized = normalize(rawType);
    return normalized.contains("ator") || normalized.contains("actor");
  }

  private boolean isUseCaseType(final String rawType) {
    if (rawType == null) return false;
    String normalized = normalize(rawType);
    return normalized.contains("caso de uso")
        || normalized.contains("use case")
        || normalized.contains("usecase")
        || normalized.contains("usercase");
  }

  private String abbreviateRelationshipDefinition(final String label) {
    if (label == null) return null;
    String normalized = normalize(label);
    if (normalized.contains("associ")) return "ass.";
    if (normalized.contains("include")) return "inc.";
    if (normalized.contains("extend")) return "ext.";
    if (normalized.contains("generaliz")) return "gen.";
    return null;
  }

  private String normalize(final String text) {
    if (text == null) return "";
    return Normalizer.normalize(text, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .trim()
        .toLowerCase(Locale.ROOT)
        .replace('_', ' ')
        .replace('-', ' ')
        .replaceAll("\\s+", " ");
  }

  private String translateTitle(final String rawTitle) {
    if (rawTitle == null) return null;

    final String normalized =
        rawTitle
            .trim()
            .toLowerCase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceAll("\\s+", " ");

    if (normalized.contains("actor")) return "ator";
    if (normalized.contains("use case") || normalized.contains("usecase") || normalized.contains("usercase")) {
      return "caso de uso";
    }
    if (normalized.contains("relationship") || normalized.contains("relaciotionship")) return "relacionamento";

    return rawTitle;
  }

  private int getColorForRecognition(final Recognition recognition, final int index) {
    if (recognition != null) {
      if (recognition.isBeingDefined()) {
        return DEFINING_COLOR;
      }
      String type = recognition.getType();
      if (type == null) type = recognition.getTitle();
      if (type != null) {
        final String normalized =
            type
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ");
        if (normalized.contains("ator")) return ACTOR_COLOR;
        if (normalized.contains("caso de uso") || normalized.contains("use case") || normalized.contains("usecase")) {
          return USE_CASE_COLOR;
        }
        if (normalized.contains("relacionamento") || normalized.contains("relationship")) return RELATION_COLOR;
      }

      if (!recognition.isDefined()) {
        return UNDEFINED_COLOR;
      }
    }
    return COLORS[index % COLORS.length];
  }

  private static class TrackedRecognition {
    RectF location;
    float detectionConfidence;
    int color;
    String title;
    String type;
    String name;
    boolean defined;
    boolean smallLabel;
  }

  private static class LabelLayout {
    final BorderedText textPainter;
    final String firstLine;
    final String secondLine;
    final boolean drawGuideLine;

    LabelLayout(
        final BorderedText textPainter,
        final String firstLine,
        final String secondLine,
        final boolean drawGuideLine) {
      this.textPainter = textPainter;
      this.firstLine = firstLine;
      this.secondLine = secondLine;
      this.drawGuideLine = drawGuideLine;
    }
  }
}
