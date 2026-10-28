package headless;
import java.util.*;
import java.util.regex.*;

/** Turns VANTED's HTML node labels into plain text (one line per {@code <br>}). */
public class HtmlPlainText {

    private static final Map<String, String> NAMED_ENTITIES = new HashMap<>();

    static {
        NAMED_ENTITIES.put("amp", "&");
        NAMED_ENTITIES.put("lt", "<");
        NAMED_ENTITIES.put("gt", ">");
        NAMED_ENTITIES.put("quot", "\"");
        NAMED_ENTITIES.put("apos", "'");
        NAMED_ENTITIES.put("nbsp", "\u00A0");
    }

    public static String htmlToPlainText(String text) {
        if (text == null) return "";

        String plain = decodeHtmlEntities(text);
        plain = plain.replaceAll("(?i)<br\\s*/?>", "\n");
        plain = plain.replaceAll("(?is)<script.*?</script>", "");
        plain = plain.replaceAll("(?is)<style.*?</style>", "");
        plain = plain.replaceAll("(?i)</?\\w+[^>]*>", "");
        plain = plain.replace("\r", "");
        plain = plain.replaceAll("\\n[ \\t\\x0B\\f]+", "\n").trim();

        return plain;
    }

    /** Decodes numeric (decimal and hexadecimal) entities and a few common named ones; unknown names stay. */
    private static String decodeHtmlEntities(String s) {
        if (s == null || s.indexOf('&') < 0) return s;

        s = replaceNumeric(s, Pattern.compile("&#(\\d+);"), 10);
        s = replaceNumeric(s, Pattern.compile("&#x([0-9a-fA-F]+);"), 16);

        Matcher m = Pattern.compile("&([a-zA-Z][a-zA-Z0-9]+);").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String repl = NAMED_ENTITIES.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(repl != null ? repl : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Replaces every match of {@code p} by the character whose code group 1 holds; an invalid code stays as it is. */
    private static String replaceNumeric(String input, Pattern p, int radix) {
        Matcher m = p.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                int code = Integer.parseInt(m.group(1), radix);
                String repl = new String(Character.toChars(code));
                m.appendReplacement(sb, Matcher.quoteReplacement(repl));
            } catch (Exception e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
