package com.ultron.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Trash / Close Drop Target for Ultron Floating Bubble.
 * Appears dynamically at the bottom center of the screen when dragging the bubble.
 * Provides clear visual feedback (turns vibrant red and expands) when the bubble
 * is hovered over it, dismissing the floating service if dropped.
 */
public class FloatingTrashView extends View {

    private final Paint bgGradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF canBodyRect = new RectF();
    private boolean isHovered = false;

    public FloatingTrashView(Context context) {
        super(context);
        init();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        targetCirclePaint.setStyle(Paint.Style.FILL);

        targetBorderPaint.setStyle(Paint.Style.STROKE);
        targetBorderPaint.setStrokeCap(Paint.Cap.ROUND);

        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setColor(Color.WHITE);

        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);
        textPaint.setFakeBoldText(true);
    }

    public void show(boolean show) {
        if (show) {
            setVisibility(View.VISIBLE);
            setAlpha(0f);
            animate().alpha(1f).setDuration(180).start();
        } else {
            isHovered = false;
            animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
                @Override
                public void run() {
                    setVisibility(View.GONE);
                }
            }).start();
        }
        invalidate();
    }

    public void setHovered(boolean hovered) {
        if (this.isHovered != hovered) {
            this.isHovered = hovered;
            invalidate();
        }
    }

    public boolean isHovered() {
        return isHovered;
    }

    private int dp(float dpVal) {
        return (int) (dpVal * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Checks if the touch coordinate (rawX, rawY) is within the capture zone of this target.
     */
    public boolean isOverTarget(float rawX, float rawY, int screenHeight) {
        int w = getWidth();
        if (w <= 0) return false;

        float targetCenterX = w / 2.0f;
        float targetCenterY = screenHeight - dp(55.0f);

        double dist = Math.hypot(rawX - targetCenterX, rawY - targetCenterY);
        return dist <= dp(75.0f); // 75dp comfortable capture radius
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            // Dark gradient vignette at screen bottom for crisp contrast
            LinearGradient grad = new LinearGradient(
                0, 0, 0, h,
                Color.TRANSPARENT,
                Color.argb(160, 0, 0, 0),
                Shader.TileMode.CLAMP
            );
            bgGradientPaint.setShader(grad);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 1. Subtle bottom dark gradient
        canvas.drawRect(0, 0, w, h, bgGradientPaint);

        // Target center
        float cx = w / 2.0f;
        float cy = h - dp(55.0f);
        float baseRadius = isHovered ? dp(35.0f) : dp(28.0f);

        // 2. Target Circle
        if (isHovered) {
            // Active Crimson Red with outer glow
            targetCirclePaint.setColor(Color.parseColor("#EA4335")); // #EA4335 Vibrant Red
            targetBorderPaint.setColor(Color.WHITE);
            targetBorderPaint.setStrokeWidth(dp(2.5f));
        } else {
            // Calm Translucent Dark with Red outline
            targetCirclePaint.setColor(Color.argb(220, 30, 31, 34)); // #1E1F22
            targetBorderPaint.setColor(Color.parseColor("#EA4335"));
            targetBorderPaint.setStrokeWidth(dp(2.0f));
        }

        canvas.drawCircle(cx, cy, baseRadius, targetCirclePaint);
        canvas.drawCircle(cx, cy, baseRadius, targetBorderPaint);

        // 3. Draw Trash Can Icon
        drawTrashIcon(canvas, cx, cy);

        // 4. Status Text Label above target
        textPaint.setTextSize(dp(11.0f));
        if (isHovered) {
            textPaint.setColor(Color.parseColor("#FFD54F")); // Bright Amber/Yellow
            canvas.drawText("Release to close", cx, cy - baseRadius - dp(10.0f), textPaint);
        } else {
            textPaint.setColor(Color.parseColor("#E0E0E0"));
            canvas.drawText("Drag here to close", cx, cy - baseRadius - dp(10.0f), textPaint);
        }
    }

    private void drawTrashIcon(Canvas canvas, float cx, float cy) {
        iconPaint.setStrokeWidth(dp(2.0f));
        iconPaint.setColor(Color.WHITE);

        float halfLidW = dp(10.0f);
        float lidY = cy - dp(4.0f);

        // Trash Lid line
        canvas.drawLine(cx - halfLidW, lidY, cx + halfLidW, lidY, iconPaint);

        // Trash Lid handle
        float handleW = dp(4.0f);
        float handleH = dp(2.5f);
        canvas.drawLine(cx - handleW, lidY - handleH, cx + handleW, lidY - handleH, iconPaint);
        canvas.drawLine(cx - handleW, lidY, cx - handleW, lidY - handleH, iconPaint);
        canvas.drawLine(cx + handleW, lidY, cx + handleW, lidY - handleH, iconPaint);

        // Trash Can Body
        float bodyHalfW = dp(8.0f);
        float bodyBottom = cy + dp(10.0f);
        canBodyRect.set(cx - bodyHalfW, lidY + dp(2.0f), cx + bodyHalfW, bodyBottom);
        canvas.drawRoundRect(canBodyRect, dp(2.0f), dp(2.0f), iconPaint);

        // 2 vertical inner slat lines
        float slatTop = lidY + dp(4.5f);
        float slatBottom = bodyBottom - dp(2.5f);
        canvas.drawLine(cx - dp(3.0f), slatTop, cx - dp(3.0f), slatBottom, iconPaint);
        canvas.drawLine(cx + dp(3.0f), slatTop, cx + dp(3.0f), slatBottom, iconPaint);
    }
}
