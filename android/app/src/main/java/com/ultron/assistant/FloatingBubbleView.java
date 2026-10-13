package com.ultron.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

/**
 * Native Always-On-Top Floating Bubble for Ultron Assistant.
 * Renders a crisp Google Gemini / Ultron circular orb with state-based
 * glowing animations and smooth hardware acceleration.
 */
public class FloatingBubbleView extends View {

    private final Paint outerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint micPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint micStandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF bounds = new RectF();
    private final RectF micHeadRect = new RectF();
    private final RectF micArcRect = new RectF();

    private String assistantState = "ready"; // "ready", "listening", "processing", "speaking"
    private boolean isPressed = false;
    private long animStartTime = 0;

    public FloatingBubbleView(Context context) {
        super(context);
        init();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        outerRingPaint.setStyle(Paint.Style.STROKE);
        outerRingPaint.setStrokeCap(Paint.Cap.ROUND);

        innerCirclePaint.setStyle(Paint.Style.FILL);
        innerCirclePaint.setColor(Color.parseColor("#1E1F22")); // Dark Slate core

        micPaint.setStyle(Paint.Style.FILL);
        micPaint.setColor(Color.parseColor("#FFFFFF"));

        micStandPaint.setStyle(Paint.Style.STROKE);
        micStandPaint.setStrokeCap(Paint.Cap.ROUND);
        micStandPaint.setColor(Color.parseColor("#FFFFFF"));

        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Color.argb(80, 0, 0, 0));

        animStartTime = SystemClock.uptimeMillis();
    }

    public void setAssistantState(String state) {
        if (state == null) state = "ready";
        this.assistantState = state;
        postInvalidate();
    }

    public void setPressedState(boolean pressed) {
        this.isPressed = pressed;
        postInvalidate();
    }

    private int dp(float dpVal) {
        return (int) (dpVal * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = dp(58);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float radius = Math.min(w, h) / 2.0f - dp(3.0f);

        if (isPressed) {
            radius *= 0.94f;
        }

        // 1. Drop shadow
        canvas.drawCircle(cx, cy + dp(2.0f), radius, shadowPaint);

        // 2. Inner dark body
        canvas.drawCircle(cx, cy, radius, innerCirclePaint);

        // 3. Animated outer glowing ring
        float ringWidth = dp(3.2f);
        outerRingPaint.setStrokeWidth(ringWidth);

        long now = SystemClock.uptimeMillis();
        float elapsedSec = (now - animStartTime) / 1000.0f;

        if ("listening".equals(assistantState)) {
            // Pulsing Red
            float pulse = (float) Math.sin(elapsedSec * 6.0) * 0.2f + 0.8f;
            int alpha = (int) (255 * pulse);
            outerRingPaint.setShader(null);
            outerRingPaint.setColor(Color.argb(alpha, 234, 67, 53)); // #EA4335 Google Red
            postInvalidateDelayed(40);
        } else if ("processing".equals(assistantState)) {
            // Rotating Amber
            outerRingPaint.setShader(null);
            outerRingPaint.setColor(Color.parseColor("#FBBC05")); // #FBBC05 Amber
            postInvalidateDelayed(60);
        } else if ("speaking".equals(assistantState)) {
            // Pulsing Green
            float pulse = (float) Math.sin(elapsedSec * 8.0) * 0.2f + 0.8f;
            int alpha = (int) (255 * pulse);
            outerRingPaint.setShader(null);
            outerRingPaint.setColor(Color.argb(alpha, 52, 168, 83)); // #34A853 Green
            postInvalidateDelayed(40);
        } else {
            // Ready: Gemini multi-color rotating sweep gradient
            int[] colors = new int[]{
                Color.parseColor("#4285F4"), // Blue
                Color.parseColor("#9B51E0"), // Purple
                Color.parseColor("#00E5FF"), // Cyan
                Color.parseColor("#4285F4")  // Blue wrap
            };
            float[] positions = new float[]{0.0f, 0.45f, 0.85f, 1.0f};
            SweepGradient sweep = new SweepGradient(cx, cy, colors, positions);
            outerRingPaint.setShader(sweep);
        }

        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawCircle(cx, cy, radius, outerRingPaint);

        // 4. Center Microphone Icon
        drawMicrophone(canvas, cx, cy);
    }

    private void drawMicrophone(Canvas canvas, float cx, float cy) {
        float headW = dp(7.0f);
        float headH = dp(13.0f);
        float headR = headW / 2.0f;
        float centerOffset = dp(1.5f);

        // Mic capsule head
        micHeadRect.set(
            cx - headR,
            cy - centerOffset - headH / 2.0f,
            cx + headR,
            cy - centerOffset + headH / 2.0f
        );
        canvas.drawRoundRect(micHeadRect, headR, headR, micPaint);

        // Mic cradle arc (U-shape)
        micStandPaint.setStrokeWidth(dp(2.0f));
        float cradleW = dp(13.0f);
        float cradleH = dp(13.0f);
        micArcRect.set(
            cx - cradleW / 2.0f,
            cy - centerOffset - dp(3.0f),
            cx + cradleW / 2.0f,
            cy - centerOffset + cradleH - dp(3.0f)
        );
        canvas.drawArc(micArcRect, 0, 180, false, micStandPaint);

        // Mic vertical stem
        float stemTop = cy - centerOffset + cradleH / 2.0f + dp(3.0f);
        float stemBottom = stemTop + dp(5.0f);
        canvas.drawLine(cx, stemTop, cx, stemBottom, micStandPaint);

        // Mic horizontal base
        float baseHalfW = dp(4.5f);
        canvas.drawLine(cx - baseHalfW, stemBottom, cx + baseHalfW, stemBottom, micStandPaint);
    }
}
