package com.example.obd.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "vin_profile")
public class VinProfile {
    @PrimaryKey @NonNull
    public String vin = "";
    public String displayName; // e.g. "BMW 730li 2007"
    public String model;
    public Integer year;
    public Long createdAt;
    public Long lastSeenAt;
    public Integer odometerKm;
}
