package com.example.obd.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Per-VIN maintenance ledger row: oil, brakes, microfilter, plugs, coolant, etc. */
@Entity(tableName = "service_item")
public class ServiceItem {
    @PrimaryKey(autoGenerate = true) public long id;
    public String vin;
    public String name;          // e.g. "Engine Oil"
    public Long lastDoneAt;      // epoch ms
    public Integer lastDoneKm;
    public Integer intervalKm;   // e.g. 12000
    public Integer intervalDays; // e.g. 365
    public String notes;
}
