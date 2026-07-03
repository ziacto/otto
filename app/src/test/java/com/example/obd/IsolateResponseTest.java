package com.example.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * ObdCommand.isolatePositiveResponse — plain-JVM tests.
 *
 * The regression that motivated this file: the positive-response marker
 * ("41 0C" for 010C) used to be accepted at ANY nibble offset in the
 * whitespace-stripped buffer, so adjacent bytes like "B4 10 C5" contained
 * "410C" at an odd offset and sliced the payload mid-byte — garbage parsed
 * as a perfectly valid reading.
 */
public class IsolateResponseTest {

    private static ObdCommand speed() {
        return new SpeedCommand();
    }

    @Test
    public void marker_at_even_offset_is_isolated() {
        // Straightforward "41 0D 50" → 80 km/h slice.
        String isolated = speed().isolatePositiveResponse("41 0D 50");
        assertEquals("41 0D 50", isolated);
        assertEquals(80.0, speed().parseResult(isolated), 0.001);
    }

    @Test
    public void marker_only_at_odd_offset_is_rejected() {
        // "B4 10 D5 0x" contains "410D" starting at nibble index 1 — a
        // mid-byte coincidence, not a real positive response.
        assertNull(speed().isolatePositiveResponse("B4 10 D5 00"));
    }

    @Test
    public void odd_offset_match_is_skipped_in_favour_of_earlier_even_match() {
        // Real response followed by bytes that also happen to contain the
        // marker at an odd offset — the aligned occurrence must win.
        String isolated = speed().isolatePositiveResponse("41 0D 50 B4 10 D5");
        assertEquals("41 0D 50 B4 10 D5".replaceAll("\\s+", "").length() / 2,
                isolated.split(" ").length);
        assertEquals(80.0, speed().parseResult(isolated), 0.001);
    }

    @Test
    public void mixed_buffer_with_stale_elm_status_still_parses() {
        // The exact shape from the 2026-07-01 field log: valid data followed
        // by a stale STOPPED marker from a prior command.
        String isolated = speed().isolatePositiveResponse("01 0D 41 0D 3C STOPPED");
        assertEquals(60.0, speed().parseResult(isolated), 0.001);
    }

    @Test
    public void truncated_frame_throws_instead_of_returning_zero() {
        // parseResult contract after the sweep: fabricated zeros are banned.
        assertThrows(Exception.class, () -> speed().parseResult("41 0D"));
    }
}
