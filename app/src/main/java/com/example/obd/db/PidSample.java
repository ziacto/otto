package com.example.obd.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "pid_sample",
        indices = { @Index(value = {"vin", "name", "ts"}) })
public class PidSample {
    @PrimaryKey(autoGenerate = true) public long id;
    public String vin;
    public String name;   // e.g. "Coolant Temp"
    public double value;
    public long ts;       // epoch ms
}
