package org.rama.service.document.template.json;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders document-template JSON (a {@code DocumentTemplateItem} array, as produced by
 * {@code rama-modules}' template builder) into an {@link XWPFDocument} body.
 *
 * <p>It emits field <b>labels</b> and {@code {{placeholder}}}s carrying the right hook
 * attributes ({@code ;date}, {@code ;master}, {@code ;checkbox}, …) — it does not read a data
 * object or run hooks. The produced body is the same a human would hand-author, so everything
 * downstream (placeholder fill, hooks, section expansion, header/footer merge, PDF conversion)
 * reuses the existing print pipeline unchanged, and the body depends only on the template JSON
 * (hence cacheable per template).
 *
 * <p>Layout mirrors Vuetify's {@code v-row}/{@code v-col} grid: fields are packed into rows by
 * their {@code width} (1–12, default 12) and each row is emitted as a borderless table.
 */
public class DocumentTemplateJsonRenderer {

    private static final Logger log = LoggerFactory.getLogger(DocumentTemplateJsonRenderer.class);

    /** Approx. usable A4 width in twips (DXA) between default margins. */
    private static final int GRID_TOTAL_DXA = 9000;
    private static final int GRID_COLUMNS = 12;

    private final TemplateJsonResolver templateJsonResolver;
    private final JsonMapper objectMapper;

    public DocumentTemplateJsonRenderer(TemplateJsonResolver templateJsonResolver, JsonMapper objectMapper) {
        this.templateJsonResolver = templateJsonResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Appends the rendered body content into {@code doc}.
     *
     * @param templateJson the template item array ({@code List<Map<String,Object>>}) or its JSON string
     */
    public void render(XWPFDocument doc, Object templateJson) {
        renderItems(doc, coerceItems(templateJson));
    }

    // ---------- item-array walk + grid packing ----------

    private void renderItems(XWPFDocument doc, List<Map<String, Object>> items) {
        List<Map<String, Object>> rowBuffer = new java.util.ArrayList<>();
        int widthSum = 0;

        for (Map<String, Object> item : items) {
            String type = asString(item.get("inputType"));

            if ("Separator".equals(type)) {
                widthSum = flush(doc, rowBuffer);
                continue;
            }
            if (isBlock(type)) {
                widthSum = flush(doc, rowBuffer);
                renderBlock(doc, type, item);
                continue;
            }

            int width = width(item);
            if (!rowBuffer.isEmpty() && widthSum + width > GRID_COLUMNS) {
                widthSum = flush(doc, rowBuffer);
            }
            rowBuffer.add(item);
            widthSum += width;
        }
        flush(doc, rowBuffer);
    }

    /** Emits the buffered fields as one borderless grid row, clears the buffer, returns 0. */
    private int flush(XWPFDocument doc, List<Map<String, Object>> rowBuffer) {
        if (rowBuffer.isEmpty()) return 0;

        XWPFTable table = doc.createTable(1, rowBuffer.size());
        removeBorders(table);
        setTableWidth(table);

        for (int c = 0; c < rowBuffer.size(); c++) {
            Map<String, Object> item = rowBuffer.get(c);
            XWPFTableCell cell = table.getRow(0).getCell(c);
            setCellWidth(cell, GRID_TOTAL_DXA * width(item) / GRID_COLUMNS);
            fillFieldCell(cell, item);
        }

        doc.createParagraph(); // spacer keeps adjacent tables from merging in Word
        rowBuffer.clear();
        return 0;
    }

    private boolean isBlock(String type) {
        return "Header".equals(type)
                || "FormTable".equals(type)
                || "FormTableData".equals(type)
                || "DocumentForm".equals(type)
                || "CustomCode".equals(type);
    }

    private void renderBlock(XWPFDocument doc, String type, Map<String, Object> item) {
        switch (type) {
            case "Header" -> renderHeader(doc, item);
            case "FormTable", "FormTableData" -> renderTable(doc, item);
            case "DocumentForm" -> renderDocumentForm(doc, item);
            case "CustomCode" -> renderCustomCode(doc, item);
            default -> { /* unreachable */ }
        }
    }

    // ---------- blocks ----------

    private void renderHeader(XWPFDocument doc, Map<String, Object> item) {
        XWPFParagraph paragraph = doc.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(label(item));
        run.setBold(true);
        run.setFontSize(14);
    }

    private void renderCustomCode(XWPFDocument doc, Map<String, Object> item) {
        XWPFParagraph paragraph = doc.createParagraph();
        paragraph.createRun().setText(asString(item.get("inputCustomCode")));
    }

    private void renderDocumentForm(XWPFDocument doc, Map<String, Object> item) {
        String code = asString(item.get("inputOptions"));
        if (code.isEmpty()) return;
        Optional<List<Map<String, Object>>> resolved;
        try {
            resolved = templateJsonResolver.resolve(code);
        } catch (Exception e) {
            log.warn("DocumentForm '{}' resolution failed; rendering nothing", code, e);
            return;
        }
        resolved.ifPresent(child -> renderItems(doc, child));
    }

    private void renderTable(XWPFDocument doc, Map<String, Object> item) {
        Map<String, Object> options = asMap(item.get("inputOptions"));
        String var = sanitize(asString(item.get("variableName")));
        List<Map<String, Object>> rowFields = rowFields(options);
        if (rowFields.isEmpty() || var.isEmpty()) return;

        List<Map<String, Object>> headers = asMapList(options.get("headers"));
        int n = rowFields.size();

        XWPFTable table = doc.createTable(2, n); // bordered (default) data table

        for (int c = 0; c < n; c++) {
            Map<String, Object> field = rowFields.get(c);
            setCellText(table.getRow(0).getCell(c), headerTitle(headers, field), true);

            String fieldVar = sanitize(asString(field.get("variableName")));
            String placeholder = placeholder(var + "." + fieldVar, asString(field.get("inputType")), field);

            XWPFTableCell cell = table.getRow(1).getCell(c);
            if (c == 0) {
                setCellText(cell, "{{" + var + ";startsec}}", false);
                cell.addParagraph().createRun().setText(placeholder);
            } else {
                setCellText(cell, placeholder, false);
            }
            if (c == n - 1) {
                cell.addParagraph().createRun().setText("{{" + var + ";endsec}}");
            }
        }

        doc.createParagraph();
    }

    // ---------- field cell ----------

    private void fillFieldCell(XWPFTableCell cell, Map<String, Object> item) {
        XWPFRun labelRun = cell.getParagraphArray(0).createRun();
        labelRun.setText(label(item));
        labelRun.setBold(true);

        String var = sanitize(asString(item.get("variableName")));
        if (var.isEmpty()) return;
        cell.addParagraph().createRun()
                .setText(placeholder(var, asString(item.get("inputType")), item));
    }

    // ---------- placeholders + hook attributes ----------

    private String placeholder(String key, String type, Map<String, Object> item) {
        // FormFile may hold a non-image attachment (e.g. accept="application/pdf"):
        // render its file name(s) instead of trying to embed it as a picture.
        if ("FormFile".equals(type) && !isImageAccept(item)) {
            String fileNameHook = isMultiple(item) ? ";join=originalFileName" : "";
            String fileNameKey = isMultiple(item) ? key : key + ".originalFileName";
            return "{{" + fileNameKey + fileNameHook + printConfigSuffix(item) + "}}";
        }
        return "{{" + key + hookSuffix(type, item) + printConfigSuffix(item) + "}}";
    }

    private String hookSuffix(String type, Map<String, Object> item) {
        return switch (type) {
            case "FormDate" -> ";date";
            case "FormTime" -> ";time";
            case "FormDateTime" -> ";datetime";
            case "MasterAutocomplete" -> ";master;groupKey=" + asString(item.get("inputOptions"));
            case "VCheckbox", "VSwitch" -> ";checkbox";
            case "FormCheckboxGroup" -> ";join";
            case "FormSignPad", "FormFile" -> ";image";
            default -> "";
        };
    }

    /**
     * Appends author-supplied docx hook attributes from the item's {@code printConfig} — the
     * print-side escape hatch for config the Vue form template can't imply (image {@code width},
     * date {@code format}, {@code prefix}/{@code suffix}, forcing {@code qrcode}/{@code barcode},
     * …). Accepts an object map ({@code {"width":2}} → {@code ;width=2}; boolean/blank values →
     * bare flags like {@code ;qrcode}) or a raw attribute string ({@code "format=dd/MM/yyyy"}).
     */
    private String printConfigSuffix(Map<String, Object> item) {
        Object printConfig = item.get("printConfig");
        if (printConfig instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String attr = asString(entry.getKey()).trim();
                if (attr.isEmpty()) continue;
                Object value = entry.getValue();
                if (value == null || Boolean.TRUE.equals(value) || asString(value).isEmpty()) {
                    sb.append(';').append(attr);
                } else {
                    sb.append(';').append(attr).append('=').append(asString(value));
                }
            }
            return sb.toString();
        }
        if (printConfig instanceof String raw && !raw.isBlank()) {
            return ";" + raw.trim().replaceAll("^;+", "");
        }
        return "";
    }

    /** True when the FormFile accepts images, or declares no {@code accept} at all. */
    private boolean isImageAccept(Map<String, Object> item) {
        String accept = inputAttribute(item, "accept");
        return accept.isEmpty() || accept.toLowerCase().contains("image");
    }

    private boolean isMultiple(Map<String, Object> item) {
        return asString(item.get("inputAttributes")).matches("(?s).*\\bmultiple\\b.*");
    }

    /** Extracts a quoted/bare attribute value from the raw {@code inputAttributes} string. */
    private String inputAttribute(Map<String, Object> item, String name) {
        String attributes = asString(item.get("inputAttributes"));
        Matcher matcher = Pattern.compile(name + "\\s*=\\s*\"([^\"]*)\"").matcher(attributes);
        return matcher.find() ? matcher.group(1) : "";
    }

    // ---------- table helpers ----------

    private List<Map<String, Object>> rowFields(Map<String, Object> options) {
        Object template = options.containsKey("formTemplate")
                ? options.get("formTemplate")
                : options.get("dataTemplate");
        return coerceItems(template);
    }

    private String headerTitle(List<Map<String, Object>> headers, Map<String, Object> field) {
        String fieldVar = asString(field.get("variableName"));
        for (Map<String, Object> header : headers) {
            if (fieldVar.equals(asString(header.get("key")))) {
                String title = asString(header.get("title"));
                if (!title.isEmpty()) return title;
            }
        }
        String label = asString(field.get("inputLabel"));
        return label.isEmpty() ? fieldVar : label;
    }

    // ---------- POI low-level helpers ----------

    private void setCellText(XWPFTableCell cell, String text, boolean bold) {
        XWPFParagraph paragraph = cell.getParagraphArray(0);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
    }

    private void removeBorders(XWPFTable table) {
        table.setTopBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "auto");
        table.setBottomBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "auto");
        table.setLeftBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "auto");
        table.setRightBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "auto");
        table.setInsideHBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "auto");
        table.setInsideVBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "auto");
    }

    private void setTableWidth(XWPFTable table) {
        CTTblPr tblPr = table.getCTTbl().getTblPr() != null
                ? table.getCTTbl().getTblPr() : table.getCTTbl().addNewTblPr();
        CTTblWidth tblWidth = tblPr.isSetTblW() ? tblPr.getTblW() : tblPr.addNewTblW();
        tblWidth.setType(STTblWidth.DXA);
        tblWidth.setW(BigInteger.valueOf(GRID_TOTAL_DXA));
        (tblPr.isSetTblLayout() ? tblPr.getTblLayout() : tblPr.addNewTblLayout())
                .setType(STTblLayoutType.FIXED);
    }

    private void setCellWidth(XWPFTableCell cell, int dxa) {
        CTTc ctTc = cell.getCTTc();
        CTTcPr tcPr = ctTc.isSetTcPr() ? ctTc.getTcPr() : ctTc.addNewTcPr();
        CTTblWidth width = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
        width.setType(STTblWidth.DXA);
        width.setW(BigInteger.valueOf(dxa));
    }

    // ---------- value coercion ----------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> coerceItems(Object templateJson) {
        if (templateJson == null) return List.of();
        if (templateJson instanceof String json) {
            String trimmed = json.trim();
            if (trimmed.isEmpty()) return List.of();
            try {
                return objectMapper.readValue(trimmed, new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                log.warn("Template JSON string could not be parsed; rendering nothing", e);
                return List.of();
            }
        }
        if (templateJson instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        if (value instanceof String json && !json.isBlank()) {
            try {
                return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.debug("inputOptions string is not a JSON object: {}", e.getMessage());
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (value instanceof List<?> list) return (List<Map<String, Object>>) list;
        return List.of();
    }

    private String label(Map<String, Object> item) {
        String label = asString(item.get("inputLabel"));
        return !label.isEmpty() ? label : asString(item.get("variableName"));
    }

    private int width(Map<String, Object> item) {
        Object width = item.get("width");
        int value = GRID_COLUMNS;
        if (width instanceof Number number) {
            value = number.intValue();
        } else if (width instanceof String s && !s.isBlank()) {
            try {
                value = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                value = GRID_COLUMNS;
            }
        }
        return Math.min(GRID_COLUMNS, Math.max(1, value));
    }

    private String sanitize(String variableName) {
        return variableName == null ? "" : variableName.replaceAll("[^\\w$]", "");
    }

    private String asString(Object value) {
        return value == null ? "" : value.toString();
    }
}
