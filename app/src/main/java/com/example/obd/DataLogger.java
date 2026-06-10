package com.example.obd;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class DataLogger {
    private static volatile DataLogger instance;
    private final Map<String, LinkedList<double[]>> series = new LinkedHashMap<>();
    private final Map<String, Long> lastRecordTime = new LinkedHashMap<>();
    private static final int MAX_POINTS = 600;
    private volatile boolean recording = false;
    private volatile long minIntervalMs = 500;

    private DataLogger() {}

    public static DataLogger getInstance() {
        if (instance == null) {
            synchronized (DataLogger.class) {
                if (instance == null) instance = new DataLogger();
            }
        }
        return instance;
    }

    public void setMinInterval(long ms) { minIntervalMs = ms; }
    public void startRecording() { recording = true; }
    public void stopRecording() { recording = false; }
    public boolean isRecording() { return recording; }

    public synchronized void record(String name, double value) {
        if (!recording) return;
        long now = System.currentTimeMillis();
        Long last = lastRecordTime.get(name);
        if (last != null && (now - last) < minIntervalMs) return;
        lastRecordTime.put(name, now);
        series.computeIfAbsent(name, k -> new LinkedList<>());
        LinkedList<double[]> list = series.get(name);
        list.add(new double[]{now, value});
        if (list.size() > MAX_POINTS) list.pollFirst();
    }

    public synchronized List<double[]> getSeries(String name) {
        LinkedList<double[]> list = series.get(name);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public synchronized void clear() {
        series.clear();
        lastRecordTime.clear();
    }
}
