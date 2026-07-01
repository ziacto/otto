package com.example.obd;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.example.obd.db.AppDatabase;
import com.example.obd.db.PidSample;
import com.example.obd.db.ServiceItem;
import com.example.obd.db.VinProfile;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Garage screen: CBS-style maintenance ledger + 30-day single-PID trend chart
 * + CSV export of the local Room DB.
 *
 * Storage: Room (`service_item` table) per-VIN. If no VIN known yet, the
 * synthetic key "default" lets the user start logging immediately.
 */
public class GarageController {

    /** Sensors offered in the 30-day trend dropdown. Coolant first — most diagnostic value. */
    private static final String[] TREND_SENSORS = {
            "Coolant Temp", "Oil Temp", "Battery Voltage",
            "RPM", "Vehicle Speed", "Mass Air Flow"
    };

    private View root;
    private TextView content;
    private LineChart trendChart;
    private Spinner trendSpinner;
    private String currentTrendSensor = TREND_SENSORS[0];

    public void attach(View view) {
        this.root = view;
        Context ctx = view.getContext();
        content = view.findViewById(R.id.tvGarageContent);
        content.setMovementMethod(new ScrollingMovementMethod());

        Button bOil    = view.findViewById(R.id.btnGarageAddOil);
        Button bBrake  = view.findViewById(R.id.btnGarageAddBrake);
        Button bFilter = view.findViewById(R.id.btnGarageAddFilter);
        Button bPlugs  = view.findViewById(R.id.btnGarageAddPlugs);

        bOil.setOnClickListener(v    -> markDone(ctx, "Engine Oil",     12000, 365));
        bBrake.setOnClickListener(v  -> markDone(ctx, "Brake Fluid",    40000, 730));
        bFilter.setOnClickListener(v -> markDone(ctx, "Cabin Filter",   20000, 365));
        bPlugs.setOnClickListener(v  -> markDone(ctx, "Spark Plugs",    80000, 1825));

        trendChart = view.findViewById(R.id.garageTrendChart);
        setupChart();

        trendSpinner = view.findViewById(R.id.spinnerTrendPid);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                ctx, android.R.layout.simple_spinner_item, TREND_SENSORS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        trendSpinner.setAdapter(adapter);
        trendSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                currentTrendSensor = TREND_SENSORS[pos];
                refreshChart(ctx);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        Button btnExport = view.findViewById(R.id.btnExportCsv);
        if (btnExport != null) btnExport.setOnClickListener(v -> exportCsv(ctx));

        refresh(ctx);
        refreshChart(ctx);
    }

    public void detach() {
        root = null;
        content = null;
        trendChart = null;
        trendSpinner = null;
    }

    private void markDone(Context ctx, String name, int intervalKm, int intervalDays) {
        WorkDispatcher.io(() -> {
            AppDatabase db = AppDatabase.get(ctx);
            String vin = currentVin(db);
            List<ServiceItem> existing = db.services().forVin(vin);
            ServiceItem item = null;
            for (ServiceItem s : existing) if (name.equals(s.name)) { item = s; break; }
            if (item == null) item = new ServiceItem();
            item.vin = vin;
            item.name = name;
            item.lastDoneAt = System.currentTimeMillis();
            item.intervalKm = intervalKm;
            item.intervalDays = intervalDays;
            db.services().upsert(item);
            refresh(ctx);
        });
    }

    private void refresh(Context ctx) {
        WorkDispatcher.io(() -> {
            AppDatabase db = AppDatabase.get(ctx);
            String vin = currentVin(db);
            List<ServiceItem> items = db.services().forVin(vin);
            int eventCount = db.events().recent(1000).size();

            StringBuilder sb = new StringBuilder();
            sb.append("VIN: ").append(vin).append("\n\n");

            if (items.isEmpty()) {
                sb.append("No service items yet. Tap a button above when you do oil/brakes/filter/plugs.\n");
            } else {
                long now = System.currentTimeMillis();
                for (ServiceItem s : items) {
                    sb.append(String.format("  %-14s ", s.name));
                    if (s.lastDoneAt == null || s.intervalDays == null) {
                        sb.append("(never logged)\n");
                        continue;
                    }
                    long daysSince = TimeUnit.MILLISECONDS.toDays(now - s.lastDoneAt);
                    long daysLeft = s.intervalDays - daysSince;
                    String marker = daysLeft < 0 ? "OVERDUE" : (daysLeft < 30 ? "DUE SOON" : "ok");
                    sb.append(String.format("%4d d ago  •  %4d d left  [%s]%n",
                            daysSince, daysLeft, marker));
                }
            }

            sb.append('\n').append(eventCount).append(" DTC event(s) recorded in obd-events.log");

            final String text = sb.toString();
            final TextView tv = content;
            if (tv != null) tv.post(() -> { if (content != null) content.setText(text); });
        });
    }

    /**
     * Plot the last 30 days of one PID from the PidSample Room table.
     * X axis is "days ago" (negative-going), Y is the raw value.
     */
    private void refreshChart(Context ctx) {
        if (trendChart == null) return;
        final String sensor = currentTrendSensor;
        WorkDispatcher.io(() -> {
            AppDatabase db = AppDatabase.get(ctx);
            long since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
            List<PidSample> rows = db.samples().samplesSince(sensor, since);
            final List<Entry> entries = new ArrayList<>(rows.size());
            long now = System.currentTimeMillis();
            for (PidSample s : rows) {
                float daysAgo = -(now - s.ts) / (24f * 3600f * 1000f);
                entries.add(new Entry(daysAgo, (float) s.value));
            }
            final LineChart c = trendChart;
            if (c == null) return;
            c.post(() -> {
                if (trendChart == null) return;
                if (entries.isEmpty()) {
                    trendChart.clear();
                    trendChart.invalidate();
                    return;
                }
                LineDataSet ds = new LineDataSet(entries, sensor);
                ds.setColor(Color.parseColor("#1E5C96"));
                ds.setDrawCircles(false);
                ds.setLineWidth(2f);
                ds.setDrawValues(false);
                trendChart.setData(new LineData(ds));
                trendChart.invalidate();
            });
        });
    }

    /** Build CSV blob via ShareReport.buildCsv, write to external files dir, fire ACTION_SEND. */
    private void exportCsv(Context ctx) {
        WorkDispatcher.io((Activity) (ctx instanceof Activity ? ctx : null),
                () -> {
                    String csv = ShareReport.buildCsv(ctx);
                    String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
                    File out = new File(ctx.getExternalFilesDir(null), "obd-export-" + stamp + ".csv");
                    try (FileWriter w = new FileWriter(out)) { w.write(csv); }
                    return out;
                },
                file -> {
                    try {
                        Uri uri = FileProvider.getUriForFile(
                                ctx, ctx.getPackageName() + ".fileprovider", file);
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("text/csv");
                        send.putExtra(Intent.EXTRA_SUBJECT, "BMW OBD data export");
                        send.putExtra(Intent.EXTRA_TEXT,
                                "Attached: trip + sensor history from my BMW. CSV — opens in Excel/Numbers.");
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        ctx.startActivity(Intent.createChooser(send, "Share OBD export"));
                    } catch (Exception e) {
                        Toast.makeText(ctx, "Export share failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                },
                e -> Toast.makeText(ctx, "Export failed: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
    }

    private void setupChart() {
        trendChart.getDescription().setEnabled(false);
        trendChart.setTouchEnabled(true);
        trendChart.setDragEnabled(true);
        trendChart.setScaleEnabled(true);
        trendChart.setPinchZoom(true);
        trendChart.setBackgroundColor(Color.parseColor("#0A0A12"));
        trendChart.getLegend().setTextColor(0xFFEAEAEA);
        trendChart.getXAxis().setTextColor(0xFF9AA0AC);
        trendChart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        trendChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                // X is "days ago" — negative going. -7.0 → "-7d"
                return String.format(Locale.US, "%.0fd", value);
            }
        });
        trendChart.getAxisLeft().setTextColor(0xFF9AA0AC);
        trendChart.getAxisRight().setEnabled(false);
        trendChart.setNoDataText("No samples in the last 30 days. Drive with the app open to build history.");
        trendChart.setNoDataTextColor(0xFF9AA0AC);
    }

    private String currentVin(AppDatabase db) {
        List<VinProfile> all = db.vins().all();
        if (all.isEmpty()) return "default";
        return all.get(0).vin;
    }
}
