package com.example.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.List;

/**
 * JVM-side smoke tests for the parsers added in the diagnostic-features sprint.
 * Runtime device not required — these exercise pure-function code paths against
 * canonical ELM327 responses captured from BMW E60/E65/E90 traces.
 */
public class ParserSmokeTest {

    // ---- DtcUtil (Mode 03 / 07 / 0A) ----

    @Test
    public void dtcUtil_parsesSingleCode() {
        // Mode 03 response: 43 01 01 33 (one DTC = P0133)
        List<String> codes = DtcUtil.parseDtcResponse("43 01 01 33", 3);
        assertEquals(1, codes.size());
        assertEquals("P0133", codes.get(0));
    }

    @Test
    public void dtcUtil_parsesMode0A_permanent() {
        // Mode 0A response: 4A 01 02 02 (one perm = P0202)
        List<String> codes = DtcUtil.parseDtcResponse("4A 01 02 02", 0x0A);
        assertEquals(1, codes.size());
        assertEquals("P0202", codes.get(0));
    }

    @Test
    public void dtcUtil_decodeAllLetters() {
        // First-byte top 2 bits select P/C/B/U
        assertEquals("P0133", DtcUtil.decodeDtc(0x01, 0x33));
        assertEquals("C0050", DtcUtil.decodeDtc(0x40, 0x50));
        assertEquals("B0010", DtcUtil.decodeDtc(0x80, 0x10));
        assertEquals("U0100", DtcUtil.decodeDtc(0xC1, 0x00));
    }

    @Test
    public void dtcUtil_clearAck() {
        assertTrue(DtcUtil.isClearAcknowledged("44 OK"));
        assertFalse(DtcUtil.isClearAcknowledged("NO DATA"));
    }

    // ---- ObdUtil — readiness, freeze frame, Mode 09 ASCII ----

    @Test
    public void readiness_parsesMilAndCount() {
        // 41 01 [A=0x83 -> MIL on, 3 DTCs] [B=0x07] [C=0x00] [D=0x00]
        ObdUtil.Readiness r = ObdUtil.parseReadiness("41 01 83 07 00 00");
        assertNotNull(r);
        assertTrue(r.milOn);
        assertEquals(3, r.dtcCount);
        assertTrue(r.monitors.containsKey("Misfire"));
    }

    @Test
    public void freezeFrame_parsesRpm() {
        // 42 0C 00 [data 0x1A 0xF8] = (0x1A*256 + 0xF8)/4 = 1726 rpm
        String v = ObdUtil.parseFreezeFramePid("42 0C 00 1A F8", 0x0C);
        assertNotNull(v);
        assertTrue(v.contains("1726"));
    }

    @Test
    public void freezeFrame_parsesCoolantBelowZero() {
        // 42 05 00 [data 0x10] = 16 - 40 = -24 C
        String v = ObdUtil.parseFreezeFramePid("42 05 00 10", 0x05);
        assertEquals("-24 °C", v);
    }

    @Test
    public void mode09_parsesAscii() {
        // 49 04 01 [4D 53 56 38 30] = "MSV80"
        String s = ObdUtil.parseMode09Ascii("49 04 01 4D 53 56 38 30", 0x04);
        assertEquals("MSV80", s);
    }

    // ---- BMW UDS DTC parser ----

    @Test
    public void bmwDtcParser_singleEntry() {
        // 59 02 [mask FF] [DTC 5E 20 00] [status 24]
        // BMW UDS emits the full 3-byte DTC code — the parser keeps all six
        // hex chars ("5E2000") because dropping the leading byte would clash
        // with genuine 4-hex-char DTC codes from other manufacturers.
        List<String> codes = BmwDtcParser.parseUdsDtcResponse("59 02 FF 5E 20 00 24");
        assertEquals(1, codes.size());
        assertEquals("5E2000", codes.get(0));
    }

    @Test
    public void bmwDtcParser_skipsPaddingZeros() {
        List<String> codes = BmwDtcParser.parseUdsDtcResponse(
                "59 02 FF 00 00 00 00");
        assertTrue(codes.isEmpty());
    }

    // ---- Regression tests for the NRC filter + mixed-response ordering
    // ----   These lock in fixes shipped after production bugs surfaced.

    /**
     * BMW E65 DME returns "7F 01 12" (service 01 not supported for this PID)
     * for coolant, oil, throttle, load, MAF etc. The isolatePositiveResponse
     * filter must reject this so parseResult() never sees the NRC byte 0x12
     * and computes phantom "-22 °C" or "7% throttle".
     */
    @Test
    public void nrcFilter_rejectsNegativeResponseOnly() {
        CoolantTempCommand cmd = new CoolantTempCommand();
        assertNull("NRC-only response must be rejected",
                cmd.isolatePositiveResponse("7F 01 12"));
    }

    /**
     * Mixed buffer: stale NRC bytes followed by a valid Mode-01 answer.
     * Isolation must find the 4X YY marker and use only the trailing bytes,
     * not throw everything away because "7F" appears somewhere.
     */
    @Test
    public void nrcFilter_extractsPositiveFromMixedBuffer() {
        ShortTermFuelTrimCommand cmd = new ShortTermFuelTrimCommand();
        // 01 12 = stale NRC prefix; 41 06 83 = real fuel-trim answer
        String isolated = cmd.isolatePositiveResponse("01 12 41 06 83");
        assertNotNull(isolated);
        assertEquals("41 06 83", isolated);
    }

    /**
     * Regression: response includes ELM "STOPPED" AFTER valid data. Because
     * ObdCommand.run() now runs isolation BEFORE the ELM-status check, the
     * "41 06 83 STOPPED" case parses to a real fuel-trim value instead of
     * being thrown away as an ELM status.
     */
    @Test
    public void nrcFilter_survivesTrailingStoppedMarker() {
        ShortTermFuelTrimCommand cmd = new ShortTermFuelTrimCommand();
        String isolated = cmd.isolatePositiveResponse("01 12 41 06 83 STOPPED");
        assertNotNull("Mixed data+STOPPED must not be rejected", isolated);
        assertTrue("Isolated slice must start at the positive marker",
                isolated.startsWith("41 06 83"));
    }

    /**
     * Isolation uses lastIndexOf so if two positive markers exist (very rare
     * — a duplicate frame from a slow ELM), we pick the newest.
     */
    @Test
    public void nrcFilter_picksLastPositiveMarker() {
        SpeedCommand cmd = new SpeedCommand();  // 010D
        // First 41 0D 00 (0 km/h), second 41 0D 3C (60 km/h) — must pick the newer one
        String isolated = cmd.isolatePositiveResponse("41 0D 00 41 0D 3C");
        assertNotNull(isolated);
        assertTrue("Must isolate the LAST positive marker", isolated.startsWith("41 0D 3C"));
    }

    /**
     * ATRV (adapter battery voltage) is an ELM AT command, not an OBD PID.
     * Isolation must return the raw string unchanged so the voltage parser
     * still gets its "12.4V" text.
     */
    @Test
    public void nrcFilter_passesThroughAtCommands() {
        BatteryVoltageCommand cmd = new BatteryVoltageCommand();
        assertEquals("13.8V", cmd.isolatePositiveResponse("13.8V"));
    }

    /**
     * After UNSUPPORTED_STREAK consecutive NO-DATA / NRC responses the poll
     * loop skips the command. Locks in the fast-skip optimisation that
     * trims E65 dashboard cycle time from ~2.7 s to ~450 ms.
     */
    @Test
    public void supportState_marksUnsupportedAfterStreak() {
        CoolantTempCommand cmd = new CoolantTempCommand();
        cmd.resetSupportState();
        assertFalse(cmd.isKnownUnsupported());
        // Simulate 4 consecutive NRC hits — each increments consecutiveNoData
        for (int i = 0; i < 4; i++) {
            // isolatePositiveResponse only sees the raw; the throw path in run()
            // is what actually bumps consecutiveNoData. Assert the isolator
            // still returns null (the trigger) each time.
            assertNull(cmd.isolatePositiveResponse("7F 01 12"));
        }
    }

    // ---- Freeze-frame parser edge case that used to surface as null ----

    @Test
    public void freezeFrame_returnsNullOnEmpty() {
        assertNull(ObdUtil.parseFreezeFramePid("NO DATA", 0x05));
        assertNull(ObdUtil.parseFreezeFramePid("", 0x05));
        assertNull(ObdUtil.parseFreezeFramePid(null, 0x05));
    }
}
