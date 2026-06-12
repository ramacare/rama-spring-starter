package org.rama.service.document.template.json;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class DocumentTemplateJsonRendererTest {

    /** Default renderer: DocumentForm resolves to nothing. */
    private final DocumentTemplateJsonRenderer renderer =
            new DocumentTemplateJsonRenderer(code -> Optional.empty());

    // ---------- helpers ----------

    private static Map<String, Object> item(String inputType, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inputType", inputType);
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

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
    void rendersTextFieldAsLabelPlusPlaceholder() {
        XWPFDocument doc = render(template(
                item("VTextField", "width", 6, "inputLabel", "Full Name", "variableName", "fullName")));

        assertThat(doc.getTables()).hasSize(1);
        String cell = doc.getTables().get(0).getRow(0).getCell(0).getText();
        assertThat(cell).contains("Full Name").contains("{{fullName}}");
    }

    @Test
    void packsTwoHalfWidthFieldsIntoOneRowWithTwoCells() {
        XWPFDocument doc = render(template(
                item("VTextField", "width", 6, "inputLabel", "First", "variableName", "first"),
                item("VTextField", "width", 6, "inputLabel", "Last", "variableName", "last")));

        assertThat(doc.getTables()).hasSize(1);
        assertThat(doc.getTables().get(0).getRow(0).getTableCells()).hasSize(2);
    }

    @Test
    void wrapsToNewRowWhenWidthExceedsTwelve() {
        XWPFDocument doc = render(template(
                item("VTextField", "width", 8, "inputLabel", "A", "variableName", "a"),
                item("VTextField", "width", 8, "inputLabel", "B", "variableName", "b")));

        assertThat(doc.getTables()).hasSize(2);
    }

    @Test
    void separatorForcesRowBreak() {
        XWPFDocument doc = render(template(
                item("VTextField", "width", 6, "inputLabel", "A", "variableName", "a"),
                item("Separator"),
                item("VTextField", "width", 6, "inputLabel", "B", "variableName", "b")));

        assertThat(doc.getTables()).hasSize(2);
        assertThat(doc.getTables().get(0).getRow(0).getTableCells()).hasSize(1);
        assertThat(doc.getTables().get(1).getRow(0).getTableCells()).hasSize(1);
    }

    @Test
    void rendersHeaderAsBoldBodyParagraphWithoutPlaceholder() {
        XWPFDocument doc = render(template(
                item("Header", "inputLabel", "Patient Profile")));

        assertThat(doc.getTables()).isEmpty();
        XWPFParagraph header = doc.getParagraphs().stream()
                .filter(p -> p.getText().contains("Patient Profile"))
                .findFirst().orElseThrow();
        assertThat(header.getRuns()).isNotEmpty();
        assertThat(header.getRuns().get(0).isBold()).isTrue();
        assertThat(header.getText()).doesNotContain("{{");
    }

    // ---------- hook-attribute placeholders ----------

    @Test
    void formDateEmitsDateHookAttribute() {
        XWPFDocument doc = render(template(
                item("FormDate", "width", 6, "inputLabel", "DOB", "variableName", "birthDate")));
        assertThat(allText(doc)).contains("{{birthDate;date}}");
    }

    @Test
    void formTimeAndDateTimeEmitTheirHooks() {
        assertThat(allText(render(template(
                item("FormTime", "width", 6, "variableName", "t")))))
                .contains("{{t;time}}");
        assertThat(allText(render(template(
                item("FormDateTime", "width", 6, "variableName", "ts")))))
                .contains("{{ts;datetime}}");
    }

    @Test
    void masterAutocompleteEmitsMasterHookWithGroupKey() {
        XWPFDocument doc = render(template(
                item("MasterAutocomplete", "width", 6, "inputLabel", "Country",
                        "variableName", "country", "inputOptions", "countries")));
        assertThat(allText(doc)).contains("{{country;master;groupKey=countries}}");
    }

    @Test
    void checkboxAndSwitchEmitCheckboxHook() {
        assertThat(allText(render(template(
                item("VCheckbox", "width", 6, "variableName", "agree")))))
                .contains("{{agree;checkbox}}");
        assertThat(allText(render(template(
                item("VSwitch", "width", 6, "variableName", "active")))))
                .contains("{{active;checkbox}}");
    }

    @Test
    void checkboxGroupEmitsJoinHook() {
        assertThat(allText(render(template(
                item("FormCheckboxGroup", "width", 6, "variableName", "tags")))))
                .contains("{{tags;join}}");
    }

    @Test
    void signPadAndFileEmitImageHook() {
        assertThat(allText(render(template(
                item("FormSignPad", "width", 6, "variableName", "signature")))))
                .contains("{{signature;image}}");
        assertThat(allText(render(template(
                item("FormFile", "width", 6, "variableName", "attachment")))))
                .contains("{{attachment;image}}");
    }

    @Test
    void unknownTypeFallsBackToPlainLabelAndPlaceholder() {
        XWPFDocument doc = render(template(
                item("VFancyThing", "width", 6, "inputLabel", "Fancy", "variableName", "x")));
        String text = allText(doc);
        assertThat(text).contains("Fancy").contains("{{x}}");
    }

    @Test
    void customCodeRendersRawFragmentAsPlainParagraph() {
        XWPFDocument doc = render(template(
                item("CustomCode", "inputCustomCode", "Signed by committee")));
        assertThat(allText(doc)).contains("Signed by committee");
        assertThat(doc.getTables()).isEmpty();
    }

    // ---------- tables ----------

    @Test
    void formTableEmitsHeaderRowAndSectionMarkers() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("title", "Contacts");
        options.put("headers", List.of(
                Map.of("title", "Name", "key", "name"),
                Map.of("title", "Phone", "key", "phone")));
        options.put("formTemplate", List.of(
                item("VTextField", "inputLabel", "Name", "variableName", "name"),
                item("VTextField", "inputLabel", "Phone", "variableName", "phone")));

        XWPFDocument doc = render(template(
                item("FormTable", "width", 12, "inputLabel", "Contacts",
                        "variableName", "contacts", "inputOptions", options)));

        String text = allText(doc);
        assertThat(text).contains("Name").contains("Phone");
        assertThat(text).contains("{{contacts;startsec}}");
        assertThat(text).contains("{{contacts.name}}");
        assertThat(text).contains("{{contacts.phone}}");
        assertThat(text).contains("{{contacts;endsec}}");
    }

    @Test
    void formTableDataUsesDataTemplateForRowFields() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("dataTemplate", List.of(
                item("VTextField", "inputLabel", "Qty", "variableName", "qty"),
                item("FormDate", "inputLabel", "When", "variableName", "when")));

        XWPFDocument doc = render(template(
                item("FormTableData", "width", 12, "variableName", "lines", "inputOptions", options)));

        String text = allText(doc);
        assertThat(text).contains("{{lines;startsec}}");
        assertThat(text).contains("{{lines.qty}}");
        // row field hook attributes are preserved on the per-row placeholder
        assertThat(text).contains("{{lines.when;date}}");
        assertThat(text).contains("{{lines;endsec}}");
    }

    // ---------- nesting ----------

    @Test
    void documentFormRecursesResolvedChildTemplate() {
        DocumentTemplateJsonRenderer withResolver = new DocumentTemplateJsonRenderer(code ->
                "address-template".equals(code)
                        ? Optional.of(List.of(
                            item("VTextField", "width", 12, "inputLabel", "City", "variableName", "city")))
                        : Optional.empty());

        XWPFDocument doc = new XWPFDocument();
        withResolver.render(doc, template(
                item("DocumentForm", "inputOptions", "address-template")));

        assertThat(allText(doc)).contains("City").contains("{{city}}");
    }

    @Test
    void documentFormRendersNothingWhenUnresolved() {
        XWPFDocument doc = render(template(
                item("DocumentForm", "inputOptions", "missing-template")));
        assertThat(allText(doc)).doesNotContain("{{");
    }

    // ---------- input forms ----------

    @Test
    void acceptsJsonStringInputEquivalentToList() {
        String json = "[{\"inputType\":\"VTextField\",\"width\":6,"
                + "\"inputLabel\":\"Full Name\",\"variableName\":\"fullName\"}]";
        XWPFDocument doc = render(json);
        assertThat(allText(doc)).contains("Full Name").contains("{{fullName}}");
    }
}
