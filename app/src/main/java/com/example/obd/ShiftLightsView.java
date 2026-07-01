package com.example.obd;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * F1-style shift-light strip — a row of 12 LEDs that light up as RPM climbs
 * toward the redline. Used at the top of the HUD dashboard.
 *
 * Color mapping (matches motorsport convention):
 *   First third  (idle → halfway to redline)        : green
 *   Second third (halfway → 95% of redline)         : yellow
 *   Final third  (95-100% of redline)               : red (with blink at redline)
 *
 * The lights are PAINTED to look like physical SMD LEDs — each LED has a colored
 * core with a soft glow halo when lit, dimmed and gray when off.
 *
 * Set RPM via setRpm(rpm). Default redline is 6700 (N52B30); override with setRedline.
 */
public class ShiftLightsView extends View {

    private static final int LED_COUNT = 12;

    private float rpm = 0f;
    private float idleRpm = 700f;
    private float redline = 6700f;
    private long blinkAtMs = 0L;

    private final Paint ledOn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledOff = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ShiftLightsView(Context c) { super(c); init(); }
    public ShiftLightsView(Context c, AttributeSet a) { super(c, a); init(); }
    public ShiftLightsView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        ledOn.setStyle(Paint.Style.FILL);
        ledGlow.setStyle(Paint.Style.FILL);
        try { ledGlow.setMaskFilter(new BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)); }
        catch (Exception ignored) {}
        ledOff.setStyle(Paint.Style.FILL);
        ledOff.setColor(0xFF202020);
        ledStroke.setStyle(Paint.Style.STROKE);
        ledStroke.setColor(0x33FFFFFF);
        ledStroke.setStrokeWidth(1f);
    }

    public void setRpm(float r) {
        this.rpm = r;
        invalidate();
    }

    public void setRedline(float r) { this.redline = r; invalidate(); }
    public void setIdleRpm(float r) { this.idleRpm = r; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // Geometry: LEDs are evenly spaced, sized to fit height with margin
        float diameter = Math.min(h * 0.65f, (w * 0.85f) / LED_COUNT);
        float spacing = (w - LED_COUNT * diameter) / (LED_COUNT + 1);
        float cy = h / 2f;
        float radius = diameter / 2f;
        float glowRadius = radius * 1.6f;

        // How many LEDs to light = fraction from idle to redline
        float range = Math.max(1f, redline - idleRpm);
        float pct = Math.max(0f, Math.min(1f, (rpm - idleRpm) / range));
        int litCount = Math.round(pct * LED_COUNT);

        // At redline, blink the entire bar
        boolean atRedline = rpm >= redline;
        boolean blinkOn = (System.currentTimeMillis() / 150) % 2 == 0;
        if (atRedline) {
            if (blinkAtMs == 0) blinkAtMs = System.currentTimeMillis();
            postInvalidateDelayed(80);
        } else {
            blinkAtMs = 0;
        }

        for (int i = 0; i < LED_COUNT; i++) {
            float cx = spacing + i * (diameter + spacing) + radius;
            boolean lit;
            if (atRedline) lit = blinkOn;
            else lit = i < litCount;

            if (lit) {
                int color = colorForIndex(i);
                ledGlow.setColor((color & 0x00FFFFFF) | 0x55000000);
                c.drawCircle(cx, cy, glowRadius, ledGlow);
                ledOn.setColor(color);
                c.drawCircle(cx, cy, radius, ledOn);
            } else {
                c.drawCircle(cx, cy, radius, ledOff);
            }
            c.drawCircle(cx, cy, radius, ledStroke);
        }
    }

    private int colorForIndex(int i) {
        // 0-3 = green, 4-7 = yellow, 8-11 = red (12 total)
        if (i < 4) return 0xFF4CAF50;
        if (i < 8) return 0xFFFFB300;
        return 0xFFF44336;
    }
}
