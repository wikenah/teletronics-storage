package com.teletronics.storage.presentation.http.response;

import java.nio.charset.StandardCharsets;

/**
 * Content-Disposition for downloads: always "attachment", with an ASCII "filename" fallback and the exact
 * name in "filename*" (RFC 6266 recommends sending both; RFC 8187 encoding).
 */
public final class ContentDispositionFormatter {

    private ContentDispositionFormatter() {
    }

    public static String attachment(String filename) {
        return "attachment; filename=\"" + asciiFallback(filename) + "\"; filename*=UTF-8''" + encode(filename);
    }

    static String asciiFallback(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            out.append(c < 0x20 || c > 0x7E || c == '"' || c == '\\' ? '_' : c);
        }
        return out.toString();
    }

    static String encode(String name) {
        StringBuilder out = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isAttrChar(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static boolean isAttrChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "!#$&+-.^_`|~".indexOf(c) >= 0;
    }
}
