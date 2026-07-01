package com.example.obd.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "dtc_event")
public class DtcEvent {
    @PrimaryKey(autoGenerate = true) public long id;
    public String vin;
    public String mode;      // STORED | PENDING | PERMANENT | DSC | EGS
    public String code;
    public long ts;
    public Double lat, lon;
    public String freezeJson; // optional snapshot
}
