package org.rama.service.document.template.json;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class DocumentTemplateJsonRendererTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Default renderer: DocumentForm resolves to nothing. */
    private final DocumentTemplateJsonRenderer renderer =
            new DocumentTemplateJsonRenderer(code -> Optional.empty(), JSON);

    // ---------- helpers ----------

    private static Map<String, Object> item(String inputType, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inputType", inputType);
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @SafeVarargs
    private static List<Map<String, Object>> template(Map<String, Object>... items) {
        return new ArrayList<>(List.of(items));
    }

    private static String allText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : doc.getParagraphs()) sb.append(p.getText()).append('\n');
        for (XWPFTable t : doc.getTables()) {
            t.getRows().forEach(r -> r.getTableCells()
                    .forEach(c -> sb.append(c.getText()).append('\n')));
        }
        return sb.toString();
    }

    private XWPFDocument render(Object templateJson) {
        XWPFDocument doc = new XWPFDocument();
        renderer.render(doc, templateJson);
        return doc;
    }

    // ---------- field rendering ----------

    @Test
    void rendersTextFieldAsLabelPlusPlaceholder() throws Exception {
        try (XWPFDocument doc = render(template(
                item("VTextField", "width", 6, "inputLabel", "Full Name", "variableName", "fullName")))) {
            assertThat(doc.getTables()).hasSize(1);
            String cell = doc.getTables().get(0).getRow(0).getCell(0).getText();
            assertThat(cell).contains("Full Name").contains("{{fullName}}");
        }
    }

    @Test
    void packsTwoHalfWidthFieldsIntoOneRowWithTwoCells() throws Exception {
        try (XWPFDocument doc = render(template(
                item("VTextField", "width", 6, "inputLabel", "First", "variableName", "first"),
                item("VTextField", "width", 6, "inputLabel", "Last", "variableName", "last")))) {
            assertThat(doc.getTables()).hasSize(1);
            assertThat(doc.getTables().get(0).getRow(0).getTableCells()).hasSize(2);
        }
    }

    @Test
    void wrapsToNewRowWhenWidthExceedsTwelve() throws Exception {
        try (XWPFDocument doc = render(template(
                item("VTextField", "width", 8, "inputLabel", "A", "variableName", "a"),
                item("VTextField", "width", 8, "inputLabel", "B", "variableName", "b")))) {
            assertThat(doc.getTables()).hasSize(2);
        }
    }

    @Test
    void separatorForcesRowBreak() throws Exception {
        try (XWPFDocument doc = render(template(
                item("VTextField", "width", 6, "inputLabel", "A", "variableName", "a"),
                item("Separator"),
                item("VTextField", "width", 6, "inputLabel", "B", "variableName", "b")))) {
            assertThat(doc.getTables()).hasSize(2);
            assertThat(doc.getTables().get(0).getRow(0).getTableCells()).hasSize(1);
            assertThat(doc.getTables().get(1).getRow(0).getTableCells()).hasSize(1);
        }
    }

    @Test
    void rendersHeaderAsBoldBodyParagraphWithoutPlaceholder() throws Exception {
        try (XWPFDocument doc = render(template(
                item("Header", "inputLabel", "Patient Profile")))) {
            assertThat(doc.getTables()).isEmpty();
            XWPFParagraph header = doc.getParagraphs().stream()
                    .filter(p -> p.getText().contains("Patient Profile"))
                    .findFirst().orElseThrow();
            assertThat(header.getRuns()).isNotEmpty();
            assertThat(header.getRuns().get(0).isBold()).isTrue();
            assertThat(header.getText()).doesNotContain("{{");
        }
    }

    // ---------- hook-attribute placeholders ----------

    @Test
    void formDateEmitsDateHookAttribute() throws Exception {
        try (XWPFDocument doc = render(template(
                item("FormDate", "width", 6, "inputLabel", "DOB", "variableName", "birthDate")))) {
            assertThat(allText(doc)).contains("{{birthDate;date}}");
        }
    }

    @Test
    void formTimeAndDateTimeEmitTheirHooks() throws Exception {
        try (XWPFDocument time = render(template(item("FormTime", "width", 6, "variableName", "t")));
             XWPFDocument dateTime = render(template(item("FormDateTime", "width", 6, "variableName", "ts")))) {
            assertThat(allText(time)).contains("{{t;time}}");
            assertThat(allText(dateTime)).contains("{{ts;datetime}}");
        }
    }

    @Test
    void masterAutocompleteEmitsMasterHookWithGroupKey() throws Exception {
        try (XWPFDocument doc = render(template(
                item("MasterAutocomplete", "width", 6, "inputLabel", "Country",
                        "variableName", "country", "inputOptions", "countries")))) {
            assertThat(allText(doc)).contains("{{country;master;groupKey=countries}}");
        }
    }

    @Test
    void masterAutocompleteOmitsGroupKeyWhenInputOptionsMissing() throws Exception {
        // No inputOptions: emit a bare ;master (MasterHooks no-ops, renders the raw value)
        // rather than ;master;groupKey= which would look up an empty group and render blank.
        try (XWPFDocument doc = render(template(
                item("MasterAutocomplete", "width", 6, "variableName", "country")))) {
            String text = allText(doc);
            assertThat(text).contains("{{country;master}}");
            assertThat(text).doesNotContain("groupKey=");
        }
    }

    @Test
    void masterAutocompleteOmitsGroupKeyWhenInputOptionsNotAString() throws Exception {
        try (XWPFDocument doc = render(template(
                item("MasterAutocomplete", "width", 6, "variableName", "country",
                        "inputOptions", Map.of("foo", "bar"))))) {
            String text = allText(doc);
            assertThat(text).contains("{{country;master}}");
            assertThat(text).doesNotContain("groupKey=");
        }
    }

    @Test
    void checkboxAndSwitchEmitCheckboxHook() throws Exception {
        try (XWPFDocument checkbox = render(template(item("VCheckbox", "width", 6, "variableName", "agree")));
             XWPFDocument vswitch = render(template(item("VSwitch", "width", 6, "variableName", "active")))) {
            assertThat(allText(checkbox)).contains("{{agree;checkbox}}");
            assertThat(allText(vswitch)).contains("{{active;checkbox}}");
        }
    }

    @Test
    void checkboxGroupEmitsJoinHook() throws Exception {
        try (XWPFDocument doc = render(template(item("FormCheckboxGroup", "width", 6, "variableName", "tags")))) {
            assertThat(allText(doc)).contains("{{tags;join}}");
        }
    }

    @Test
    void signPadAndFileEmitImageHook() throws Exception {
        try (XWPFDocument signPad = render(template(item("FormSignPad", "width", 6, "variableName", "signature")));
             XWPFDocument file = render(template(item("FormFile", "width", 6, "variableName", "attachment")))) {
            assertThat(allText(signPad)).contains("{{signature;image}}");
            assertThat(allText(file)).contains("{{attachment;image}}");
        }
    }

    // ---------- printConfig (author-supplied docx hook attributes) ----------

    @Test
    void printConfigObjectAppendsAttributesToPlaceholder() throws Exception {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("singleline", true);
        cfg.put("prefix", "Mr.");
        try (XWPFDocument doc = render(template(
                item("VTextField", "width", 6, "variableName", "name", "printConfig", cfg)))) {
            assertThat(allText(doc)).contains("{{name;singleline;prefix=Mr.}}");
        }
    }

    @Test
    void printConfigStringFormIsAppended() throws Exception {
        try (XWPFDocument doc = render(template(
                item("FormDate", "width", 6, "variableName", "d", "printConfig", "format=dd/MM/yyyy")))) {
            assertThat(allText(doc)).contains("{{d;date;format=dd/MM/yyyy}}");
        }
    }

    @Test
    void printConfigSuppliesImageWidth() throws Exception {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("width", 2);
        try (XWPFDocument doc = render(template(
                item("FormSignPad", "width", 6, "variableName", "sig", "printConfig", cfg)))) {
            assertThat(allText(doc)).contains("{{sig;image;width=2}}");
        }
    }

    @Test
    void printConfigCanIntroduceAHook() throws Exception {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("qrcode", true);
        cfg.put("width", 1);
        try (XWPFDocument doc = render(template(
                item("VTextField", "width", 6, "variableName", "code", "printConfig", cfg)))) {
            assertThat(allText(doc)).contains("{{code;qrcode;width=1}}");
        }
    }

    @Test
    void printConfigStripsBracesThatWouldBreakThePlaceholder() throws Exception {
        // Author-supplied printConfig must not be able to truncate the {{…}} envelope.
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("prefix", "a}}b{{c");
        try (XWPFDocument doc = render(template(
                item("VTextField", "width", 6, "variableName", "name", "printConfig", cfg)))) {
            String text = allText(doc);
            assertThat(text).contains("{{name;prefix=abc}}");
            assertThat(text).doesNotContain("a}}b");
        }
    }

    @Test
    void printConfigRawStringStripsBraces() throws Exception {
        try (XWPFDocument doc = render(template(
                item("FormDate", "width", 6, "variableName", "d", "printConfig", "format=dd}}/MM")))) {
            assertThat(allText(doc)).contains("{{d;date;format=dd/MM}}");
        }
    }

    // ---------- FormFile: image vs. file name ----------

    @Test
    void formFileWithImageAcceptEmitsImageHook() throws Exception {
        try (XWPFDocument doc = render(template(
                item("FormFile", "width", 6, "variableName", "photo",
                        "inputAttributes", "accept=\"image/*\"")))) {
            assertThat(allText(doc)).contains("{{photo;image}}");
        }
    }

    @Test
    void formFileWithNonImageAcceptRendersOriginalFileName() throws Exception {
        try (XWPFDocument doc = render(template(
                item("FormFile", "width", 6, "variableName", "doc",
                        "inputAttributes", "accept=\"application/pdf\"")))) {
            String text = allText(doc);
            assertThat(text).contains("{{doc.originalFileName}}");
            assertThat(text).doesNotContain(";image");
        }
    }

    @Test
    void formFileMultipleNonImageJoinsOriginalFileNames() throws Exception {
        try (XWPFDocument doc = render(template(
                item("FormFile", "width", 6, "variableName", "docs",
                        "inputAttributes", "accept=\"application/pdf\" multiple")))) {
            assertThat(allText(doc)).contains("{{docs;join=originalFileName}}");
        }
    }

    @Test
    void unknownTypeFallsBackToPlainLabelAndPlaceholder() throws Exception {
        try (XWPFDocument doc = render(template(
                item("VFancyThing", "width", 6, "inputLabel", "Fancy", "variableName", "x")))) {
            String text = allText(doc);
            assertThat(text).contains("Fancy").contains("{{x}}");
        }
    }

    @Test
    void customCodeRendersRawFragmentAsPlainParagraph() throws Exception {
        try (XWPFDocument doc = render(template(
                item("CustomCode", "inputCustomCode", "Signed by committee")))) {
            assertThat(allText(doc)).contains("Signed by committee");
            assertThat(doc.getTables()).isEmpty();
        }
    }

    // ---------- tables ----------

    @Test
    void formTableEmitsHeaderRowAndSectionMarkers() throws Exception {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("title", "Contacts");
        options.put("headers", List.of(
                Map.of("title", "Name", "key", "name"),
                Map.of("title", "Phone", "key", "phone")));
        options.put("formTemplate", List.of(
                item("VTextField", "inputLabel", "Name", "variableName", "name"),
                item("VTextField", "inputLabel", "Phone", "variableName", "phone")));

        try (XWPFDocument doc = render(template(
                item("FormTable", "width", 12, "inputLabel", "Contacts",
                        "variableName", "contacts", "inputOptions", options)))) {
            String text = allText(doc);
            assertThat(text).contains("Name").contains("Phone");
            assertThat(text).contains("{{contacts;startsec}}");
            assertThat(text).contains("{{contacts.name}}");
            assertThat(text).contains("{{contacts.phone}}");
            assertThat(text).contains("{{contacts;endsec}}");
        }
    }

    @Test
    void formTableDataUsesDataTemplateForRowFields() throws Exception {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("dataTemplate", List.of(
                item("VTextField", "inputLabel", "Qty", "variableName", "qty"),
                item("FormDate", "inputLabel", "When", "variableName", "when")));

        try (XWPFDocument doc = render(template(
                item("FormTableData", "width", 12, "variableName", "lines", "inputOptions", options)))) {
            String text = allText(doc);
            assertThat(text).contains("{{lines;startsec}}");
            assertThat(text).contains("{{lines.qty}}");
            // row field hook attributes are preserved on the per-row placeholder
            assertThat(text).contains("{{lines.when;date}}");
            assertThat(text).contains("{{lines;endsec}}");
        }
    }

    // ---------- nesting ----------

    @Test
    void documentFormRecursesResolvedChildTemplate() throws Exception {
        DocumentTemplateJsonRenderer withResolver = new DocumentTemplateJsonRenderer(code ->
                "address-template".equals(code)
                        ? Optional.of(List.of(
                            item("VTextField", "width", 12, "inputLabel", "City", "variableName", "city")))
                        : Optional.empty(), JSON);

        try (XWPFDocument doc = new XWPFDocument()) {
            withResolver.render(doc, template(
                    item("DocumentForm", "inputOptions", "address-template")));
            assertThat(allText(doc)).contains("City").contains("{{city}}");
        }
    }

    @Test
    void documentFormRendersNothingWhenUnresolved() throws Exception {
        try (XWPFDocument doc = render(template(
                item("DocumentForm", "inputOptions", "missing-template")))) {
            assertThat(allText(doc)).doesNotContain("{{");
        }
    }

    // ---------- input forms ----------

    @Test
    void acceptsJsonStringInputEquivalentToList() throws Exception {
        String json = "[{\"inputType\":\"VTextField\",\"width\":6,"
                + "\"inputLabel\":\"Full Name\",\"variableName\":\"fullName\"}]";
        try (XWPFDocument doc = render(json)) {
            assertThat(allText(doc)).contains("Full Name").contains("{{fullName}}");
        }
    }
}
