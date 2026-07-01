package com.example.obd;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * BMW-style semi-circular gauge. Sweep from 7-o'clock (-150°) through bottom-up
 * to 5-o'clock (+150°), 300° total. Min on the left, max on the right.
 *
 * Visual:
 *  - Outer track arc (faint white)
 *  - Filled "value" arc (BMW blue → orange → red as value approaches max)
 *  - Major tick marks + labels at multiples
 *  - Big digital readout in the centre
 *  - Label below (e.g. "RPM", "km/h")
 *  - Optional red zone at the top end (e.g. RPM redline)
 *
 * Animation: when setValue() is called, the displayed needle eases toward the
 * target at ~15 % per frame, so transient jitter is smoothed and changes look
 * organic instead of snapping.
 */
public class CircularGaugeView extends View {

    private static final float START_ANGLE = 150f;     // sweep start (bottom-left)
    private static final float SWEEP_ANGLE = 240f;     // total sweep

    private float minValue = 0f;
    private float maxValue = 100f;
    private float redlineStart = Float.NaN;            // start of red zone (optional)
    private float targetValue = 0f;
    private float displayValue = 0f;
    private String label = "";
    private String unit = "";
    private int valueDecimals = 0;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint redZonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint majorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint readoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint readoutGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF arcRect = new RectF();
    private long startupAtMs = 0L;
    private static final int STARTUP_DURATION_MS = 900;

    public CircularGaugeView(Context ctx) { super(ctx); init(); }
    public CircularGaugeView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public CircularGaugeView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(0x22FFFFFF);

        valuePaint.setStyle(Paint.Style.STROKE);
        valuePaint.setStrokeCap(Paint.Cap.ROUND);
        valuePaint.setColor(0xFF03DAC5);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        redZonePaint.setStyle(Paint.Style.STROKE);
        redZonePaint.setStrokeCap(Paint.Cap.BUTT);
        redZonePaint.setColor(0x66FF1A1A);

        majorTickPaint.setColor(0xFFFFFFFF);
        majorTickPaint.setStrokeWidth(3f);
        majorTickPaint.setStrokeCap(Paint.Cap.ROUND);

        minorTickPaint.setColor(0x66FFFFFF);
        minorTickPaint.setStrokeWidth(1.5f);

        tickLabelPaint.setColor(0xB3FFFFFF);
        tickLabelPaint.setTextAlign(Paint.Align.CENTER);
        tickLabelPaint.setLetterSpacing(0.05f);

        readoutPaint.setColor(0xFFFFFFFF);
        readoutPaint.setTextAlign(Paint.Align.CENTER);
        readoutPaint.setFakeBoldText(true);

        readoutGlowPaint.setColor(0xFFFFFFFF);
        readoutGlowPaint.setTextAlign(Paint.Align.CENTER);
        readoutGlowPaint.setFakeBoldText(true);
        readoutGlowPaint.setMaskFilter(new BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL));

        unitPaint.setColor(0x99FFFFFF);
        unitPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setLetterSpacing(0.15f);

        labelPaint.setColor(0xFF0066B1);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
        labelPaint.setLetterSpacing(0.20f);

        centerBgPaint.setStyle(Paint.Style.FILL);

        startupAtMs = System.currentTimeMillis();
    }

    public void setRange(float min, float max) {
        this.minValue = min;
        this.maxValue = max;
        invalidate();
    }

    public void setRedlineStart(float v) { this.redlineStart = v; invalidate(); }

    public void setLabel(String label) { this.label = label; invalidate(); }

    public void setUnit(String unit) { this.unit = unit; invalidate(); }

    public void setValueDecimals(int n) { this.valueDecimals = n; invalidate(); }

    public void setValue(float v) {
        this.targetValue = clamp(v, minValue, maxValue);
        if (Float.compare(displayValue, 0f) == 0 && Float.compare(targetValue, 0f) != 0) {
            // First non-zero value snaps in; subsequent updates animate.
            displayValue = targetValue;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        // Startup sweep animation — gauges sweep from 0 to target on first attach.
        // After STARTUP_DURATION_MS we fall through to normal easing.
        long elapsed = System.currentTimeMillis() - startupAtMs;
        if (elapsed < STARTUP_DURATION_MS) {
            float t = elapsed / (float) STARTUP_DURATION_MS;
            // Ease-out cubic
            float ease = 1f - (float) Math.pow(1f - t, 3);
            displayValue = targetValue * ease;
            postInvalidateOnAnimation();
        } else {
            displayValue += (targetValue - displayValue) * 0.18f;
            if (Math.abs(displayValue - targetValue) > 0.05f) postInvalidateOnAnimation();
        }

        int w = getWidth();
        int h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f + size * 0.05f;
        float stroke = size * 0.075f;
        float radius = size * 0.40f;

        trackPaint.setStrokeWidth(stroke);
        valuePaint.setStrokeWidth(stroke);
        glowPaint.setStrokeWidth(stroke * 1.6f);
        redZonePaint.setStrokeWidth(stroke);

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // Radial gradient backdrop — subtle glow centred on the gauge
        int valueColor = colorForValue(displayValue);
        int glowColor = (valueColor & 0x00FFFFFF) | 0x33000000;
        centerBgPaint.setShader(new RadialGradient(cx, cy, radius * 1.2f,
                glowColor, 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, radius * 1.2f, centerBgPaint);

        // Outer track
        c.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint);

        // Red zone (optional)
        if (!Float.isNaN(redlineStart) && redlineStart < maxValue) {
            float redStart = START_ANGLE + valueToAngle(redlineStart);
            float redSweep = valueToAngle(maxValue) - valueToAngle(redlineStart);
            c.drawArc(arcRect, redStart, redSweep, false, redZonePaint);
        }

        // Value arc with neon glow underneath
        valuePaint.setColor(valueColor);
        glowPaint.setColor((valueColor & 0x00FFFFFF) | 0x55000000);
        try { glowPaint.setMaskFilter(new BlurMaskFilter(stroke * 0.6f, BlurMaskFilter.Blur.NORMAL)); }
        catch (Exception ignored) {}
        float sweep = valueToAngle(displayValue);
        if (sweep > 0.1f) {
            c.drawArc(arcRect, START_ANGLE, sweep, false, glowPaint);
            c.drawArc(arcRect, START_ANGLE, sweep, false, valuePaint);
        }

        // Tick marks + labels
        drawTicks(c, cx, cy, radius, stroke, size);

        // Digital readout
        readoutPaint.setTextSize(size * 0.22f);
        readoutGlowPaint.setTextSize(size * 0.22f);
        readoutGlowPaint.setColor(valueColor);
        unitPaint.setTextSize(size * 0.08f);
        labelPaint.setTextSize(size * 0.07f);

        String text = formatNumber(displayValue);
        c.drawText(text, cx, cy + readoutPaint.getTextSize() * 0.35f, readoutGlowPaint);
        c.drawText(text, cx, cy + readoutPaint.getTextSize() * 0.35f, readoutPaint);
        c.drawText(unit, cx, cy + readoutPaint.getTextSize() * 0.85f, unitPaint);
        c.drawText(label, cx, cy - radius * 0.18f, labelPaint);
    }

    private float valueToAngle(float v) {
        if (maxValue == minValue) return 0;
        float pct = (v - minValue) / (maxValue - minValue);
        return clamp(pct, 0f, 1f) * SWEEP_ANGLE;
    }

    private int colorForValue(float v) {
        float pct = (v - minValue) / Math.max(0.001f, (maxValue - minValue));
        pct = clamp(pct, 0f, 1f);
        // Cyan (00DAC5) at 0 → BMW blue (0066B1) at 0.5 → Orange (FFB300) at 0.85 → Red (F44336) at 1.0
        if (pct < 0.5f) {
            return blend(0xFF03DAC5, 0xFF0066B1, pct / 0.5f);
        } else if (pct < 0.85f) {
            return blend(0xFF0066B1, 0xFFFFB300, (pct - 0.5f) / 0.35f);
        } else {
            return blend(0xFFFFB300, 0xFFF44336, (pct - 0.85f) / 0.15f);
        }
    }

    private static int blend(int c1, int c2, float t) {
        t = clamp(t, 0f, 1f);
        int a = lerp(Color.alpha(c1), Color.alpha(c2), t);
        int r = lerp(Color.red(c1), Color.red(c2), t);
        int g = lerp(Color.green(c1), Color.green(c2), t);
        int b = lerp(Color.blue(c1), Color.blue(c2), t);
        return Color.argb(a, r, g, b);
    }

    private static int lerp(int a, int b, float t) {
        return (int) (a + (b - a) * t);
    }

    private void drawTicks(Canvas c, float cx, float cy, float radius, float stroke, float size) {
        tickLabelPaint.setTextSize(size * 0.055f);
        int majorTicks = 6;
        int minorPerMajor = 5; // 5 minor ticks between each pair of majors

        // Minor ticks first (so majors draw on top)
        int totalMinor = majorTicks * minorPerMajor;
        for (int i = 0; i <= totalMinor; i++) {
            if (i % minorPerMajor == 0) continue; // skip positions where a major sits
            float frac = i / (float) totalMinor;
            float angle = START_ANGLE + frac * SWEEP_ANGLE;
            double rad = Math.toRadians(angle);
            float inner = radius - stroke * 0.5f;
            float outer = radius - stroke * 0.25f;
            float x1 = cx + (float) Math.cos(rad) * inner;
            float y1 = cy + (float) Math.sin(rad) * inner;
            float x2 = cx + (float) Math.cos(rad) * outer;
            float y2 = cy + (float) Math.sin(rad) * outer;
            c.drawLine(x1, y1, x2, y2, minorTickPaint);
        }

        // Major ticks + labels
        for (int i = 0; i <= majorTicks; i++) {
            float frac = i / (float) majorTicks;
            float angle = START_ANGLE + frac * SWEEP_ANGLE;
            double rad = Math.toRadians(angle);
            float inner = radius - stroke * 0.9f;
            float outer = radius - stroke * 0.2f;
            float x1 = cx + (float) Math.cos(rad) * inner;
            float y1 = cy + (float) Math.sin(rad) * inner;
            float x2 = cx + (float) Math.cos(rad) * outer;
            float y2 = cy + (float) Math.sin(rad) * outer;
            c.drawLine(x1, y1, x2, y2, majorTickPaint);

            // Label outside the arc
            float labelR = radius + stroke * 0.45f;
            float tx = cx + (float) Math.cos(rad) * labelR;
            float ty = cy + (float) Math.sin(rad) * labelR + tickLabelPaint.getTextSize() * 0.35f;
            float labelV = minValue + frac * (maxValue - minValue);
            String txt = (labelV >= 1000) ? String.format("%.0fk", labelV / 1000f)
                                          : String.format("%.0f", labelV);
            // Redline major ticks get red labels
            if (!Float.isNaN(redlineStart) && labelV >= redlineStart) {
                tickLabelPaint.setColor(0xFFF44336);
            } else {
                tickLabelPaint.setColor(0xB3FFFFFF);
            }
            c.drawText(txt, tx, ty, tickLabelPaint);
        }
    }

    private String formatNumber(float v) {
        if (valueDecimals == 0) return String.format("%.0f", v);
        if (valueDecimals == 1) return String.format("%.1f", v);
        return String.format("%." + valueDecimals + "f", v);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
