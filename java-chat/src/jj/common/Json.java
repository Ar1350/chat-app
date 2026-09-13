package jj.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析 / 生成器（无第三方依赖）。
 * 支持：对象 / 数组 / 字符串 / 数字 / 布尔 / null。
 * 数字解析为 Long（整数）或 Double（小数）。
 */
public final class Json {

    private Json() {}

    // ================= 解析 =================

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value();
        return v;
    }

    private static final class P {
        final String s;
        int i;
        P(String s) { this.s = s; }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            switch (c) {
                case '{': return obj();
                case '[': return arr();
                case '"': return str();
                case 't': i += 4; return Boolean.TRUE;
                case 'f': i += 5; return Boolean.FALSE;
                case 'n': i += 4; return null;
                default:  return num();
            }
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                i++; // 跳过 ':'
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') break;
                // c == ',' 时继续
            }
            return m;
        }

        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') break;
            }
            return l;
        }

        String str() {
            StringBuilder b = new StringBuilder();
            i++; // 跳过开头 "
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':  b.append('"');  break;
                        case '\\': b.append('\\'); break;
                        case '/':  b.append('/');  break;
                        case 'b':  b.append('\b'); break;
                        case 'f':  b.append('\f'); break;
                        case 'n':  b.append('\n'); break;
                        case 'r':  b.append('\r'); break;
                        case 't':  b.append('\t'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        Object num() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
                return Long.parseLong(n);
            }
            return Double.parseDouble(n);
        }
    }

    // ================= 生成（紧凑） =================

    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        w(o, b);
        return b.toString();
    }

    private static void w(Object o, StringBuilder b) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof String) { ws((String) o, b); return; }
        if (o instanceof Boolean || o instanceof Number) { b.append(o.toString()); return; }
        if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                ws(String.valueOf(e.getKey()), b);
                b.append(':');
                w(e.getValue(), b);
            }
            b.append('}');
            return;
        }
        if (o instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object v : (Iterable<?>) o) {
                if (!first) b.append(',');
                first = false;
                w(v, b);
            }
            b.append(']');
            return;
        }
        ws(String.valueOf(o), b);
    }

    // ================= 生成（缩进美化，便于人工查看 db 文件） =================

    public static String writePretty(Object o) {
        StringBuilder b = new StringBuilder();
        wp(o, b, 0);
        return b.toString();
    }

    private static void wp(Object o, StringBuilder b, int ind) {
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(",\n");
                first = false;
                indent(b, ind + 1);
                ws(String.valueOf(e.getKey()), b);
                b.append(": ");
                wp(e.getValue(), b, ind + 1);
            }
            b.append('\n');
            indent(b, ind);
            b.append('}');
            return;
        }
        if (o instanceof Iterable) {
            List<Object> l = new ArrayList<>();
            for (Object v : (Iterable<?>) o) l.add(v);
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            boolean first = true;
            for (Object v : l) {
                if (!first) b.append(",\n");
                first = false;
                indent(b, ind + 1);
                wp(v, b, ind + 1);
            }
            b.append('\n');
            indent(b, ind);
            b.append(']');
            return;
        }
        w(o, b);
    }

    private static void indent(StringBuilder b, int n) {
        for (int i = 0; i < n; i++) b.append("  ");
    }

    // ================= 字符串转义 =================

    private static void ws(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ================= 便捷取值 =================

    public static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static Long lng(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) return null;
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return (List<Object>) o;
    }
}
