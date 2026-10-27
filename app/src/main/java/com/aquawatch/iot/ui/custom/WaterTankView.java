package com.aquawatch.iot.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class WaterTankView extends View {
    private float waterPercent = 0.0f;
    private float wavePhase = 0.0f;
    private final Path wavePath = new Path();
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tankBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tankBounds = new RectF();
    private ValueAnimator waveAnimator;

    public WaterTankView(Context context) {
        super(context);
        init();
    }

    public WaterTankView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        tankBorderPaint.setColor(Color.parseColor("#90CAF9"));
        tankBorderPaint.setStyle(Paint.Style.STROKE);
        tankBorderPaint.setStrokeWidth(8f);

        waveAnimator = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        waveAnimator.setDuration(2200);
        waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
        waveAnimator.setInterpolator(new LinearInterpolator());
        waveAnimator.addUpdateListener(animation -> {
            wavePhase = (float) animation.getAnimatedValue();
            invalidate();
        });
        waveAnimator.start();
    }

    public void setWaterLevel(float percent) {
        this.waterPercent = Math.max(0f, Math.min(100f, percent));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float padding = 16f;

        tankBounds.set(padding, padding, w - padding, h - padding);
        float cornerRadius = 32f;

        canvas.save();
        Path clipPath = new Path();
        clipPath.addRoundRect(tankBounds, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.clipPath(clipPath);

        float waterHeight = tankBounds.bottom - (tankBounds.height() * (waterPercent / 100f));
        wavePath.reset();
        wavePath.moveTo(tankBounds.left, tankBounds.bottom);
        wavePath.lineTo(tankBounds.left, waterHeight);

        float amplitude = (waterPercent <= 0 || waterPercent >= 100) ? 0f : 16f;
        float frequency = 0.02f;

        for (float x = tankBounds.left; x <= tankBounds.right; x += 10f) {
            float y = waterHeight + (float) Math.sin((x * frequency) + wavePhase) * amplitude;
            wavePath.lineTo(x, y);
        }

        wavePath.lineTo(tankBounds.right, tankBounds.bottom);
        wavePath.close();

        int colorStart = Color.parseColor("#29B6F6");
        int colorEnd = Color.parseColor("#0288D1");
        if (waterPercent <= 10.0f) {
            colorStart = Color.parseColor("#EF5350");
            colorEnd = Color.parseColor("#C62828");
        } else if (waterPercent >= 95.0f) {
            colorStart = Color.parseColor("#FFA726");
            colorEnd = Color.parseColor("#E65100");
        }

        wavePaint.setShader(new LinearGradient(0, waterHeight, 0, tankBounds.bottom, colorStart, colorEnd, Shader.TileMode.CLAMP));
        canvas.drawPath(wavePath, wavePaint);
        canvas.restore();

        canvas.drawRoundRect(tankBounds, cornerRadius, cornerRadius, tankBorderPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (waveAnimator != null) {
            waveAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }
}
