package org.rama.service.document.template;

import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.rama.entity.asset.AssetFile;
import org.rama.service.StorageProvider;
import org.rama.service.document.template.docx.DocxTemplateHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class DocxTemplatePreprocessor {
    private static final Logger log = LoggerFactory.getLogger(DocxTemplatePreprocessor.class);
    private final StorageProvider storageService;
    private final String placeholderPattern;

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String CACHE_BUCKET = "docx-template-cache$";

    public DocxTemplatePreprocessor(StorageProvider storageService, String placeholderPattern) {
        this.storageService = storageService;
        this.placeholderPattern = placeholderPattern;
    }

    public InputStream preprocess(AssetFile assetFile) throws Exception {
        try {
            if (storageService.rawExists(CACHE_BUCKET, assetFile.getMd5hash())) {
                return new ByteArrayInputStream(storageService.rawRetrieve(CACHE_BUCKET, assetFile.getMd5hash()));
            }
        } catch (Exception ignore) {}

        return new ByteArrayInputStream(preprocess(storageService.retrieve(assetFile.getId()).getContentAsByteArray(), assetFile.getMd5hash()));
    }

    public InputStream preprocess(InputStream inputStream) throws Exception {
        byte[] inputBytes = inputStream.readAllBytes();
        String hash = storageService.calculateMD5hash(inputBytes);
        try {
            if (storageService.rawExists(CACHE_BUCKET, hash)) {
                return new ByteArrayInputStream(storageService.rawRetrieve(CACHE_BUCKET, hash));
            }
        } catch (Exception ignore) {}

        return new ByteArrayInputStream(preprocess(inputBytes, hash));
    }

    public byte[] preprocess(byte[] originalContent, String hash) {
        try {
            // A helper method to create a new XWPFDocument from the byte array
            Supplier<XWPFDocument> resetDocument = () -> {
                try {
                    return new XWPFDocument(new ByteArrayInputStream(originalContent));
                } catch (IOException e) {
                    throw new RuntimeException("Error resetting document", e);
                }
            };

            try (XWPFDocument document = resetDocument.get()) {
                // Replace placeholders in main body
                processControlToPlaceholder(document, document.getDocument().getBody().newCursor());
                cleanupBody(document);

                // Replace placeholders in headers
                for (XWPFHeader header : document.getHeaderList()) {
                    processControlToPlaceholder(header, header._getHdrFtr().newCursor());
                    cleanupBody(header);
                }

                // Replace placeholders in footers
                for (XWPFFooter footer : document.getFooterList()) {
                    processControlToPlaceholder(footer, footer._getHdrFtr().newCursor());
                    cleanupBody(footer);
                }

                try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                    document.write(outputStream);

                    byte[] processedByte = outputStream.toByteArray();

                    storageService.rawStore(processedByte, CACHE_BUCKET, hash);

                    return processedByte;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void processControlToPlaceholder(IBody doc,XmlCursor c) {
        replaceControlInBlock(doc,c);

        // 2) Run-level SDTs in top-level paragraphs
        for (XWPFParagraph p : doc.getParagraphs()) {
            processTextBox(doc,p);
            replaceControlInParagraph(p);
        }

        // 3) SDTs inside tables (both block-level and run-level), recursively
        for (XWPFTable t : doc.getTables()) {
            processTable(doc,t);
        }
    }

    private void processTable(IBody doc,XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                processCell(doc,cell);
            }
        }
    }

    private  void processCell(IBody doc,XWPFTableCell cell) {
        for (XWPFParagraph p : cell.getParagraphs()) {
            processTextBox(doc,p);
            replaceControlInParagraph(p);
        }

        try (XmlCursor c = cell.getCTTc().newCursor()) {
            replaceControlInBlock(cell,c);
        }

        for (XWPFTable nested : cell.getTables()) {
            processTable(doc,nested);
        }
    }

    private void processTextBox(IBody document, XWPFParagraph paragraph) {
        for (XWPFRun run : paragraph.getRuns()) {
            Map<String,List<XmlObject>> nodes = DocxTemplateHelper.extractTextBox(run);

            // STEP 2: PROCESS PARAGRAPHS
            for (XmlObject node : nodes.getOrDefault("paragraph", new ArrayList<>())) {
                try {
                    // Parse to get the correct CTP type (avoiding ClassCastException)
                    CTP ctp = CTP.Factory.parse(node.xmlText());
                    XWPFParagraph p = new XWPFParagraph(ctp, document);

                    // Logic and Recursion
                    processTextBox(document, p);

                    cleanupParagraph(p);
                    replaceControlInParagraph(p);

                    // Write modified XML back to the document
                    node.set(ctp);
                } catch (Exception e) {
                    log.error("Failed to process paragraph in run: {}", e.getMessage());
                }
            }

            // STEP 3: PROCESS TABLES
            for (XmlObject node : nodes.getOrDefault("table", new ArrayList<>())) {
                try {
                    CTTbl ctTbl = CTTbl.Factory.parse(node.xmlText());
                    XWPFTable table = new XWPFTable(ctTbl, document);

                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cleanupBody(cell);
                        }
                    }

                    processTable(document, table);

                    // Write modified XML back to the document
                    node.set(ctTbl);
                } catch (Exception e) {
                    log.error("Failed to process table in run: {}", e.getMessage());
                }
            }
        }
    }

    private void replaceControlInBlock(IBody doc, XmlCursor c) {
        if (!c.toFirstChild()) return;
        do {
            if (isStartOf(c, "sdt")) {
                CTPPr ppr = findFirstInnerParagraphPPr(c);
                CTRPr rpr = findFirstInnerRunRPr(c);
                CTPPr pprCopy = (ppr != null) ? (CTPPr) ppr.copy() : null;
                CTRPr rprCopy = (rpr != null) ? (CTRPr) rpr.copy() : null;
                String placeholder = readControlValue(c);
                if (placeholder == null) continue;

                try (XmlCursor before = c.newCursor()) {
                    XWPFParagraph para = doc.insertNewParagraph(before);
                    if (pprCopy != null) para.getCTP().setPPr(pprCopy);
                    XWPFRun run = para.createRun();
                    if (rprCopy != null) run.getCTR().setRPr(rprCopy);

                    CTText t = run.getCTR().addNewT();
                    t.setStringValue(placeholder);
                    t.setSpace(SpaceAttribute.Space.PRESERVE);
                }

                c.removeXml();
            }
        } while (c.toNextSibling());
    }

    private void replaceControlInParagraph(XWPFParagraph p) {
        try (XmlCursor cur = p.getCTP().newCursor()) {
            if (!cur.toFirstChild()) return;
            do {
                if (isStartOf(cur, "sdt")) {
                    int pos = runLikeIndexInParagraph(p, cur);

                    CTRPr rpr = findFirstInnerRunRPr(cur);
                    CTRPr rprCopy = (rpr != null) ? (CTRPr) rpr.copy() : null;
                    String placeholder = readControlValue(cur);
                    if (placeholder == null) continue;

                    cur.removeXml();

                    XWPFRun run = p.insertNewRun(pos);
                    if (rprCopy != null) run.getCTR().setRPr(rprCopy);

                    CTText t = run.getCTR().addNewT();
                    t.setStringValue(placeholder);
                    t.setSpace(SpaceAttribute.Space.PRESERVE);
                }
            } while (cur.toNextSibling());
        }
    }

    private void cleanupParagraph(XWPFParagraph paragraph) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.size() < 2) return;

        for (int i = 0; i < runs.size(); i++) {
            XWPFRun run = runs.get(i);
            String runText = run.getText(0);

            if (runText==null) continue;

            if (runText.endsWith("{") && !runText.endsWith("{{")) {
                XWPFRun nextRun = runs.get(i+1);
                String nextRunText = nextRun.getText(0);
                if (nextRunText.startsWith("{") && !nextRunText.startsWith("{{")) {
                    String newRunText = runText + nextRunText;
                    paragraph.removeRun(i + 1);
                    run.setText(newRunText, 0);
                    i--;
                    continue;
                }
            }

            if (StringUtils.countOccurrencesOf(runText,"{{")!=StringUtils.countOccurrencesOf(runText,"}}")) {
                int endRunIndex = -1;
                for (int j = i + 1; j < runs.size(); j++) {
                    XWPFRun nextRun = runs.get(j);
                    String nextRunText = nextRun.getText(0);
                    if (nextRunText!=null && (nextRunText.contains("}}") || nextRunText.startsWith("}"))) {
                        endRunIndex = j;
                        break;
                    }
                }
                if (endRunIndex != -1) {
                    StringBuilder newRunText = new StringBuilder(runText);
                    for (int j = i + 1; j <= endRunIndex; j++) {
                        XWPFRun nextRun = runs.get(i + 1);
                        newRunText.append(nextRun.getText(0));

                        DocxTemplateHelper.mergeRunStyle(run,nextRun);

                        paragraph.removeRun(i + 1);
                    }

                    run.setText(newRunText.toString(), 0);

                    i--;
                }
            }
        }
    }

    private void cleanupBody(IBody documentBody) {
        for (XWPFParagraph paragraph : documentBody.getParagraphs()) {
            cleanupParagraph(paragraph);
        }

        for (XWPFTable table : documentBody.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    cleanupBody(cell);
                }
            }
        }
    }

    private int runLikeIndexInParagraph(XWPFParagraph p, XmlCursor sdtHere) {
        int idx = 0;
        try (XmlCursor k = p.getCTP().newCursor()) {
            if (!k.toFirstChild()) return 0;
            do {
                if (k.isAtSamePositionAs(sdtHere)) {
                    return idx;
                }
                if (isStartOf(k, "r") || isStartOf(k, "sdt")) {
                    idx++;
                }
            } while (k.toNextSibling());
        }
        return idx;
    }

    private boolean isStartOf(XmlCursor c, String localName) {
        return c.currentTokenType() == XmlCursor.TokenType.START
                && c.getName() != null
                && W_NS.equals(c.getName().getNamespaceURI())
                && localName.equals(c.getName().getLocalPart());
    }

    private String readControlValue(XmlCursor onSdt) {
        try (XmlCursor q = onSdt.newCursor()) {
            if (!q.toFirstChild()) return null;
            do {
                if (isStartOf(q, "sdtPr")) {
                    try (XmlCursor t = q.newCursor()) {
                        if (!t.toFirstChild()) return null;
                        Pattern pattern = Pattern.compile(placeholderPattern);
                        String tag = null;
                        String alias = null;
                        do {
                            if (isStartOf(t, "tag")) {
                                tag = t.getAttributeText(new javax.xml.namespace.QName(W_NS, "val"));
                            }
                            if (isStartOf(t, "alias")) {
                                alias = t.getAttributeText(new javax.xml.namespace.QName(W_NS, "val"));
                            }
                        } while (t.toNextSibling());
                        if (tag != null && pattern.matcher(tag).matches()) return tag;
                        if (alias != null && pattern.matcher(alias).matches()) return alias;
                    }
                    return null;
                }
            } while (q.toNextSibling());
            return null;
        }
    }

    private XmlObject findFirstInnerObject(XmlCursor onSdt, String firstNode, String secondNode) {
        try (XmlCursor q = onSdt.newCursor()) {
            if (!q.toFirstChild()) return null;
            boolean atContent = false;
            do {
                if (isStartOf(q, "sdtContent")) { atContent = true; break; }
            } while (q.toNextSibling());
            if (!atContent) return null;

            try (XmlCursor d = q.newCursor()) {
                if (!d.toFirstChild()) return null;
                while (true) {
                    if (isStartOf(d, firstNode)) {
                        try (XmlCursor pp = d.newCursor()) {
                            if (pp.toFirstChild()) {
                                do {
                                    if (isStartOf(pp, secondNode)) {
                                        return pp.getObject();
                                    }
                                } while (pp.toNextSibling());
                            }
                        }
                    }
                    // DFS step
                    if (d.toFirstChild()) continue;
                    while (!d.toNextSibling()) {
                        if (!d.toParent()) return null;
                        if (d.isAtSamePositionAs(q)) return null; // back at sdtContent root
                    }
                }
            }
        }
    }

    private CTPPr findFirstInnerParagraphPPr(XmlCursor onSdt) {
        XmlObject xo = findFirstInnerObject(onSdt,"p","pPr");
        return (xo instanceof CTPPr) ? (CTPPr)xo : null;
    }

    private CTRPr findFirstInnerRunRPr(XmlCursor onSdt) {
        XmlObject xo = findFirstInnerObject(onSdt,"r","rPr");
        return (xo instanceof CTRPr) ? (CTRPr)xo : null;
    }
}
