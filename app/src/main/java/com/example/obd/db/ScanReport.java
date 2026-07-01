package com.example.obd.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "scan_report")
public class ScanReport {
    @PrimaryKey(autoGenerate = true) public long id;
    public long ts;
    public String vin;
    /** Stable kind tag: DTC_FULL / DTC_STORED / DTC_PENDING / DTC_PERMANENT / MODULE / AI_ESTIMATE. */
    @NonNull public String kind = "DTC_FULL";
    @NonNull public String title = "";
    @NonNull public String html = "";
}
