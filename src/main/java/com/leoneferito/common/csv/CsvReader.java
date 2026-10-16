package com.leoneferito.common.csv;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class CsvReader {
    private static final Charset CP949 = Charset.forName("x-windows-949");
    private CsvReader() {}
    public static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF) {return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);}
        try {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();} catch (CharacterCodingException e) {return new String(bytes, CP949);}}
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {row.add(cell.toString());
            rows.add(row);
        }return rows;
    }
    public static String quote(String value) {if (value == null) {return "";}
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {return "\"" + value.replace("\"", "\"\"") + "\"";}return value;
    }
}