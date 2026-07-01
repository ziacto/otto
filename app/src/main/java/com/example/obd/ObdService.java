package com.example.obd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Foreground service that owns the lifecycle of background BT reconnect watchdog.
 *
 * The Activity holds the live {@link ObdManagerFast}; this service simply runs a
 * 30-second tick that asks {@link BluetoothHelper#tryAutoConnect()} to re-attempt
 * the saved-MAC connection if we've dropped offline. Keeping it lightweight is
 * intentional — the real polling stays with the Activity for now, because the
 * `ObdConnection` was not designed for cross-process ownership.
 *
 * Started from MainActivity.onCreate, stopped from onDestroy.
 */
public class ObdService extends Service {

    private static final String CHANNEL_ID = "obd-bg";
    private static final int NOTIF_ID = 1042;

    /** Reconnect backoff schedule, in ms. Stops doubling after the cap. */
    private static final long BACKOFF_BASE_MS = 30_000L;       // 30 s
    private static final long BACKOFF_CAP_MS  = 5 * 60_000L;   // 5 min

    /** Public connection state — observable so the UI can render reconnect progress. */
    public enum ConnectionState { IDLE, CONNECTING, CONNECTED, RECONNECTING, ERROR }
    private static volatile ConnectionState state = ConnectionState.IDLE;
    private static volatile int reconnectAttempts = 0;

    public static ConnectionState getConnectionState() { return state; }
    public static int getReconnectAttempts() { return reconnectAttempts; }
    static void setConnectionState(ConnectionState s) { state = s; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable tick;

    private static volatile BluetoothHelper helperRef;
    private static volatile ObdManagerFast managerRef;

    /** Activity calls this once so the service knows what to drive. */
    public static void bind(BluetoothHelper helper, ObdManagerFast manager) {
        helperRef = helper;
        managerRef = manager;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        // On Android 12+ the 5-second FGS clock is already ticking by the time
        // we get here (via startForegroundService in startIfNeeded). We MUST
        // call startForeground() before returning — even in the no-permission
        // path, otherwise the framework throws
        // ForegroundServiceDidNotStartInTimeException and crashes the process.
        // If BT_CONNECT is missing we post a benign notification then stop —
        // the process stays alive but the watchdog is inert.
        boolean btConnectGranted = android.os.Build.VERSION.SDK_INT < 31
                || androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.BLUETOOTH_CONNECT)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        try {
            startForeground(NOTIF_ID, buildNotification(
                    btConnectGranted ? "Watching for OBD adapter"
                                     : "Bluetooth permission missing"));
        } catch (Exception e) {
            // Defensive: even with perms, the OS can still reject (e.g. battery
            // saver in some OEM ROMs). Stop self quietly so the app doesn't ANR.
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "ObdService startForeground rejected: " + e.getMessage());
            stopSelf();
            return;
        }
        if (!btConnectGranted) {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "ObdService: BT_CONNECT not granted, stopping (FGS acked)");
            stopSelf();
            return;
        }
        tick = new Runnable() {
            @Override public void run() {
                long nextDelay = BACKOFF_BASE_MS;
                try {
                    BluetoothHelper h = helperRef;
                    ObdManagerFast m = managerRef;
                    if (h == null || m == null) {
                        state = ConnectionState.IDLE;
                    } else if (m.isConnected()) {
                        // Healthy: reset the backoff counter so the next outage starts fresh.
                        if (reconnectAttempts != 0) {
                            ObdLogger.get().log(ObdLogger.Level.INFO,
                                    "Reconnected — clearing backoff (was " + reconnectAttempts + " attempts)");
                        }
                        reconnectAttempts = 0;
                        state = ConnectionState.CONNECTED;
                    } else {
                        // Doubling backoff, capped at 5 min. Helps battery and avoids hammering
                        // a paired-but-offline adapter every 30 s for the rest of the trip.
                        state = (reconnectAttempts == 0)
                                ? ConnectionState.CONNECTING
                                : ConnectionState.RECONNECTING;
                        reconnectAttempts++;
                        ObdLogger.get().log(ObdLogger.Level.INFO,
                                "Auto-reconnect attempt #" + reconnectAttempts);
                        h.tryAutoConnect();
                        nextDelay = backoffDelay(reconnectAttempts);
                    }
                } catch (Exception e) {
                    ObdLogger.get().log(ObdLogger.Level.ERROR, "BG reconnect: " + e);
                    state = ConnectionState.ERROR;
                    nextDelay = backoffDelay(reconnectAttempts);
                }
                handler.postDelayed(this, nextDelay);
            }
        };
        handler.postDelayed(tick, BACKOFF_BASE_MS);
    }

    /** 30 s, 60 s, 120 s, 240 s, 300 s, 300 s, … capped at 5 min. */
    private static long backoffDelay(int attempt) {
        if (attempt <= 1) return BACKOFF_BASE_MS;
        long d = BACKOFF_BASE_MS << Math.min(attempt - 1, 10); // 2^N · base
        return Math.min(d, BACKOFF_CAP_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (tick != null) handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "OBD background", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Auto-reconnect watchdog for the OBD adapter");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("BMW OBD")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    public static void startIfNeeded(Context ctx) {
        // On Android 12+ (API 31) startForegroundService() STARTS a 5-second clock
        // that MUST be satisfied by a matching startForeground() call inside the
        // service's onCreate/onStartCommand. If the service instead calls stopSelf
        // (e.g. because BLUETOOTH_CONNECT wasn't granted) the framework crashes the
        // app with ForegroundServiceDidNotStartInTimeException. So we probe the
        // permission HERE, before starting the service — no start, no timer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && androidx.core.content.ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "ObdService deferred: BT_CONNECT not granted");
            return;
        }
        try {
            Intent i = new Intent(ctx, ObdService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (SecurityException se) {
            ObdLogger.get().log(ObdLogger.Level.INFO,
                    "ObdService deferred: " + se.getMessage());
        } catch (Exception e) {
            ObdLogger.get().log(ObdLogger.Level.ERROR,
                    "ObdService start failed: " + e);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ObdService.class));
    }
}
