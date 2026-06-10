package com.example.obd;

public interface SpeedPollerListener {
    void onValue(String name, double value, String unit);
    void onError(String msg);
}