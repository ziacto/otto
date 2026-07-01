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
        List<String> codes = BmwDtcParser.parseUdsDtcResponse("59 02 FF 5E 20 00 24");
        assertEquals(1, codes.size());
        // BMW short form drops the leading byte (5E) -> "2000"
        assertEquals("2000", codes.get(0));
    }

    @Test
    public void bmwDtcParser_skipsPaddingZeros() {
        List<String> codes = BmwDtcParser.parseUdsDtcResponse(
                "59 02 FF 00 00 00 00");
        assertTrue(codes.isEmpty());
    }
}
