package headless;
import java.util.*;
import java.util.regex.*;

/** Turns VANTED's HTML node labels into plain text (one line per {@code <br>}). */
public class HtmlPlainText {

    private static final Map<String, String> NAMED_ENTITIES = new HashMap<>();

    /** A character reference: decimal (group 1), hexadecimal (group 2) or named (group 3). */
    private static final Pattern ENTITY = Pattern.compile("&(?:#(\\d+)|#[xX]([0-9a-fA-F]+)|([a-zA-Z][a-zA-Z0-9]+));");

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

        String plain = text.replaceAll("(?i)<br\\s*/?>", "\n");
        plain = plain.replaceAll("(?is)<script.*?</script>", "");
        plain = plain.replaceAll("(?is)<style.*?</style>", "");
        plain = plain.replaceAll("(?i)</?\\w+[^>]*>", "");
        // Only now: an escaped '<' is text, not the start of a tag.
        plain = decodeHtmlEntities(plain);
        plain = plain.replace("\r", "");
        plain = plain.replaceAll("\\n[ \\t\\x0B\\f]+", "\n").trim();

        return plain;
    }

    /**
     * Decodes numeric (decimal and hexadecimal) entities and a few common named ones in one pass, so a decoded
     * {@code &} never starts another entity; unknown names and invalid codes stay as they are.
     */
    private static String decodeHtmlEntities(String s) {
        if (s == null || s.indexOf('&') < 0) return s;

        Matcher m = ENTITY.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String repl = null;
            if (m.group(3) != null) {
                repl = NAMED_ENTITIES.get(m.group(3));
            } else {
                try {
                    int code = m.group(1) != null ? Integer.parseInt(m.group(1)) : Integer.parseInt(m.group(2), 16);
                    repl = new String(Character.toChars(code));
                } catch (Exception e) {
                    // not a valid code point: keep the reference
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(repl != null ? repl : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
