package com.example.obd.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "trip")
public class Trip {
    @PrimaryKey(autoGenerate = true) public long id;
    public String vin;
    public long startedAt;
    public Long endedAt;
    public Double peakSpeed;        // km/h
    public Double peakCoolant;      // °C
    public Double peakRpm;          // rpm
    public Integer durationSec;
    public Double distanceKm;       // optional, integrated from speed samples
    public String notes;
}
