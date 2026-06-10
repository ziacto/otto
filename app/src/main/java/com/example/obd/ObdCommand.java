package com.example.obd;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;

public abstract class ObdCommand {
    private final String command;
    private static final int TIMEOUT_MS = 8000;

    protected ObdCommand(String command) {
        this.command = command;
    }

    public abstract String getName();
    public abstract String getUnit();
    public abstract double parseResult(String rawResponse);

    public double run(BufferedInputStream in, OutputStream out) throws IOException {
        // Flush buffer – discard leftover bytes from the previous command
        while (in.available() > 0) in.read();

        out.write((command + "\r").getBytes());
        out.flush();

        StringBuilder res = new StringBuilder();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
            if (in.available() > 0) {
                int c = in.read();
                if (c == -1 || c == '>') break;
                res.append((char) c);
            } else {
                try { Thread.sleep(20); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                }
            }
        }

        String raw = res.toString().trim();

        // ELM327 status messages are not vehicle data – throw as IOException
        // so pollLoop() silently skips them instead of triggering a disconnect
        if (raw.isEmpty() || isElm327Status(raw)) {
            throw new IOException("ELM: " + raw);
        }

        try {
            return parseResult(raw);
        } catch (Exception e) {
            // NumberFormatException or similar – not a connection problem, just an unrecognized response
            throw new IOException("Parse error [" + getName() + "]: " + raw);
        }
    }

    private static boolean isElm327Status(String r) {
        String u = r.toUpperCase();
        return u.contains("NO DATA")
            || u.contains("ERROR")
            || u.contains("CONNECT")
            || u.contains("SEARCHING")
            || u.contains("STOPPED")
            || u.contains("UNABLE")
            || u.contains("BUS")
            || u.contains("FB ERR")
            || u.contains("DATA ERR")
            || u.trim().equals("?")
            || u.trim().equals("OK");
    }
}
