package com.example.obd;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Grid of LED-style cells showing which D-CAN modules are online.
 *
 * Each cell:
 *   - Module abbreviation (e.g. "DME", "DSC")
 *   - LED dot below — color reflects state:
 *       GREY   = unknown (no scan run yet)
 *       GREEN  = module responded
 *       AMBER  = module responded with fault codes
 *       RED    = module did not respond (probably not present)
 *
 * Designed to fit at the bottom of the HUD dashboard. Visually quiet when
 * everything is OK; instantly noticeable when something goes amber/red.
 */
public class ModuleStatusGridView extends GridLayout {

    public enum State {
        UNKNOWN(0xFF333333, 0xFF666666),
        ONLINE(0xFF4CAF50, 0xFF4CAF50),
        FAULT(0xFFFFB300, 0xFFFFB300),
        OFFLINE(0xFFF44336, 0xFFF44336);

        final int dotColor;
        final int strokeColor;
        State(int dot, int stroke) { this.dotColor = dot; this.strokeColor = stroke; }
    }

    private final Map<BmwModule, View> cells = new HashMap<>();

    public ModuleStatusGridView(Context c) { super(c); init(); }
    public ModuleStatusGridView(Context c, AttributeSet a) { super(c, a); init(); }
    public ModuleStatusGridView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        setColumnCount(4);
        setUseDefaultMargins(false);
        setPadding(dp(4), dp(4), dp(4), dp(4));
        rebuild();
    }

    private void rebuild() {
        removeAllViews();
        cells.clear();
        for (BmwModule m : BmwModule.dCanModules()) addCell(m);
    }

    private void addCell(BmwModule module) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(6), dp(6), dp(6), dp(6));

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        cell.setLayoutParams(lp);

        cell.setBackground(makeBg(0x33222222, 0xFF444444));

        TextView name = new TextView(getContext());
        name.setText(module.name());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(0xFFFFFFFF);
        name.setLetterSpacing(0.08f);
        name.setGravity(Gravity.CENTER);
        cell.addView(name);

        // LED dot (TextView with circular drawable)
        TextView dot = new TextView(getContext());
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.topMargin = dp(4);
        dot.setLayoutParams(dotLp);
        dot.setBackground(makeDot(State.UNKNOWN.dotColor));
        dot.setTag("dot");
        cell.addView(dot);

        addView(cell);
        cells.put(module, cell);
        update(module, State.UNKNOWN);
    }

    public void update(BmwModule module, State state) {
        View cell = cells.get(module);
        if (cell == null) return;
        cell.setBackground(makeBg(0x33222222, state.strokeColor));
        View dot = ((LinearLayout) cell).getChildAt(1);
        if (dot != null) dot.setBackground(makeDot(state.dotColor));
    }

    /** Reset all cells to unknown — call when starting a fresh scan. */
    public void resetAll() {
        for (BmwModule m : cells.keySet()) update(m, State.UNKNOWN);
    }

    private GradientDrawable makeBg(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(6));
        return d;
    }

    private GradientDrawable makeDot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
