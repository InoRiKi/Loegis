package src.server;

import java.util.List;
import java.util.Map;

/**
 * Json
 * ----
 * ตัวช่วยแปลง Java object พื้นฐาน (Map, List, String, Number, Boolean, null) เป็น JSON string
 *
 * เขียนเองแบบเรียบง่าย ไม่พึ่ง library ภายนอก (เช่น Gson/Jackson) เพื่อให้โปรเจกต์มี
 * dependency แค่ jgrapht ตัวเดียวเหมือนเดิม ไม่เพิ่มความซับซ้อนในการ build
 *
 * รองรับเฉพาะสิ่งที่ระบบนี้ต้องใช้จริง: Map<String,Object>, List<Object>, String, Number,
 * Boolean, null และ nested ของพวกนี้ — ไม่ใช่ JSON parser/writer แบบสมบูรณ์
 *
 * สำคัญ: ทุก response ที่ฝั่ง server ส่งออกไปต้องเป็น UTF-8 เสมอ (ตัวอักษรไทยใน log/label
 * ต้องไม่บูด) — การ encode เป็น byte[] แบบ UTF-8 ทำที่ฝั่ง ApiServer ตอนเขียนลง response body
 */
public final class Json {

    private Json() {}

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            writeMap((Map<String, Object>) value, sb);
        } else if (value instanceof List) {
            writeList((List<Object>) value, sb);
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number) {
            writeNumber((Number) value, sb);
        } else {
            // fallback: ไม่ควรเกิดขึ้นถ้าใช้แต่ type ที่รองรับ แต่กันพังด้วยการแปลงเป็น string
            writeString(value.toString(), sb);
        }
    }

    private static void writeMap(Map<String, Object> map, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(entry.getKey(), sb);
            sb.append(':');
            write(entry.getValue(), sb);
        }
        sb.append('}');
    }

    private static void writeList(List<Object> list, StringBuilder sb) {
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) sb.append(',');
            first = false;
            write(item, sb);
        }
        sb.append(']');
    }

    private static void writeNumber(Number n, StringBuilder sb) {
        double d = n.doubleValue();
        // ตัด .0 ส่วนเกินของจำนวนเต็มออก เพื่อให้ JS อ่านง่ายขึ้น (ไม่บังคับ แค่สวยงาม)
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        // ตัวอักษรไทยและ emoji ปล่อยผ่านตรงๆ ไม่ escape เป็น \\uXXXX
                        // เพราะ response encode เป็น UTF-8 ทั้งก้อนอยู่แล้วที่ ApiServer
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ==================================================================
    //  Minimal JSON PARSER — ใช้อ่าน request body จาก frontend (เช่น ตอน POST พารามิเตอร์)
    // ==================================================================

    /** parse JSON object string ระดับเดียว (flat) เป็น Map<String,Object> — พอสำหรับ request body ของ API นี้ */
    public static Map<String, Object> parseObject(String json) {
        return (Map<String, Object>) new Parser(json).parseValue();
    }

    private static class Parser {
        private final String s;
        private int pos = 0;

        Parser(String s) { this.s = s; }

        Object parseValue() {
            skipWhitespace();
            char c = s.charAt(pos);
            if (c == '{') return parseObjectInternal();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') { pos += 4; return null; }
            return parseNumber();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parseObjectInternal() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (s.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                pos++; // :
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (s.charAt(pos) == ',') { pos++; continue; }
                if (s.charAt(pos) == '}') { pos++; break; }
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new java.util.ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (s.charAt(pos) == ',') { pos++; continue; }
                if (s.charAt(pos) == ']') { pos++; break; }
            }
            return list;
        }

        String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (s.charAt(pos) != '"') {
                char c = s.charAt(pos);
                if (c == '\\') {
                    pos++;
                    char esc = s.charAt(pos);
                    switch (esc) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'u':
                            String hex = s.substring(pos + 1, pos + 5);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
                pos++;
            }
            pos++; // closing quote
            return sb.toString();
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            pos += 5; return Boolean.FALSE;
        }

        Double parseNumber() {
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '-'
                    || s.charAt(pos) == '+' || s.charAt(pos) == '.' || s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
