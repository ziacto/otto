package com.example.obd;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

/**
 * Fake ELM327 adapter for dry-run testing. Pipes commands from the app into
 * a background {@link SimEngine} and pipes canned responses back, letting the
 * whole polling + parsing + UI stack exercise without an actual OBD adapter.
 *
 * Same shape as {@link BleObdTransport}: exposes an InputStream + OutputStream
 * that {@link ObdConnection} plugs into its {@code in}/{@code out} fields.
 *
 * Usage:
 *   ObdManagerFast.connectSimulator() → ObdConnection.connectSimulator()
 *   which builds this transport and skips the entire Bluetooth stack.
 *
 * Buffer sizes are chosen so the small ELM commands + responses never block:
 *   - App-to-Sim pipe (commands): 4 KB (worst case: raw CAN sniffer replay)
 *   - Sim-to-App pipe (responses): 16 KB (module-scan multi-frame ISO-TP)
 */
public class SimulatedObdTransport {

    private final PipedInputStream fromSim;   // App reads from here
    private final PipedOutputStream simToApp; // Sim writes here
    private final PipedInputStream toSim;     // Sim reads here
    private final PipedOutputStream appToSim; // App writes here

    private BufferedInputStream inputWrapper;
    private final SimEngine engine = new SimEngine();
    private volatile boolean running = false;
    private Thread worker;

    public SimulatedObdTransport() throws IOException {
        fromSim = new PipedInputStream(16 * 1024);
        simToApp = new PipedOutputStream(fromSim);
        toSim = new PipedInputStream(4 * 1024);
        appToSim = new PipedOutputStream(toSim);
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
        if (inputWrapper == null) inputWrapper = new BufferedInputStream(fromSim);
        return inputWrapper;
    }

    public OutputStream getOutput() { return appToSim; }

    public void disconnect() {
        running = false;
        if (worker != null) worker.interrupt();
        // Closing the pipes wakes any pending read on the app side with -1
        // so the pollLoop notices the transport went away.
        safeClose(simToApp);
        safeClose(appToSim);
        safeClose(fromSim);
        safeClose(toSim);
    }

    private static void safeClose(java.io.Closeable c) {
        try { c.close(); } catch (Exception ignored) {}
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
        StringBuilder cmd = new StringBuilder();
        while (running) {
            try {
                int b = toSim.read();
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
                    simToApp.write(('\r' + response + "\r>").getBytes());
                    simToApp.flush();
                } else {
                    cmd.append(c);
                }
            } catch (IOException e) {
                break;
            }
        }
    }
}
