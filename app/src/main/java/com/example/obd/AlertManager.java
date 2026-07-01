package com.example.obd;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * Spoken-alert engine with hysteresis + throttle. Encapsulates TTS lifecycle and the
 * per-sensor threshold state that used to live inline in {@link MainActivity}.
 *
 * Behavior is one alert per crossing, not per poll: a value sitting above its
 * threshold won't be spoken again until it dips below the recovery point. A 4-second
 * global throttle protects the driver from a barrage if multiple alarms fire at once.
 */
public final class AlertManager {

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private long lastTtsAt = 0L;

    private boolean coolantHot = false;
    private boolean oilHot = false;
    private boolean batteryLow = false;

    public void init(Context ctx) {
        // Capture the engine via a holder so the init callback can reach the
        // *new* engine even if it fires before the field assignment below, or
        // after shutdown() nulled the field. Without this, a re-init or quick
        // shutdown→init can crash with NPE on setLanguage().
        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(ctx, status -> {
            TextToSpeech engine = holder[0];
            if (status == TextToSpeech.SUCCESS && engine != null) {
                try { engine.setLanguage(Locale.US); } catch (Exception ignored) {}
                ttsReady = true;
            } else {
                ttsReady = false;
            }
        });
        tts = holder[0];
    }

    public void shutdown() {
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
            try { tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
        }
        ttsReady = false;
    }

    /** Inspect a live sensor reading; speak a warning if it just crossed into bad territory. */
    public void check(String name, double value) {
        if (!ttsReady) return;
        switch (name) {
            case "Coolant Temp":
                if (value >= 110.0 && !coolantHot) {
                    coolantHot = true;
                    speak("Coolant high, " + (int) value + " degrees");
                } else if (value < 105.0 && coolantHot) {
                    coolantHot = false;
                }
                break;
            case "Oil Temp":
                if (value >= 130.0 && !oilHot) {
                    oilHot = true;
                    speak("Oil temperature high, " + (int) value + " degrees");
                } else if (value < 125.0 && oilHot) {
                    oilHot = false;
                }
                break;
            case "Battery Voltage":
                if (value < 11.8 && !batteryLow) {
                    batteryLow = true;
                    speak("Battery voltage low");
                } else if (value >= 12.2 && batteryLow) {
                    batteryLow = false;
                }
                break;
        }
    }

    private void speak(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastTtsAt < 4000) return;
        lastTtsAt = now;
        if (tts != null) tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "obd-alert");
    }
}
