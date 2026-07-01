package com.example.obd.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = { VinProfile.class, Trip.class, PidSample.class, DtcEvent.class, ServiceItem.class, ScanReport.class },
        version = 2,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    public abstract Daos.VinDao vins();
    public abstract Daos.TripDao trips();
    public abstract Daos.PidSampleDao samples();
    public abstract Daos.DtcEventDao events();
    public abstract Daos.ServiceDao services();
    public abstract Daos.ScanReportDao scanReports();

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase get(Context ctx) {
        AppDatabase db = INSTANCE;
        if (db == null) {
            synchronized (AppDatabase.class) {
                db = INSTANCE;
                if (db == null) {
                    db = Room.databaseBuilder(ctx.getApplicationContext(),
                                    AppDatabase.class, "obd.db")
                            .fallbackToDestructiveMigration()
                            .build();
                    INSTANCE = db;
                }
            }
        }
        return db;
    }
}
