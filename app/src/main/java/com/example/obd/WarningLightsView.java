package com.example.obd;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cluster-style warning lights row — shows the same kind of icons that light up on
 * the E65 instrument cluster: MIL (check engine), Brake/DSC, Transmission, Battery.
 *
 * Visual: horizontal row of "LED" badges. Each badge has an icon + label. Color:
 *   - gray: feature monitored OK
 *   - amber: warning, attention needed
 *   - red: fault, fix soon
 *
 * Tap a badge → controller shows the why-it's-on dialog (wired from the parent
 * controller via setOnLightClickListener).
 *
 * We update the lights based on:
 *   - MIL bit from Mode 01 PID 0x01 (engine check)
 *   - DTC count > 0 (DTC counter on the right)
 *   - Module DTC scan results for DSC, EGS (set externally)
 *   - Battery voltage (warn < 12.4 at rest, < 13.5 running)
 */
public class WarningLightsView extends LinearLayout {

    public enum State { OK, WARN, FAULT, UNKNOWN }

    public enum Light {
        MIL("CHECK ENGINE", "🔧"),
        DTC_COUNT("DTCs", "⚠"),
        DSC("DSC / ABS", "🛞"),
        EGS("TRANSMISSION", "⚙"),
        BATTERY("BATTERY", "🔋"),
        TEMP("TEMP", "🌡");

        public final String label;
        public final String icon;
        Light(String l, String i) { this.label = l; this.icon = i; }
    }

    public interface OnLightClickListener {
        void onClick(Light light, State state, String detail);
    }

    private final Map<Light, View> badges = new LinkedHashMap<>();
    private final Map<Light, State> states = new LinkedHashMap<>();
    private final Map<Light, String> details = new LinkedHashMap<>();
    private OnLightClickListener listener;

    public WarningLightsView(Context ctx) { super(ctx); init(); }
    public WarningLightsView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public WarningLightsView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding(dp(4), dp(2), dp(4), dp(2));
        for (Light l : Light.values()) addBadge(l);
    }

    private void addBadge(Light light) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        col.setLayoutParams(lp);
        col.setPadding(dp(4), dp(6), dp(4), dp(6));
        col.setBackground(makeBg(0x33333333, 0xFF666666));
        col.setClickable(true);
        col.setFocusable(true);

        TextView icon = new TextView(getContext());
        icon.setText(light.icon);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        icon.setGravity(Gravity.CENTER);
        col.addView(icon);

        TextView label = new TextView(getContext());
        label.setText(light.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        label.setLetterSpacing(0.08f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(0xCCFFFFFF);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(2), 0, 0);
        col.addView(label);

        TextView valueChip = new TextView(getContext());
        valueChip.setId(View.generateViewId());
        valueChip.setTag("value");
        valueChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        valueChip.setTypeface(Typeface.DEFAULT_BOLD);
        valueChip.setTextColor(0xFFFFFFFF);
        valueChip.setGravity(Gravity.CENTER);
        valueChip.setPadding(0, dp(2), 0, 0);
        valueChip.setVisibility(GONE);
        col.addView(valueChip);

        col.setOnClickListener(v -> {
            if (listener != null) listener.onClick(light, states.getOrDefault(light, State.UNKNOWN),
                    details.getOrDefault(light, ""));
        });

        badges.put(light, col);
        states.put(light, State.UNKNOWN);
        addView(col);
    }

    private GradientDrawable makeBg(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(6));
        return d;
    }

    public void setOnLightClickListener(OnLightClickListener l) { this.listener = l; }

    /** Update a light's state, optional value chip text, and detail string for the tap-info dialog. */
    public void update(Light light, State state, String valueChipText, String detail) {
        View v = badges.get(light);
        if (v == null) return;
        states.put(light, state);
        if (detail != null) details.put(light, detail);
        int fill, stroke;
        int iconColor = 0xFFFFFFFF;
        switch (state) {
            case OK:      fill = 0x331DB95E; stroke = 0xFF1DB95E; break;
            case WARN:    fill = 0x33FFB300; stroke = 0xFFFFB300; iconColor = 0xFFFFB300; break;
            case FAULT:   fill = 0x33F44336; stroke = 0xFFF44336; iconColor = 0xFFF44336; break;
            default:      fill = 0x33333333; stroke = 0xFF666666;
        }
        v.setBackground(makeBg(fill, stroke));
        TextView iconView = (TextView) ((LinearLayout) v).getChildAt(0);
        iconView.setTextColor(iconColor);

        TextView valueChip = (TextView) ((LinearLayout) v).getChildAt(2);
        if (valueChipText == null || valueChipText.isEmpty()) {
            valueChip.setVisibility(GONE);
        } else {
            valueChip.setText(valueChipText);
            valueChip.setVisibility(VISIBLE);
            valueChip.setTextColor(state == State.FAULT ? 0xFFF44336
                    : (state == State.WARN ? 0xFFFFB300 : 0xFFFFFFFF));
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
