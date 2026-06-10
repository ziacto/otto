package com.example.obd;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

public class ObdManagerFast {
    private final Context context;
    private final SpeedPollerListener listener;
    private final Supplier<Integer> intervalSupplier;
    private final Supplier<List<ObdCommand>> commandSupplier;

    private volatile ObdConnection connection;
    private volatile boolean running = false;
    private Thread pollThread;

    public ObdManagerFast(Context context,
                          SpeedPollerListener listener,
                          Supplier<Integer> intervalSupplier,
                          Supplier<List<ObdCommand>> commandSupplier) {
        this.context = context;
        this.listener = listener;
        this.intervalSupplier = intervalSupplier;
        this.commandSupplier = commandSupplier;
    }

    public void connect(BluetoothDevice device) throws IOException {
        disconnect();
        connection = new ObdConnection();
        connection.connect(context, device);
        startPolling();
    }

    /**
     * Switch poll group: stop thread and wait for it to finish,
     * then start a new one. Connection stays open.
     */
    public void restartPolling() {
        stopAndJoinPollThread();
        if (connection != null && connection.isConnected()) {
            startPolling();
        }
    }

    public synchronized void disconnect() {
        stopAndJoinPollThread();
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
    }

    public boolean isConnected() {
        return connection != null && connection.isConnected();
    }

    private synchronized void startPolling() {
        running = true;
        pollThread = new Thread(this::pollLoop, "ObdPollThread");
        pollThread.setPriority(Thread.MIN_PRIORITY);
        pollThread.start();
    }

    /** Stops the poll thread and waits up to 2000 ms for it to finish. */
    private void stopAndJoinPollThread() {
        Thread old;
        synchronized (this) {
            running = false;
            old = pollThread;
            if (pollThread != null) {
                pollThread.interrupt();
                pollThread = null;
            }
        }
        // Do not join the current thread (occurs when pollLoop() calls disconnect()).
        if (old != null && old != Thread.currentThread()) {
            try { old.join(2000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void pollLoop() {
        int emptycycles = 0; // Consecutive cycles without any successful reading

        while (running) {
            ObdConnection conn = connection;
            if (conn == null || !conn.isConnected()) break;

            List<ObdCommand> commands = commandSupplier.get();
            boolean anySuccess = false;

            for (ObdCommand cmd : commands) {
                if (!running) return;
                ObdConnection c = connection;
                if (c == null || !c.isConnected()) return;
                try {
                    double value = cmd.run(c.getInput(), c.getOutput());
                    listener.onValue(cmd.getName(), value, cmd.getUnit());
                    anySuccess = true;
                } catch (Exception e) {
                    // IOException (timeout, ELM status, parse error) or similar –
                    // silently skip this command, keep connection open
                }
            }

            if (anySuccess) {
                emptycycles = 0;
            } else {
                emptycycles++;
                // 3 consecutive cycles with no response → connection genuinely lost
                if (emptycycles >= 3) {
                    listener.onError("OBD adapter not responding");
                    disconnect();
                    return;
                }
            }

            try {
                Thread.sleep(intervalSupplier.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        ObdConnection conn = connection;
        if (conn != null && !conn.isConnected()) {
            listener.onError("OBD adapter disconnected");
            disconnect();
        }
    }
}
