package com.example.obd;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Horizontal bar gauge with coloured zones — for temperatures, fuel, load, etc.
 *
 * Visual:
 *  - Track (faint grey) full width
 *  - Filled portion (zone-coloured)
 *  - Optional cold/hot threshold lines
 *  - Label on top-left, value+unit on top-right
 *  - Small min/max labels under the bar
 *
 * Use cases on E65 dashboard:
 *  - Coolant temp 60-115 °C (warn at 95, hot at 105)
 *  - Oil temp 80-130 °C (warn at 110, hot at 125)
 *  - Fuel level 0-100 %
 *  - Engine load 0-100 %
 */
public class BarGaugeView extends View {

    private float minValue = 0f;
    private float maxValue = 100f;
    private float coldThreshold = Float.NaN;
    private float warnThreshold = Float.NaN;
    private float hotThreshold = Float.NaN;
    private float targetValue = 0f;
    private float displayValue = 0f;
    private String label = "";
    private String unit = "";
    private int valueDecimals = 1;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minMaxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF barRect = new RectF();
    private final RectF fillRect = new RectF();
    private long startupAtMs = System.currentTimeMillis();
    private static final int STARTUP_DURATION_MS = 700;

    public BarGaugeView(Context ctx) { super(ctx); init(); }
    public BarGaugeView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public BarGaugeView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        trackPaint.setColor(0x22FFFFFF);
        fillPaint.setColor(0xFF03DAC5);
        glowPaint.setColor(0x4403DAC5);
        try { glowPaint.setMaskFilter(new BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)); }
        catch (Exception ignored) {}
        markerPaint.setColor(0x99FFFFFF);
        markerPaint.setStrokeWidth(2f);
        labelPaint.setColor(0xFF0066B1);
        labelPaint.setFakeBoldText(true);
        labelPaint.setLetterSpacing(0.12f);
        valuePaint.setColor(0xFFFFFFFF);
        valuePaint.setTextAlign(Paint.Align.RIGHT);
        valuePaint.setFakeBoldText(true);
        valueGlowPaint.setTextAlign(Paint.Align.RIGHT);
        valueGlowPaint.setFakeBoldText(true);
        try { valueGlowPaint.setMaskFilter(new BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)); }
        catch (Exception ignored) {}
        minMaxPaint.setColor(0x66FFFFFF);
    }

    public void setRange(float min, float max) {
        this.minValue = min;
        this.maxValue = max;
        invalidate();
    }

    public void setThresholds(float cold, float warn, float hot) {
        this.coldThreshold = cold;
        this.warnThreshold = warn;
        this.hotThreshold = hot;
        invalidate();
    }

    public void setLabel(String l) { this.label = l; invalidate(); }
    public void setUnit(String u) { this.unit = u; invalidate(); }
    public void setValueDecimals(int n) { this.valueDecimals = n; invalidate(); }

    public void setValue(float v) {
        this.targetValue = clamp(v, minValue, maxValue);
        if (displayValue == 0f && targetValue != 0f) displayValue = targetValue;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        // Startup sweep then normal easing
        long elapsed = System.currentTimeMillis() - startupAtMs;
        if (elapsed < STARTUP_DURATION_MS) {
            float t = elapsed / (float) STARTUP_DURATION_MS;
            float ease = 1f - (float) Math.pow(1f - t, 3);
            displayValue = targetValue * ease;
            postInvalidateOnAnimation();
        } else {
            displayValue += (targetValue - displayValue) * 0.18f;
            if (Math.abs(displayValue - targetValue) > 0.05f) postInvalidateOnAnimation();
        }

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float padX = w * 0.04f;
        float topRow = h * 0.30f;
        float barTop = h * 0.40f;
        float barBottom = h * 0.72f;
        float bottomRow = h * 0.95f;

        labelPaint.setTextSize(h * 0.20f);
        valuePaint.setTextSize(h * 0.26f);
        valueGlowPaint.setTextSize(h * 0.26f);
        minMaxPaint.setTextSize(h * 0.13f);

        // Label (top-left)
        c.drawText(label, padX, topRow, labelPaint);
        // Value (top-right) — drawn glow first, then crisp text on top
        String txt = formatNumber(displayValue) + " " + unit;
        int color = colorForValue(displayValue);
        valuePaint.setColor(0xFFFFFFFF);
        valueGlowPaint.setColor(color);
        c.drawText(txt, w - padX, topRow, valueGlowPaint);
        c.drawText(txt, w - padX, topRow, valuePaint);

        // Bar background
        barRect.set(padX, barTop, w - padX, barBottom);
        c.drawRoundRect(barRect, h * 0.05f, h * 0.05f, trackPaint);

        // Bar fill — neon gradient + glow
        float pct = (displayValue - minValue) / Math.max(0.001f, (maxValue - minValue));
        pct = clamp(pct, 0f, 1f);
        fillPaint.setColor(color);
        glowPaint.setColor((color & 0x00FFFFFF) | 0x55000000);
        fillRect.set(barRect.left, barRect.top,
                barRect.left + (barRect.width() * pct), barRect.bottom);
        if (fillRect.width() > 1) {
            // Soft halo underneath the fill
            RectF halo = new RectF(fillRect);
            halo.inset(-h * 0.05f, -h * 0.05f);
            c.drawRoundRect(halo, h * 0.05f, h * 0.05f, glowPaint);
            // Horizontal gradient inside the fill (slightly brighter on the right edge)
            fillPaint.setShader(new LinearGradient(
                    fillRect.left, 0, fillRect.right, 0,
                    (color & 0x00FFFFFF) | 0xAA000000, color, Shader.TileMode.CLAMP));
            c.drawRoundRect(fillRect, h * 0.05f, h * 0.05f, fillPaint);
            fillPaint.setShader(null);
        }

        // Threshold tick marks
        drawMarker(c, coldThreshold, barRect);
        drawMarker(c, warnThreshold, barRect);
        drawMarker(c, hotThreshold, barRect);

        // Min/Max labels
        c.drawText(String.format("%.0f", minValue), padX, bottomRow, minMaxPaint);
        minMaxPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(String.format("%.0f", maxValue), w - padX, bottomRow, minMaxPaint);
        minMaxPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawMarker(Canvas c, float v, RectF bar) {
        if (Float.isNaN(v) || v < minValue || v > maxValue) return;
        float pct = (v - minValue) / (maxValue - minValue);
        float x = bar.left + bar.width() * pct;
        c.drawLine(x, bar.top - bar.height() * 0.2f, x, bar.bottom + bar.height() * 0.2f, markerPaint);
    }

    private int colorForValue(float v) {
        // Cool zone — teal. Below warn = cyan. Warn-hot = orange. Above hot = red.
        if (!Float.isNaN(hotThreshold) && v >= hotThreshold) return 0xFFF44336;
        if (!Float.isNaN(warnThreshold) && v >= warnThreshold) return 0xFFFFB300;
        if (!Float.isNaN(coldThreshold) && v < coldThreshold) return 0xFF0066B1;
        return 0xFF03DAC5;
    }

    private String formatNumber(float v) {
        return String.format("%." + valueDecimals + "f", v);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
