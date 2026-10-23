package ru.iqwanoino.devhosy;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Compact Material-style indeterminate circular indicator. */
public final class InoLoadingIndicator extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private int color = 0xFFD0BCFF;
    private float sweep = 95f, rotation;
    private ValueAnimator animator;
    public InoLoadingIndicator(Context c) { super(c); density = getResources().getDisplayMetrics().density; setMinimumWidth(dp(32)); setMinimumHeight(dp(32)); }
    public void setIndicatorColor(int c) { color = c; invalidate(); }
    private int dp(float x) { return Math.round(x * density); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); start(); }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
    public void start() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 360f); animator.setDuration(1000); animator.setRepeatCount(ValueAnimator.INFINITE); animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> { rotation = (Float) a.getAnimatedValue(); sweep = 70f + 45f * (float)Math.sin(Math.toRadians(rotation * 1.7)); invalidate(); }); animator.start();
    }
    public void stop() { if (animator != null) { animator.cancel(); animator = null; } }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float s = Math.min(getWidth(), getHeight()), pad = Math.max(dp(3), s * .18f);
        RectF r = new RectF((getWidth()-s)/2+pad, (getHeight()-s)/2+pad, (getWidth()+s)/2-pad, (getHeight()+s)/2-pad);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(dp(2), s*.09f)); paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(color);
        canvas.drawArc(r, rotation, sweep, false, paint); paint.setStyle(Paint.Style.FILL);
    }
}
