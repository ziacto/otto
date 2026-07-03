package com.example.obd;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake ELM327 adapter for dry-run testing. Feeds commands from the app into
 * a background {@link SimEngine} and feeds canned responses back, letting the
 * whole polling + parsing + UI stack exercise without an actual OBD adapter.
 *
 * Same shape as {@link BleObdTransport}: exposes an InputStream + OutputStream
 * that {@link ObdConnection} plugs into its {@code in}/{@code out} fields.
 *
 * Both directions are queue-backed, NOT PipedInput/OutputStream: pipes bind
 * to the last reader thread, and the simulator is driven by short-lived
 * threads (the "SimBootFromIntent"/drawer boot thread runs init, then dies;
 * the poll thread is killed/recreated on every one-shot). With pipes, the
 * first response written after a reader thread died failed with "Read end
 * dead", the engine loop exited, and every subsequent poll cycle errored —
 * the exact failure captured in the 2026-07-02 on-device log.
 *
 * Usage:
 *   ObdManagerFast.connectSimulator() → ObdConnection.connectSimulator()
 *   which builds this transport and skips the entire Bluetooth stack.
 */
public class SimulatedObdTransport {

    private final ByteQueueChannel appToSim = new ByteQueueChannel(); // commands
    private final ByteQueueChannel simToApp = new ByteQueueChannel(); // responses

    private BufferedInputStream inputWrapper;
    private final SimEngine engine = new SimEngine();
    private volatile boolean running = false;
    private Thread worker;

    public SimulatedObdTransport() {
    }

    public void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::runEngine, "SimObdEngine");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean isConnected() { return running; }

    public BufferedInputStream getInput() {
        if (inputWrapper == null) inputWrapper = new BufferedInputStream(simToApp.in());
        return inputWrapper;
    }

    public OutputStream getOutput() { return appToSim.out(); }

    public void disconnect() {
        running = false;
        appToSim.close();
        simToApp.close();
        if (worker != null) worker.interrupt();
    }

    /**
     * Command loop: read from the app until we see '\r', trim it, ask the
     * engine for a response, then write "<response>\r>" back so the app's
     * '>'-terminated read completes.
     *
     * Cheap adapters emit both '\r' *and* leading/trailing whitespace around
     * responses. We mimic that with a leading '\r' before the payload so the
     * app's ObdCommand.run tokeniser works identically to real hardware.
     */
    private void runEngine() {
        InputStream in = appToSim.in();
        StringBuilder cmd = new StringBuilder();
        while (running) {
            try {
                int b = in.read();
                if (b == -1) break;
                char c = (char) b;
                if (c == '\r' || c == '\n') {
                    if (cmd.length() == 0) continue;
                    String request = cmd.toString().trim();
                    cmd.setLength(0);
                    String response = engine.respond(request);
                    if (response == null) response = "?";
                    // Real ELMs echo nothing after ATE0 = 1 (which the app
                    // enables in initElm327). Just emit the payload + prompt.
                    simToApp.out().write(('\r' + response + "\r>").getBytes());
                } else {
                    cmd.append(c);
                }
            } catch (IOException e) {
                break;
            }
        }
    }

    /**
     * One-direction byte channel with no thread affinity. Writes enqueue
     * chunks and never block; reads block briefly (100 ms poll) and re-check
     * the closed flag, returning EOF once closed and drained. available() is
     * exact, which the available()-then-read() pattern in every command pump
     * relies on.
     */
    private static final class ByteQueueChannel {
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(1024);
        private final AtomicInteger queuedBytes = new AtomicInteger(0);
        private volatile boolean closed = false;

        private final InputStream in = new InputStream() {
            private byte[] cur;
            private int pos;

            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n <= 0 ? -1 : (one[0] & 0xFF);
            }

            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                while (cur == null || pos >= cur.length) {
                    if (closed && queue.isEmpty()) return -1;
                    try {
                        byte[] next = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (next != null && next.length > 0) {
                            cur = next;
                            pos = 0;
                        } else if (closed) {
                            return -1;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted");
                    }
                }
                int n = Math.min(len, cur.length - pos);
                System.arraycopy(cur, pos, b, off, n);
                pos += n;
                queuedBytes.addAndGet(-n);
                return n;
            }

            @Override public int available() {
                return Math.max(0, queuedBytes.get());
            }
        };

        private final OutputStream out = new OutputStream() {
            @Override public void write(int b) throws IOException {
                write(new byte[]{ (byte) b }, 0, 1);
            }

            @Override public void write(byte[] b, int off, int len) throws IOException {
                if (closed) throw new IOException("Simulator transport closed");
                if (len == 0) return;
                byte[] copy = new byte[len];
                System.arraycopy(b, off, copy, 0, len);
                if (!queue.offer(copy)) throw new IOException("Simulator channel full");
                queuedBytes.addAndGet(len);
            }
        };

        InputStream in() { return in; }
        OutputStream out() { return out; }

        void close() {
            closed = true;
            queue.offer(new byte[0]); // wake a blocked reader so it sees EOF
        }
    }
}
