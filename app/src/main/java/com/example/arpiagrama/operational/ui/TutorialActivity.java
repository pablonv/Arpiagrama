package com.example.arpiagrama.operational.ui;

import com.example.arpiagrama.R;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.ScaleGestureDetector;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

public class TutorialActivity extends BaseActivity {

    private static final float MIN_TEXT_SIZE_SP = 14f;
    private static final float MAX_TEXT_SIZE_SP = 32f;

    private float currentTextSizeSp = 18f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tutorial);

        Button backButton = findViewById(R.id.button_tutorial_back);
        setButtonAction(backButton, this::finish);

        List<TextView> zoomableTextViews = getZoomableTextViews();
        updateTutorialTextSize(zoomableTextViews);

        ScaleGestureDetector scaleGestureDetector =
                new ScaleGestureDetector(this, new ScaleListener(zoomableTextViews));

        ScrollView tutorialScroll = findViewById(R.id.scroll_tutorial);
        tutorialScroll.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            return false;
        });
    }

    private List<TextView> getZoomableTextViews() {
        return Arrays.asList(
                findViewById(R.id.text_tutorial_intro),
                findViewById(R.id.text_tutorial_use_case_title),
                findViewById(R.id.text_tutorial_use_case_body),
                findViewById(R.id.text_tutorial_table_title),
                findViewById(R.id.text_tutorial_table_body),
                findViewById(R.id.text_tutorial_resources_title),
                findViewById(R.id.text_tutorial_resources_body)
        );
    }

    private void updateTutorialTextSize(List<TextView> textViews) {
        for (TextView textView : textViews) {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSizeSp);
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        private final List<TextView> textViews;

        ScaleListener(List<TextView> textViews) {
            this.textViews = textViews;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float nextTextSize = currentTextSizeSp * detector.getScaleFactor();
            currentTextSizeSp = Math.max(MIN_TEXT_SIZE_SP, Math.min(nextTextSize, MAX_TEXT_SIZE_SP));
            updateTutorialTextSize(textViews);
            return true;
        }
    }
}
