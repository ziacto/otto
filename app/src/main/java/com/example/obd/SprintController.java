package com.example.obd;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SprintController {

    private enum State { IDLE, READY, RUNNING, DONE }

    private State state = State.IDLE;
    private long startTime;
    private long endTime;
    private double bestTime = Double.MAX_VALUE;
    private final List<float[]> currentRun = new ArrayList<>();
    private final List<Double> history = new ArrayList<>();

    private TextView tvTime, tvStatus, tvBest, tvSpeed;
    private Button btnControl;
    private LineChart chart;
    private LinearLayout historyContainer;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timerTick;
    private boolean attached = false;

    public void attach(View view) {
        attached = true;
        tvTime = view.findViewById(R.id.tvSprintTime);
        tvStatus = view.findViewById(R.id.tvSprintStatus);
        tvBest = view.findViewById(R.id.tvBestTime);
        tvSpeed = view.findViewById(R.id.tvSprintSpeed);
        btnControl = view.findViewById(R.id.btnSprintControl);
        chart = view.findViewById(R.id.sprintChart);
        historyContainer = view.findViewById(R.id.sprintHistoryContainer);

        setupChart();
        renderUI();
        refreshHistory();

        btnControl.setOnClickListener(v -> onButtonPressed());

        timerTick = new Runnable() {
            @Override
            public void run() {
                if (!attached) return;
                if (state == State.RUNNING) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    tvTime.setText(String.format(Locale.US, "%.2f s", elapsed / 1000.0));
                }
                handler.postDelayed(this, 50);
            }
        };
        handler.post(timerTick);
    }

    public void detach() {
        attached = false;
        if (timerTick != null) handler.removeCallbacks(timerTick);
    }

    public void onSpeedValue(double speedKmh) {
        if (!attached) return;
        handler.post(() -> {
            // Re-check inside the post: a detach can land between the check
            // above and this runnable, and the state machine must not advance
            // against a torn-down screen.
            if (!attached) return;
            if (tvSpeed != null)
                tvSpeed.setText(String.format(Locale.US, "%.0f km/h", speedKmh));

            switch (state) {
                case READY:
                    if (speedKmh > 1.0) {
                        state = State.RUNNING;
                        startTime = System.currentTimeMillis();
                        currentRun.clear();
                        currentRun.add(new float[]{0f, (float) speedKmh});
                        renderUI();
                    }
                    break;
                case RUNNING:
                    float elapsed = System.currentTimeMillis() - startTime;
                    currentRun.add(new float[]{elapsed, (float) speedKmh});
                    if (speedKmh >= 100.0) {
                        endTime = System.currentTimeMillis();
                        double runTime = (endTime - startTime) / 1000.0;
                        if (runTime < bestTime) bestTime = runTime;
                        history.add(0, runTime);
                        if (history.size() > 5) history.remove(history.size() - 1);
                        state = State.DONE;
                        renderUI();
                        renderChart();
                        refreshHistory();
                    }
                    break;
                default:
                    break;
            }
        });
    }

    private void onButtonPressed() {
        switch (state) {
            case IDLE:
            case DONE:
                state = State.READY;
                break;
            case READY:
            case RUNNING:
                state = State.IDLE;
                currentRun.clear();
                break;
        }
        renderUI();
    }

    private void renderUI() {
        if (!attached) return;
        switch (state) {
            case IDLE:
                tvStatus.setText("0 – 100 km/h Timer");
                tvTime.setText("---.-- s");
                btnControl.setText("GET READY");
                tintButton("#03DAC5");
                break;
            case READY:
                tvStatus.setText("Waiting for launch…");
                tvTime.setText("---.-- s");
                btnControl.setText("CANCEL");
                tintButton("#FF6D00");
                break;
            case RUNNING:
                tvStatus.setText("Lauf aktiv!");
                btnControl.setText("CANCEL");
                tintButton("#F44336");
                break;
            case DONE:
                double t = (endTime - startTime) / 1000.0;
                tvTime.setText(String.format(Locale.US, "%.2f s", t));
                tvStatus.setText("Done!");
                btnControl.setText("AGAIN");
                tintButton("#03DAC5");
                break;
        }
        if (bestTime < Double.MAX_VALUE)
            tvBest.setText(String.format(Locale.US, "Best: %.2f s", bestTime));
    }

    private void tintButton(String hex) {
        if (btnControl == null) return;
        btnControl.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.parseColor(hex)));
    }

    private void refreshHistory() {
        if (historyContainer == null) return;
        historyContainer.removeAllViews();
        for (int i = 0; i < history.size(); i++) {
            TextView tv = new TextView(historyContainer.getContext());
            tv.setText(String.format(Locale.US, "  #%d:  %.2f s", i + 1, history.get(i)));
            tv.setTextColor(i == 0 ? Color.parseColor("#FFD700") : Color.GRAY);
            tv.setTextSize(15f);
            historyContainer.addView(tv);
        }
    }

    private void setupChart() {
        chart.getDescription().setEnabled(false);
        chart.setTouchEnabled(false);
        chart.setBackgroundColor(Color.parseColor("#121212"));
        chart.getLegend().setTextColor(Color.WHITE);
        chart.getXAxis().setTextColor(Color.GRAY);
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        chart.getXAxis().setValueFormatter(new com.github.mikephil.charting.formatter.ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1fs", value);
            }
        });
        chart.getAxisLeft().setTextColor(Color.GRAY);
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setAxisMaximum(120f);
        chart.getAxisRight().setEnabled(false);
        chart.setNoDataText("No run yet");
        chart.setNoDataTextColor(Color.GRAY);
    }

    private void renderChart() {
        if (chart == null || currentRun.isEmpty()) return;
        List<Entry> entries = new ArrayList<>(currentRun.size());
        for (float[] pt : currentRun)
            entries.add(new Entry(pt[0] / 1000f, pt[1]));

        LineDataSet ds = new LineDataSet(entries, "Speed (km/h)");
        ds.setColor(Color.parseColor("#03DAC5"));
        ds.setDrawCircles(false);
        ds.setLineWidth(2.5f);
        ds.setDrawValues(false);
        ds.setDrawFilled(true);
        ds.setFillColor(Color.parseColor("#03DAC5"));
        ds.setFillAlpha(40);
        chart.setData(new LineData(ds));
        chart.invalidate();
    }
}
