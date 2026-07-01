package com.example.obd.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

public final class Daos {

    private Daos() {}

    @Dao public interface VinDao {
        @Insert(onConflict = OnConflictStrategy.REPLACE)
        long upsert(VinProfile p);

        @Query("SELECT * FROM vin_profile ORDER BY lastSeenAt DESC")
        List<VinProfile> all();

        @Query("SELECT * FROM vin_profile WHERE vin = :vin LIMIT 1")
        VinProfile byVin(String vin);
    }

    @Dao public interface TripDao {
        @Insert long insert(Trip t);
        @Update int update(Trip t);
        @Query("SELECT * FROM trip ORDER BY startedAt DESC LIMIT :limit")
        List<Trip> recent(int limit);
    }

    @Dao public interface PidSampleDao {
        @Insert long insert(PidSample s);

        @Query("SELECT * FROM pid_sample WHERE name = :name AND ts >= :since ORDER BY ts ASC")
        List<PidSample> samplesSince(String name, long since);

        @Query("SELECT AVG(value) FROM pid_sample WHERE name = :name AND ts >= :since")
        Double averageSince(String name, long since);

        @Query("SELECT COUNT(*) FROM pid_sample WHERE name = :name AND ts >= :since")
        int countSince(String name, long since);

        @Query("SELECT COUNT(*) FROM pid_sample")
        int total();

        @Query("DELETE FROM pid_sample")
        int deleteAll();
    }

    @Dao public interface DtcEventDao {
        @Insert long insert(DtcEvent e);
        @Query("SELECT * FROM dtc_event ORDER BY ts DESC LIMIT :limit")
        List<DtcEvent> recent(int limit);
    }

    @Dao public interface ServiceDao {
        @Insert(onConflict = OnConflictStrategy.REPLACE)
        long upsert(ServiceItem s);

        @Query("SELECT * FROM service_item WHERE vin = :vin ORDER BY name ASC")
        List<ServiceItem> forVin(String vin);

        @Query("DELETE FROM service_item WHERE id = :id")
        int delete(long id);
    }

    @Dao public interface ScanReportDao {
        @Insert long insert(ScanReport r);

        @Query("SELECT id, ts, vin, kind, title, '' AS html FROM scan_report ORDER BY ts DESC LIMIT :limit")
        List<ScanReport> recentMeta(int limit);

        @Query("SELECT * FROM scan_report WHERE id = :id LIMIT 1")
        ScanReport byId(long id);

        @Query("DELETE FROM scan_report WHERE id = :id")
        int delete(long id);

        @Query("DELETE FROM scan_report")
        int clear();
    }
}
