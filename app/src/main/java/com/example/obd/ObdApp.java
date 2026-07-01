package com.example.obd;

import android.app.Application;

import com.example.obd.db.AppDatabase;

public class ObdApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ObdLogger.get().initFileSink(this);
        RawFrameLogger.get().init(this);
        EventLogger.get().init(this);
        CrashHandler.install(this);
        AppDatabase.get(this); // warm up
    }
}
