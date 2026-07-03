package com.example.obd;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AnalyticsController {

    static final List<String> MOTORSPORT_SENSORS = Arrays.asList(
            "RPM", "Vehicle Speed", "Boost Pressure", "Throttle Position",
            "Engine Load", "Mass Air Flow", "Lambda",
            "Coolant Temp", "Intake Air Temp", "Timing Advance"
    );

    private static final int[] CHART_COLORS = {
            Color.parseColor("#03DAC5"),
            Color.parseColor("#FF4081"),
            Color.parseColor("#FFD600"),
            Color.parseColor("#64DD17"),
            Color.parseColor("#FF6D00"),
            Color.parseColor("#AA00FF"),
            Color.parseColor("#2979FF"),
            Color.parseColor("#F50057"),
            Color.parseColor("#00E5FF"),
            Color.parseColor("#FFFFFF"),
    };

    private static final long[] INTERVAL_MS = {200, 500, 1000, 2000, 5000};
    private static final String[] INTERVAL_LABELS = {"0.2 s", "0.5 s", "1 s", "2 s", "5 s"};

    private LineChart chart;
    private Button btnToggle;
    private final Set<String> enabledSensors = new LinkedHashSet<>(MOTORSPORT_SENSORS);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable chartUpdater;
    private boolean attached = false;

    public void attach(View view) {
        attached = true;
        chart = view.findViewById(R.id.analyticsChart);
        setupChart();

        Spinner spinnerInterval = view.findViewById(R.id.spinnerInterval);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                view.getContext(), android.R.layout.simple_spinner_item, INTERVAL_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerInterval.setAdapter(adapter);
        spinnerInterval.setSelection(1);
        spinnerInterval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                DataLogger.getInstance().setMinInterval(INTERVAL_MS[pos]);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnToggle = view.findViewById(R.id.btnToggleRecording);
        refreshToggleButton();
        btnToggle.setOnClickListener(v -> {
            DataLogger logger = DataLogger.getInstance();
            if (logger.isRecording()) logger.stopRecording();
            else logger.startRecording();
            refreshToggleButton();
        });

        Button btnClear = view.findViewById(R.id.btnClearData);
        btnClear.setOnClickListener(v -> {
            DataLogger.getInstance().clear();
            refreshChart();
        });

        LinearLayout cbContainer = view.findViewById(R.id.sensorCheckboxContainer);
        for (int i = 0; i < MOTORSPORT_SENSORS.size(); i++) {
            final String sensor = MOTORSPORT_SENSORS.get(i);
            CheckBox cb = new CheckBox(view.getContext());
            cb.setText(sensor);
            cb.setChecked(true);
            cb.setTextColor(CHART_COLORS[i % CHART_COLORS.length]);
            cb.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) enabledSensors.add(sensor);
                else enabledSensors.remove(sensor);
                refreshChart();
            });
            cbContainer.addView(cb);
        }

        chartUpdater = new Runnable() {
            @Override
            public void run() {
                if (!attached) return;
                refreshChart();
                handler.postDelayed(this, 500);
            }
        };
        handler.post(chartUpdater);
    }

    public void detach() {
        attached = false;
        if (chartUpdater != null) handler.removeCallbacks(chartUpdater);
    }

    private void setupChart() {
        chart.getDescription().setEnabled(false);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);
        chart.setBackgroundColor(Color.parseColor("#121212"));
        chart.getLegend().setTextColor(Color.WHITE);
        chart.getLegend().setWordWrapEnabled(true);
        chart.getXAxis().setTextColor(Color.GRAY);
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return (int) value + "s";
            }
        });
        chart.getAxisLeft().setTextColor(Color.GRAY);
        chart.getAxisRight().setEnabled(false);
        chart.setNoDataText("Start recording and drive");
        chart.setNoDataTextColor(Color.GRAY);
    }

    private void refreshToggleButton() {
        if (btnToggle == null) return;
        boolean rec = DataLogger.getInstance().isRecording();
        btnToggle.setText(rec ? "Stop" : "Start recording");
        btnToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                rec ? Color.parseColor("#F44336") : Color.parseColor("#03DAC5")));
    }

    private void refreshChart() {
        if (chart == null) return;
        DataLogger logger = DataLogger.getInstance();

        long refTime = -1;
        for (String sensor : enabledSensors) {
            List<double[]> pts = logger.getSeries(sensor);
            if (!pts.isEmpty()) {
                long t = (long) pts.get(0)[0];
                if (refTime < 0 || t < refTime) refTime = t;
            }
        }
        if (refTime < 0) refTime = System.currentTimeMillis();

        List<LineDataSet> sets = new ArrayList<>();
        int colorIdx = 0;
        for (String sensor : MOTORSPORT_SENSORS) {
            if (!enabledSensors.contains(sensor)) { colorIdx++; continue; }
            List<double[]> pts = logger.getSeries(sensor);
            if (pts.isEmpty()) { colorIdx++; continue; }
            List<Entry> entries = new ArrayList<>(pts.size());
            for (double[] pt : pts) {
                entries.add(new Entry((float) ((pt[0] - refTime) / 1000.0), (float) pt[1]));
            }
            LineDataSet ds = new LineDataSet(entries, sensor);
            ds.setColor(CHART_COLORS[colorIdx % CHART_COLORS.length]);
            ds.setDrawCircles(false);
            ds.setLineWidth(2f);
            ds.setDrawValues(false);
            sets.add(ds);
            colorIdx++;
        }

        if (sets.isEmpty()) {
            chart.clear();
            chart.invalidate();
        } else {
            chart.setData(new LineData(new ArrayList<>(sets)));
            chart.invalidate();
        }
    }
}
