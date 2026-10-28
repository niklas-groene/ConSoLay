package layout.template.io;

import constraints.spec.ConstraintSpec;
import layout.template.model.TemplateDef;
import layout.template.model.TemplateSource;
import layout.template.model.TemplateValidator;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The template file format: definitions of constraint types of a newer version survive reading and writing, the JSON
 * reader keeps to the standard's escapes, and local ids are never handed out twice. Run with {@code ant test}.
 */
public class TemplateFormatTest {

    /** A newer constraint type with values of forms this version does not use is read and written back unchanged. */
    @Test
    public void unknownConstraintTypeIsKeptAsWritten() throws Exception {
        String k = "{\"id\": \"k2\", \"type\": \"SPIRAL\", \"priority\": \"URGENT\", \"roles\": {\"centre\": [\"n1\"], "
                + "\"shape\": {\"kind\": \"log\"}}, \"params\": {\"order\": [\"n2\", \"n1\"], "
                + "\"turns\": {\"a\": {\"b\": 1}}}}";
        TemplateDef t = TemplateExamples.reaction();
        Map<String, Object> json = TemplateCodec.toJson(t);
        @SuppressWarnings("unchecked") List<Object> cs = (List<Object>) json.get("constraints");
        cs.add(Json.parse(k));
        TemplateDef read = TemplateCodec.fromJson(Json.parse(Json.write(json)));
        ConstraintSpec s = read.constraint("k2");
        assertTrue(s.refs().contains("n1"));
        boolean notApplicable = false;
        for (TemplateValidator.Problem p : TemplateValidator.validate(read, TemplateSource.embeddedIn(read))) {
            assertTrue(p.toString(), p.severity != TemplateValidator.Severity.ERROR);
            notApplicable |= p.severity == TemplateValidator.Severity.NOT_APPLICABLE && "k2".equals(p.elementId);
        }
        assertTrue(notApplicable);
        assertEquals(Json.write(Json.parse(k)), Json.write(TemplateCodec.constraint(s)));
    }

    /** Only four hex digits make a \\u escape. */
    @Test
    public void unicodeEscapesNeedFourHexDigits() throws Exception {
        assertEquals("A", ((Map<?, ?>) Json.parse("{\"label\": \"\\u0041\"}")).get("label"));
        for (String bad : new String[]{"\\u-001", "\\u+041", "\\u00g1"}) {
            try {
                Json.parse("{\"label\": \"" + bad + "\"}");
                fail(bad + " was accepted");
            } catch (Json.ParseException expected) {
                // refused, as it should be
            }
        }
    }

    /** The id of a deleted port is not given to the next port, also after the template went through a file. */
    @Test
    public void idsOfDeletedElementsAreNotReused() throws Exception {
        TemplateDef t = TemplateExamples.reaction();
        t.ports.removeIf(p -> p.id.equals("p3"));
        TemplateDef read = TemplateCodec.fromJson(Json.parse(Json.write(TemplateCodec.toJson(t))));
        assertEquals("p4", read.nextPortId());
        assertEquals("p5", read.nextPortId());
        assertEquals("n4", read.copy().nextNodeId());
    }
}
