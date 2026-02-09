package com.sms.gateway.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits UCS-2 encoded message bytes into SMS-sized segments.
 * <p>
 * GSM/SMS payload constraints:
 * - Total user data per SMS is 140 bytes.
 * - For UCS-2: 70 chars = 140 bytes per single-part SMS.
 * - For concatenated SMS using a 6-byte UDH: payload per part is 134 bytes.
 * <p>
 * Assumption:
 * - Input is UCS-2 bytes (UTF-16BE) and should be even-length. We still guard against odd length.
 */
public final class Ucs2Segmentation {
    private Ucs2Segmentation() {
    }

    public static List<byte[]> split(byte[] ucs2Bytes) {
        if (ucs2Bytes == null || ucs2Bytes.length == 0) return List.of(new byte[0]);

        int singleMax = 140;
        if (ucs2Bytes.length <= singleMax) return List.of(ucs2Bytes);

        int partMax = 134;
        List<byte[]> parts = new ArrayList<>();
        int offset = 0;

        while (offset < ucs2Bytes.length) {
            int len = Math.min(partMax, ucs2Bytes.length - offset);

            // Never split in the middle of a UCS-2 character (2 bytes).
            if ((len % 2) == 1) len--;

            // Safety: if remaining bytes is 1 (odd input), len would become 0 -> infinite loop.
            if (len <= 0) {
                throw new IllegalArgumentException("Invalid UCS-2 byte array length (odd length?): " + ucs2Bytes.length);
            }

            byte[] chunk = new byte[len];
            System.arraycopy(ucs2Bytes, offset, chunk, 0, len);
            parts.add(chunk);
            offset += len;
        }

        return parts;
    }
}