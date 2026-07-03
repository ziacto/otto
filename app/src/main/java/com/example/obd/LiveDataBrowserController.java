package com.example.obd;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Live Data browser: every known sensor in one scrollable list with current
 * value, OK/WARN/OUT pill, normal range, plain-English description, and
 * BMW-specific note. Category chips at the top filter the list. Tap a row to
 * toggle the expanded description.
 *
 * <p>Replaces the 10 deleted single-category sub-screens (Speed / Temp / Fuel /
 * Electrical / Thermal / Engine / Turbo / Performance / Normal). Same data, one
 * unified surface — and now with rich descriptions per sensor.</p>
 */
public final class LiveDataBrowserController {

    private View root;
    private LinearLayout container;
    private LinearLayout chipsRow;

    private SensorInfo.Category currentFilter = null; // null = "All"
    private final Map<String, View> rowByName = new HashMap<>();
    private final Map<String, Boolean> expanded = new HashMap<>();
    private final Map<String, Double> lastValues = new LinkedHashMap<>();

    // Set by attach(). Tapping a category chip narrows the visible rows AND asks
    // the host (MainActivity) to swap the OBD poll group so only that system's
    // PIDs are polled. Null-safe: if no switcher is wired, chips still filter.
    private Consumer<PollGroup> pollGroupSwitcher;

    /** Attach to an inflated layout_livedata.xml. */
    public void attach(View view, SensorInfo.Category initialFilter,
                       Consumer<PollGroup> switcher) {
        this.root = view;
        this.container = view.findViewById(R.id.liveRowContainer);
        this.chipsRow = view.findViewById(R.id.liveCategoryChips);
        this.currentFilter = initialFilter;
        this.pollGroupSwitcher = switcher;

        buildChips();
        rebuildRows();
    }

    public void detach() {
        root = null;
        container = null;
        chipsRow = null;
        pollGroupSwitcher = null;
        rowByName.clear();
        // Keep lastValues + expanded across detach/attach so re-opening the
        // screen restores the prior view state.
    }

    public boolean isAttached() { return root != null; }

    /** Forwarded from MainActivity.updateUI — push a fresh value into the row. */
    public void onValue(String name, double value) {
        lastValues.put(name, value);
        if (container == null) return;
        View row = rowByName.get(name);
        if (row != null) applyValueToRow(row, name, value);
    }

    // --- chips ---

    private void buildChips() {
        chipsRow.removeAllViews();
        addChip("All", null);
        for (SensorInfo.Category cat : SensorInfo.allCategories()) addChip(cat.label, cat);
    }

    private void addChip(String label, SensorInfo.Category cat) {
        Context ctx = chipsRow.getContext();
        TextView chip = new TextView(ctx);
        chip.setText(label);
        // Match the Drive / Diagnose chip sizing (12sp, 14dp horizontal padding)
        // so all three chip surfaces read as one component.
        chip.setTextSize(12f);
        chip.setTypeface(null, android.graphics.Typeface.BOLD);
        chip.setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6));
        boolean active = (cat == currentFilter);
        chip.setBackgroundResource(active
                ? R.drawable.status_pill_ok
                : R.drawable.status_chip_neutral);
        chip.setTextColor(active ? 0xFFFFFFFF : 0xFF9AA0AC);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(ctx, 6));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            currentFilter = cat;
            // Swap the poll group to match the chosen category so the adapter
            // only spends cycles on that system's PIDs (or the broad dashboard
            // group for "All"). Filtering rows alone would keep polling every
            // sensor, so the narrow-category rows would update just as slowly.
            if (pollGroupSwitcher != null) pollGroupSwitcher.accept(pollGroupFor(cat));
            buildChips();
            rebuildRows();
        });
        chipsRow.addView(chip);
    }

    /**
     * Map a Live Data category to the poll group that covers its PIDs. The "All"
     * chip (cat == null) falls back to the broad dashboard group, which polls
     * the sensors most owners care about across every system.
     */
    private static PollGroup pollGroupFor(SensorInfo.Category cat) {
        if (cat == null) return PollGroup.GROUP_DASHBOARD;
        switch (cat) {
            case POWERTRAIN:  return PollGroup.GROUP_LIVE_POWERTRAIN;
            case THERMAL:     return PollGroup.GROUP_LIVE_THERMAL;
            case FUEL:        return PollGroup.GROUP_LIVE_FUEL;
            case ELECTRICAL:  return PollGroup.GROUP_LIVE_ELECTRICAL;
            case PERFORMANCE: return PollGroup.GROUP_LIVE_PERFORMANCE;
            case EMISSIONS:   return PollGroup.GROUP_LIVE_EMISSIONS;
            default:          return PollGroup.GROUP_DASHBOARD;
        }
    }

    // --- rows ---

    private void rebuildRows() {
        container.removeAllViews();
        rowByName.clear();
        LayoutInflater inf = LayoutInflater.from(container.getContext());
        List<String> names = currentFilter == null
                ? SensorInfo.allNames()
                : SensorInfo.namesInCategory(currentFilter);
        for (String name : names) {
            SensorInfo.Spec spec = SensorInfo.spec(name);
            if (spec == null) continue;
            View row = inf.inflate(R.layout.row_live_sensor, container, false);
            populateStatic(row, spec);
            container.addView(row);
            rowByName.put(name, row);
            Double v = lastValues.get(name);
            applyValueToRow(row, name, v == null ? Double.NaN : v);
            row.setOnClickListener(v2 -> toggleExpand(name, row, spec));
            if (Boolean.TRUE.equals(expanded.get(name))) applyExpanded(row, spec, true);
        }
    }

    private void populateStatic(View row, SensorInfo.Spec spec) {
        ((TextView) row.findViewById(R.id.rowSensorName)).setText(spec.name);
        ((TextView) row.findViewById(R.id.rowSensorUnit)).setText(spec.unit);
        TextView range = row.findViewById(R.id.rowSensorRange);
        if (spec.maxNormal >= Double.MAX_VALUE / 2) {
            range.setText("Normal: ≥" + fmt(spec.minNormal) + " " + spec.unit);
        } else {
            range.setText("Normal: " + fmt(spec.minNormal) + "–" + fmt(spec.maxNormal)
                    + " " + spec.unit);
        }
        ((TextView) row.findViewById(R.id.rowSensorDescription)).setText(spec.description);
        ((TextView) row.findViewById(R.id.rowSensorBmwNote)).setText("BMW: " + spec.bmwNote);
    }

    private void applyValueToRow(View row, String name, double value) {
        TextView valueTv = row.findViewById(R.id.rowSensorValue);
        TextView statusTv = row.findViewById(R.id.rowSensorStatus);
        SensorInfo.Spec spec = SensorInfo.spec(name);
        if (spec == null) return;
        if (Double.isNaN(value)) {
            valueTv.setText("—");
            statusTv.setVisibility(View.INVISIBLE);
            return;
        }
        valueTv.setText(formatValue(spec, value));
        SensorInfo.Status st = spec.statusOf(value);
        statusTv.setVisibility(View.VISIBLE);
        switch (st) {
            case OK:
                statusTv.setText("OK");
                statusTv.setBackgroundResource(R.drawable.status_pill_ok);
                statusTv.setTextColor(0xFFFFFFFF);
                break;
            case WARN:
                statusTv.setText("WARN");
                statusTv.setBackgroundResource(R.drawable.status_pill_warn);
                statusTv.setTextColor(0xFF000000);
                break;
            case OUT_OF_RANGE:
                statusTv.setText("OUT");
                statusTv.setBackgroundResource(R.drawable.status_pill_err);
                statusTv.setTextColor(0xFFFFFFFF);
                break;
            default:
                statusTv.setVisibility(View.INVISIBLE);
        }
    }

    private void toggleExpand(String name, View row, SensorInfo.Spec spec) {
        boolean now = !Boolean.TRUE.equals(expanded.get(name));
        expanded.put(name, now);
        applyExpanded(row, spec, now);
    }

    private void applyExpanded(View row, SensorInfo.Spec spec, boolean on) {
        row.findViewById(R.id.rowSensorDescription).setVisibility(on ? View.VISIBLE : View.GONE);
        row.findViewById(R.id.rowSensorBmwNote).setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private static String formatValue(SensorInfo.Spec s, double v) {
        if ("%".equals(s.unit) || "°".equals(s.unit) || "°C".equals(s.unit)
                || "km/h".equals(s.unit) || "rpm".equals(s.unit) || "s".equals(s.unit)) {
            return String.format(Locale.US, "%.0f", v);
        }
        return String.format(Locale.US, "%.2f", v);
    }

    private static String fmt(double v) {
        if (v == Math.floor(v)) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.1f", v);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
