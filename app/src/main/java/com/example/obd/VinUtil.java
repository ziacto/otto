package com.example.obd;

/**
 * Parses an ELM327 Mode 09 PID 02 (VIN) response.
 * Format: "49 02 01 [17 ASCII bytes]" — typically arrives as multi-frame
 * (lines prefixed "0:" "1:" "2:" with a leading ISO-TP length triplet).
 */
public final class VinUtil {

    private VinUtil() {}

    /** Returns the parsed VIN, or null if not found. */
    public static String parseVin(String raw) {
        if (raw == null) return null;
        if (raw.toUpperCase().contains("NO DATA")) return null;

        String cleaned = raw.replace('\r', ' ').replace('\n', ' ');
        StringBuilder hex = new StringBuilder();
        for (String tokenRaw : cleaned.split("\\s+")) {
            if (tokenRaw.isEmpty()) continue;
            String token = tokenRaw;
            // Strip multi-frame prefix like "0:" / "1:"
            if (token.length() >= 2 && token.charAt(1) == ':') {
                token = token.substring(2);
                if (token.isEmpty()) continue;
            }
            // Skip ISO-TP total-length header (e.g. "014")
            if (token.length() == 3 && isHex(token)) continue;
            // Accept clean 2-char hex bytes
            if (token.length() == 2 && isHex(token)) hex.append(token.toUpperCase());
        }
        String hexStr = hex.toString();

        // Find Mode 09 PID 02 response header "490201"
        int idx = hexStr.indexOf("490201");
        if (idx < 0) return null;
        int p = idx + 6;

        int avail = hexStr.length() - p;
        if (avail < 2) return null;
        int byteCount = Math.min(17, avail / 2);

        StringBuilder vin = new StringBuilder();
        for (int i = 0; i < byteCount; i++) {
            int b;
            try {
                b = Integer.parseInt(hexStr.substring(p + i * 2, p + i * 2 + 2), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (b == 0) continue;            // padding
            if (b >= 32 && b < 127) vin.append((char) b);
        }
        String result = vin.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }
}
