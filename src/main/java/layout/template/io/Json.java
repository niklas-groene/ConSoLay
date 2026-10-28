package layout.template.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for the template files. VANTED ships org.json, but its objects forget the order of
 * their keys; the template files keep a fixed, readable order (and saving a loaded file without changes gives the same
 * text), so this class reads into {@link LinkedHashMap} / {@link ArrayList} / {@link String} / {@link Double} /
 * {@link Boolean} / null and writes them back indented. Numbers must be finite: NaN and infinities are refused.
 *
 * @author Niklas Gröne
 */
public final class Json {

    private Json() { }

    /** A syntax error; the message names its line and column. */
    public static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String message, int line, int column) {
            super(message + " (line " + line + ", column " + column + ")");
        }
    }

    public static Object parse(String text) throws ParseException {
        Parser p = new Parser(text);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') p.i = 1;   // byte order mark
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < text.length()) throw p.error("Unexpected text after the end of the data");
        return v;
    }

    private static final class Parser {
        private static final String HEX_DIGITS = "0123456789abcdefABCDEF";
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        ParseException error(String msg) {
            int line = 1, col = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) {
                if (s.charAt(k) == '\n') { line++; col = 1; } else col++;
            }
            return new ParseException(msg, line, col);
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() throws ParseException {
            if (i >= s.length()) throw error("Unexpected end of the data");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw error("Unexpected character '" + c + "'");
            }
        }

        void literal(String word) throws ParseException {
            if (!s.startsWith(word, i)) throw error("Expected '" + word + "'");
            i += word.length();
        }

        Map<String, Object> object() throws ParseException {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw error("Expected a name in quotes");
                String key = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw error("Expected ':' after \"" + key + "\"");
                i++;
                ws();
                if (m.containsKey(key)) throw error("The name \"" + key + "\" appears twice");
                m.put(key, value());
                ws();
                if (i >= s.length()) throw error("Unexpected end inside an object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') { i--; throw error("Expected ',' or '}'"); }
            }
        }

        List<Object> array() throws ParseException {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i >= s.length()) throw error("Unexpected end inside a list");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') { i--; throw error("Expected ',' or ']'"); }
            }
        }

        String string() throws ParseException {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw error("Unterminated text");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw error("Unterminated text");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u': {
                            if (i + 4 > s.length()) throw error("Bad \\u escape");
                            int code = 0;
                            for (int k = 0; k < 4; k++) {
                                int d = HEX_DIGITS.indexOf(s.charAt(i + k));
                                if (d < 0) throw error("Bad \\u escape");
                                code = code * 16 + (d < 16 ? d : d - 6);
                            }
                            sb.append((char) code);
                            i += 4;
                            break;
                        }
                        default: throw error("Bad escape '\\" + e + "'");
                    }
                } else {
                    if (c < 0x20) throw error("Control character inside text");
                    sb.append(c);
                }
            }
        }

        Double number() throws ParseException {
            int start = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(start, i);
            try {
                double d = Double.parseDouble(t);
                if (!Double.isFinite(d)) throw error("Number out of range: " + t);
                return d;
            } catch (NumberFormatException e) {
                i = start;
                throw error("Bad number '" + t + "'");
            }
        }
    }

    /** {@code value} as indented JSON text (two spaces), ending without a newline. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Number) {
            sb.append(number(((Number) v).doubleValue()));
        } else if (v instanceof double[]) {
            List<Object> l = new ArrayList<>();
            for (double d : (double[]) v) l.add(d);
            write(sb, l, indent);
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            if (flat(m.values()) && m.size() <= 4) {
                sb.append("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(", ");
                    first = false;
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(": ");
                    write(sb, e.getValue(), indent);
                }
                sb.append("}");
                return;
            }
            sb.append("{\n");
            int k = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent + 1);
                if (++k < m.size()) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) { sb.append("[]"); return; }
            if (flat(l)) {
                sb.append('[');
                for (int k = 0; k < l.size(); k++) {
                    if (k > 0) sb.append(", ");
                    write(sb, l.get(k), indent);
                }
                sb.append(']');
                return;
            }
            sb.append("[\n");
            for (int k = 0; k < l.size(); k++) {
                pad(sb, indent + 1);
                write(sb, l.get(k), indent + 1);
                if (k < l.size() - 1) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Cannot write " + v.getClass().getSimpleName() + " as JSON");
        }
    }

    /** True if every value is a scalar or a short list of numbers (written on one line). */
    private static boolean flat(Iterable<?> values) {
        int n = 0;
        for (Object o : values) {
            n++;
            if (o instanceof Map || (o instanceof List && !numbers((List<?>) o))) return false;
            if (o instanceof String && ((String) o).length() > 40) return false;
        }
        return n <= 12;
    }

    private static boolean numbers(List<?> l) {
        if (l.size() > 4) return false;
        for (Object o : l) if (!(o instanceof Number)) return false;
        return true;
    }

    /** An integral value without decimals, else the shortest exact text; NaN and infinities are refused. */
    static String number(double d) {
        if (!Double.isFinite(d)) throw new IllegalArgumentException("Refusing to write " + d + " (the format allows only finite numbers)");
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return Double.toString(d);
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int k = 0; k < indent; k++) sb.append("  ");
    }
}
