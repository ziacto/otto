package com.example.obd;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

/**
 * Live engine-bay schematic that animates with real-time OBD readings.
 *
 * Driven by {@link #setRpm(double)}, {@link #setCoolantTemp(double)},
 * {@link #setMaf(double)}, {@link #setOilTemp(double)} — the dashboard pushes raw
 * ECU values in and a Choreographer tick (~60 fps) smoothly interpolates the
 * rendered state toward those targets, so motion is buttery even when the ECU
 * polls at 2 Hz.
 *
 * Visuals (all procedural, no drawables):
 *   • 6 coil-packs strobe in BMW M52/N52 firing order (1-5-3-6-2-4), pulse rate ∝ RPM
 *   • Radiator/reservoir glow tints from cool blue → warm red as coolant climbs
 *   • Animated intake-manifold dashes flow left→right at a speed proportional to MAF
 *   • Exhaust manifold tint warms with oil temp
 *
 * The view stops its tick on detach so it doesn't leak.
 */
public class CarEngineAnimationView extends View implements Choreographer.FrameCallback {

    // --- live targets (raw ECU values) ---
    private volatile double targetRpm = 0;
    private volatile double targetCoolant = Double.NaN;
    private volatile double targetMaf = 0;
    private volatile double targetOilTemp = Double.NaN;

    // --- smoothed displayed values ---
    private double dispRpm = 0;
    private double dispCoolant = 20;
    private double dispMaf = 0;
    private double dispOil = 20;

    // --- animation phases (radians) ---
    private float strobePhase = 0f;
    private float intakePhase = 0f;
    private long lastFrameNs = 0L;

    private final Paint paintFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintCoil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintIntake = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintText = new Paint(Paint.ANTI_ALIAS_FLAG);

    // BMW N52 firing order — used to phase the 6 coil pulses
    private static final int[] FIRING_ORDER = { 0, 4, 2, 5, 1, 3 };

    public CarEngineAnimationView(Context c) { super(c); init(); }
    public CarEngineAnimationView(Context c, AttributeSet a) { super(c, a); init(); }
    public CarEngineAnimationView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        paintStroke.setStyle(Paint.Style.STROKE);
        paintStroke.setStrokeWidth(dp(1f));
        paintStroke.setColor(0xFF03DAC5);
        paintFill.setStyle(Paint.Style.FILL);
        paintCoil.setStyle(Paint.Style.FILL);
        paintIntake.setStyle(Paint.Style.STROKE);
        paintIntake.setStrokeWidth(dp(2f));
        paintIntake.setColor(0xFF03DAC5);
        paintText.setColor(0x80FFFFFF);
        paintText.setTextSize(dp(9f));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override public void doFrame(long frameTimeNanos) {
        float dtSec = lastFrameNs == 0L ? 1f / 60f
                : Math.min(0.1f, (frameTimeNanos - lastFrameNs) / 1_000_000_000f);
        lastFrameNs = frameTimeNanos;

        // Exponential smoothing toward the latest ECU targets — α ≈ 0.18 per frame at 60 fps
        // gives a visual time-constant of about 80 ms, which absorbs ECU jitter without
        // feeling sluggish on the eye.
        dispRpm     = ema(dispRpm, targetRpm, 0.18);
        if (!Double.isNaN(targetCoolant)) dispCoolant = ema(dispCoolant, targetCoolant, 0.05);
        dispMaf     = ema(dispMaf, targetMaf, 0.18);
        if (!Double.isNaN(targetOilTemp)) dispOil = ema(dispOil, targetOilTemp, 0.05);

        // Strobe phase rate: idle 700 rpm ≈ 1.4 Hz on a 4-stroke 6-cyl (3 power strokes/rev).
        // Pulses per second = (rpm / 60) * 3 = rpm / 20.
        float pulseHz = (float) Math.max(0.5, dispRpm / 20.0);
        strobePhase = (strobePhase + dtSec * pulseHz) % FIRING_ORDER.length;

        // Intake dash drift: MAF g/s → dash offset px/sec. 0 g/s = still, 30 g/s ≈ 60 px/s.
        intakePhase += (float) (dpRaw(2) + dispMaf * 1.8f) * dtSec;
        if (intakePhase > 1000f) intakePhase -= 1000f;

        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Background bay
        paintFill.setColor(0xFF101522);
        canvas.drawRoundRect(0, 0, w, h, dp(8), dp(8), paintFill);

        // Reference frame: 16-wide × 12-tall units
        float ux = w / 16f, uy = h / 12f;

        drawAirbox(canvas, ux, uy);
        drawReservoirGlow(canvas, ux, uy);
        drawExhaust(canvas, ux, uy);
        drawValveCover(canvas, ux, uy);
        drawCoils(canvas, ux, uy);
        drawIntakeFlow(canvas, ux, uy);
        drawLabels(canvas, ux, uy);
    }

    // --- visual elements ---

    private void drawAirbox(Canvas c, float ux, float uy) {
        paintFill.setColor(0xFF1B2030);
        c.drawRoundRect(ux * 0.6f, uy * 3f, ux * 3.4f, uy * 6f, dp(3), dp(3), paintFill);
        c.drawRoundRect(ux * 0.6f, uy * 3f, ux * 3.4f, uy * 6f, dp(3), dp(3), paintStroke);
        paintText.setColor(0x60FFFFFF);
        c.drawText("AIR", ux * 1.3f, uy * 4.6f, paintText);
    }

    private void drawValveCover(Canvas c, float ux, float uy) {
        paintFill.setColor(0xFF0E0E1A);
        c.drawRoundRect(ux * 4f, uy * 3f, ux * 12f, uy * 7.2f, dp(4), dp(4), paintFill);
        c.drawRoundRect(ux * 4f, uy * 3f, ux * 12f, uy * 7.2f, dp(4), dp(4), paintStroke);
        paintText.setColor(0x70FFFFFF);
        c.drawText("N52 · INLINE-6", ux * 4.3f, uy * 3.6f, paintText);
    }

    private void drawCoils(Canvas c, float ux, float uy) {
        float top = uy * 3.6f;
        float bottom = uy * 6.6f;
        float coilW = ux * 1.0f;
        float gap = ux * 0.33f;
        float startX = ux * 4.4f;

        for (int i = 0; i < 6; i++) {
            float x = startX + i * (coilW + gap);
            // Strobe intensity: phase position relative to this cylinder's firing slot
            int slot = indexOf(FIRING_ORDER, i);
            float phaseDiff = (strobePhase - slot + FIRING_ORDER.length) % FIRING_ORDER.length;
            // Bell-shaped spike around phaseDiff≈0; widens at higher load
            float spike = (float) Math.exp(-phaseDiff * phaseDiff * 6.0);
            int alpha = (int) (90 + 165 * spike);
            paintCoil.setColor(((alpha & 0xFF) << 24) | 0x03DAC5);
            c.drawRoundRect(x, top, x + coilW, bottom, dp(2), dp(2), paintCoil);
        }
    }

    private void drawIntakeFlow(Canvas c, float ux, float uy) {
        // Intake manifold base
        paintFill.setColor(0xFF161D2D);
        c.drawRoundRect(ux * 4f, uy * 7.6f, ux * 12f, uy * 8.8f, dp(2), dp(2), paintFill);
        c.drawRoundRect(ux * 4f, uy * 7.6f, ux * 12f, uy * 8.8f, dp(2), dp(2), paintStroke);

        // Animated dashes flowing left→right; speed and density scale with MAF
        float y = uy * 8.2f;
        float dashLen = dp(8);
        float dashGap = dp(6);
        float period = dashLen + dashGap;
        float startX = ux * 4.1f - (intakePhase % period);
        float endX = ux * 11.9f;

        paintIntake.setColor(0xCC03DAC5);
        paintIntake.setStrokeWidth(dp(2f));
        for (float x = startX; x < endX; x += period) {
            float x0 = Math.max(x, ux * 4.1f);
            float x1 = Math.min(x + dashLen, endX);
            if (x1 > x0) c.drawLine(x0, y, x1, y, paintIntake);
        }
    }

    private void drawReservoirGlow(Canvas c, float ux, float uy) {
        float cx = ux * 13.7f;
        float cy = uy * 4.5f;
        float r = ux * 1.4f;

        // Glow color map: 70°C cool blue → 100°C neutral → 110°C amber → 120°C+ red
        int glowColor = coolantColor(dispCoolant);
        RadialGradient g = new RadialGradient(cx, cy, r, glowColor, 0x00000000,
                Shader.TileMode.CLAMP);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(g);
        c.drawCircle(cx, cy, r, glow);

        paintFill.setColor(0xFF1B2030);
        c.drawRoundRect(ux * 12.6f, uy * 3f, ux * 15f, uy * 6f, dp(3), dp(3), paintFill);
        c.drawRoundRect(ux * 12.6f, uy * 3f, ux * 15f, uy * 6f, dp(3), dp(3), paintStroke);
        paintText.setColor(0x60FFFFFF);
        c.drawText("COOLANT", ux * 12.85f, uy * 4.6f, paintText);
    }

    private void drawExhaust(Canvas c, float ux, float uy) {
        // Exhaust manifold (bottom strip) — color warms with oil temp
        int tint = oilColor(dispOil);
        paintFill.setColor(tint);
        c.drawRoundRect(ux * 4f, uy * 9.2f, ux * 12f, uy * 10.4f, dp(2), dp(2), paintFill);
        c.drawRoundRect(ux * 4f, uy * 9.2f, ux * 12f, uy * 10.4f, dp(2), dp(2), paintStroke);
        paintText.setColor(0x80FFFFFF);
        c.drawText("EXHAUST", ux * 4.3f, uy * 10f, paintText);
    }

    private void drawLabels(Canvas c, float ux, float uy) {
        paintText.setColor(0xC0FFFFFF);
        paintText.setTextSize(dp(8.5f));
        c.drawText(String.format("RPM %.0f", dispRpm), ux * 0.6f, uy * 2.2f, paintText);
        if (!Double.isNaN(targetCoolant)) {
            c.drawText(String.format("COOL %.0f°C", dispCoolant), ux * 6f, uy * 2.2f, paintText);
        }
        c.drawText(String.format("MAF %.1f g/s", dispMaf), ux * 11f, uy * 2.2f, paintText);
    }

    // --- color maps ---

    private static int coolantColor(double t) {
        // 60°C cold blue → 90°C teal → 105°C amber → 115°C+ red
        if (t < 60) return 0x402979FF;
        if (t < 90) return lerpColor(0x402979FF, 0x4003DAC5, (t - 60) / 30.0);
        if (t < 105) return lerpColor(0x4003DAC5, 0x66FFB300, (t - 90) / 15.0);
        if (t < 115) return lerpColor(0x66FFB300, 0xAAF44336, (t - 105) / 10.0);
        return 0xCCF44336;
    }

    private static int oilColor(double t) {
        // 60°C neutral grey → 95°C teal → 120°C amber → 140°C red
        if (t < 60) return 0xFF2A2F40;
        if (t < 95) return lerpColor(0xFF2A2F40, 0xFF1F4A48, (t - 60) / 35.0);
        if (t < 120) return lerpColor(0xFF1F4A48, 0xFF5A4520, (t - 95) / 25.0);
        if (t < 140) return lerpColor(0xFF5A4520, 0xFF6A2222, (t - 120) / 20.0);
        return 0xFF6A2222;
    }

    private static int lerpColor(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int oa = (int) (aa + (ba - aa) * t);
        int or = (int) (ar + (br - ar) * t);
        int og = (int) (ag + (bg - ag) * t);
        int ob = (int) (ab + (bb - ab) * t);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }

    // --- public setters (called from the UI thread by DashboardController) ---

    public void setRpm(double v)         { targetRpm = v; }
    public void setCoolantTemp(double v) { targetCoolant = v; }
    public void setMaf(double v)         { targetMaf = v; }
    public void setOilTemp(double v)     { targetOilTemp = v; }

    // --- helpers ---

    private static double ema(double cur, double target, double alpha) {
        return cur + (target - cur) * alpha;
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    private float dp(float v)   { return v * getResources().getDisplayMetrics().density; }
    private float dpRaw(float v){ return v * getResources().getDisplayMetrics().density; }

    @SuppressWarnings("unused")
    private static final PorterDuffXfermode XFER_SRC_OVER = new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER);
    @SuppressWarnings("unused")
    private static int withAlpha(int c, int a) { return (a << 24) | (c & 0x00FFFFFF); }
    @SuppressWarnings("unused")
    private static int dim() { return Color.argb(128, 0, 0, 0); }
}
