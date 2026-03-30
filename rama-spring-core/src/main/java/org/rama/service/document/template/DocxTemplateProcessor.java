package org.rama.service.document.template;

import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.rama.service.document.BarcodeService;
import org.rama.service.document.PdfService;
import org.rama.service.document.template.docx.DocxTemplateHelper;
import org.rama.service.document.template.docx.ReplacePlaceholder;
import org.rama.service.document.template.docx.ReplaceSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DocxTemplateProcessor implements TemplateProcessor {
    private static final Logger log = LoggerFactory.getLogger(DocxTemplateProcessor.class);
    private final String placeholderPattern;
    private final String repeatAttributeProperty;
    private final String maximumPagesProperty;

    private final BarcodeService barcodeService;
    private final PdfService pdfService;
    private final ReplacementProcessor replacementProcessor;

    private final ReplacePlaceholder replacePlaceholder;
    private final ReplaceSection replaceSection;

    public DocxTemplateProcessor(
            String placeholderPattern,
            String repeatAttributeProperty,
            String maximumPagesProperty,
            BarcodeService barcodeService,
            PdfService pdfService,
            ReplacementProcessor replacementProcessor,
            ReplacePlaceholder replacePlaceholder,
            ReplaceSection replaceSection
    ) {
        this.placeholderPattern = placeholderPattern;
        this.repeatAttributeProperty = repeatAttributeProperty;
        this.maximumPagesProperty = maximumPagesProperty;
        this.barcodeService = barcodeService;
        this.pdfService = pdfService;
        this.replacementProcessor = replacementProcessor;
        this.replacePlaceholder = replacePlaceholder;
        this.replaceSection = replaceSection;
    }

    @Override
    public byte[] processTemplate(InputStream inputStream, Map<String, Object> replacements) {
        IOUtils.setByteArrayMaxOverride(200_000_000);

        try (InputStream is = inputStream) {
            final byte[] originalContent = is.readAllBytes();

            final Supplier<XWPFDocument> resetDocument = () -> {
                try {
                    return new XWPFDocument(new ByteArrayInputStream(originalContent));
                } catch (IOException e) {
                    throw new RuntimeException("Error resetting document", e);
                }
            };

            try (XWPFDocument document = resetDocument.get()) {
                POIXMLProperties.CustomProperties customProperties = document.getProperties().getCustomProperties();

                int maximumPages = readMaximumPages(customProperties);

                // Repeat mode: produce multiple PDFs and merge (bytes-first)
                if (customProperties != null && customProperties.contains(repeatAttributeProperty) && customProperties.getProperty(repeatAttributeProperty).isSetLpwstr()) {
                    String repeatAttribute = customProperties.getProperty(repeatAttributeProperty).getLpwstr();
                    Object value = (replacements != null) ? replacements.get(repeatAttribute) : null;

                    if (value instanceof Collection<?> collection) {
                        List<byte[]> pdfs = new ArrayList<>(collection.size());

                        for (Object item : collection) {
                            replacements.put(repeatAttribute + "Item", item);
                            try (XWPFDocument doc2 = resetDocument.get()) {
                                pdfs.add(processDocumentToPdfBytes(doc2, replacements, maximumPages));
                            }
                        }
                        return pdfService.mergePdfBytesBlocking(pdfs);
                    }
                }

                return processDocumentToPdfBytes(document, replacements, maximumPages);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private int readMaximumPages(POIXMLProperties.CustomProperties customProperties) {
        if (customProperties == null) return 0;
        if (!customProperties.contains(maximumPagesProperty)) return 0;

        var p = customProperties.getProperty(maximumPagesProperty);
        try {
            if (p.isSetInt()) return p.getInt();
            if (p.isSetI1()) return p.getI1();
            if (p.isSetI2()) return p.getI2();
            if (p.isSetI4()) return p.getI4();
            if (p.isSetI8()) return (int) p.getI8();
            if (p.isSetR4()) return (int) p.getR4();
            if (p.isSetR8()) return (int) p.getR8();
        } catch (Exception ignore) {
        }
        return 0;
    }

    private byte[] processDocumentToPdfBytes(XWPFDocument document, Map<String, Object> replacements, int maximumPages) throws IOException {
        processDocument(document, replacements);

        byte[] docxBytes;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.write(out);
            docxBytes = out.toByteArray();
        }

        byte[] pdfBytes = pdfService.convertDocxToPdfBytesBlocking(docxBytes);
        if (maximumPages > 0) {
            return pdfService.trimPdfBytesBlocking(pdfBytes, maximumPages);
        }
        return pdfBytes;
    }

    private void processDocument(XWPFDocument document, Map<String, Object> replacements) {
        Pattern pattern = Pattern.compile(placeholderPattern);

        replacePatternInBody(document, pattern, replacements);

        for (XWPFHeader header : document.getHeaderList()) {
            replacePatternInBody(header, pattern, replacements);
        }

        for (XWPFFooter footer : document.getFooterList()) {
            replacePatternInBody(footer, pattern, replacements);
        }
    }

    private void replacePatternInBody(IBody documentBody, Pattern pattern, Map<String, Object> replacements) {
        for (XWPFParagraph paragraph : documentBody.getParagraphs()) {
            replacePatternInTextBox(documentBody, paragraph, pattern, replacements);
            replacePatternInParagraph(paragraph, pattern, replacements);
        }

        for (XWPFTable table : documentBody.getTables()) {
            replaceSection.expandSectionsRecursively(table, replacements);
            replacePatternInTable(documentBody, table, pattern, replacements);
        }
    }

    public void replacePatternInParagraph(XWPFParagraph paragraph, Pattern pattern, Map<String, Object> replacements) {
        Set<String> placeholders = new HashSet<>();

        String text = paragraph.getText();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) placeholders.add(matcher.group());

        for (String placeholder : placeholders) {
            Map<String, String> attributeData = new HashMap<>();
            String replacementKey = replacementProcessor.parsePlaceholder(placeholder, attributeData);

            boolean shouldProcess =
                    (replacements != null && replacements.containsKey(replacementKey))
                            || replacementKey.contains(".")
                            || replacementKey.contains("[")
                            || attributeData.containsKey("ifempty")
                            || attributeData.containsKey("else")
                            || attributeData.containsKey("checkbox");

            if (!shouldProcess) continue;

            String replacement = replacementProcessor.processReplacement(replacementKey, replacements, attributeData);

            if (attributeData.containsKey("qrcode")) {
                try {
                    double width = Double.parseDouble(attributeData.getOrDefault("width", "1"));
                    BufferedImage img = barcodeService.generateQRCode(
                            replacement,
                            (int) Math.ceil(width * 96),
                            (int) Math.ceil(width * 96)
                    );
                    replacePlaceholder.replacePlaceholderInParagraph(paragraph, placeholder, bufferedImageToPngBytes(img), width, 0);
                } catch (Exception e) {
                    log.error(e.getMessage());
                    replacePlaceholder.replacePlaceholderInParagraph(paragraph, placeholder, "");
                }

            } else if (attributeData.containsKey("barcode39") || attributeData.containsKey("barcode")) {
                try {
                    BufferedImage img = barcodeService.generateCode39(replacement);
                    replacePlaceholder.replacePlaceholderInParagraph(paragraph, placeholder, bufferedImageToPngBytes(img), 0, 0);
                } catch (Exception e) {
                    log.error(e.getMessage());
                    replacePlaceholder.replacePlaceholderInParagraph(paragraph, placeholder, "");
                }

            } else if (attributeData.containsKey("barcode128")) {
                try {
                    BufferedImage img = barcodeService.generateCode128(replacement);
                    replacePlaceholder.replacePlaceholderInParagraph(paragraph, placeholder, bufferedImageToPngBytes(img), 0, 0);
                } catch (Exception e) {
                    log.error(e.getMessage());
                    replacePlaceholder.replacePlaceholderInParagraph(paragraph, placeholder, "");
                }

            } else if (attributeData.containsKey("image")) {
                double width = Double.parseDouble(attributeData.getOrDefault("width", "0"));
                double height = Double.parseDouble(attributeData.getOrDefault("height", "0"));

                // bytes-first fast path
                Optional<byte[]> imageBytes = replacementProcessor.processBytes(replacementKey, replacements, attributeData);

                if (imageBytes.isPresent() && imageBytes.get().length > 0) {
                    replacePlaceholder.replacePlaceholderInParagraph(paragraph, placeholder, imageBytes.get(), width, height);
                } else {
                    replacePlaceholder.replacePlaceholderInParagraph(paragraph, placeholder, "");
                }

            } else if (attributeData.containsKey("html")) {
                replacePlaceholder.insertHtmlToParagraph(paragraph, placeholder, replacement);

            } else {
                replacePlaceholder.replacePlaceholderInParagraph(paragraph, placeholder, replacement);
            }
        }

        // image-in-shape replacement (also bytes-first in your updated ReplacePlaceholder)
        replacePlaceholder.replaceImageInParagraph(paragraph, pattern, replacements);
    }

    private void replacePatternInTable(IBody documentBody, XWPFTable table, Pattern pattern, Map<String, Object> replacements) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                    replacePatternInTextBox(documentBody, paragraph, pattern, replacements);
                    replacePatternInParagraph(paragraph, pattern, replacements);
                }
                for (XWPFTable nestedTable : cell.getTables()) {
                    replacePatternInTable(documentBody, nestedTable, pattern, replacements);
                }
            }
        }
    }

    private void replacePatternInTextBox(IBody documentBody, XWPFParagraph paragraph, Pattern pattern, Map<String, Object> replacements) {
        for (XWPFRun run : paragraph.getRuns()) {
            Map<String, List<XmlObject>> nodes = DocxTemplateHelper.extractTextBox(run);

            for (XmlObject node : nodes.getOrDefault("paragraph", List.of())) {
                try {
                    CTP ctp = CTP.Factory.parse(node.xmlText());
                    XWPFParagraph p = new XWPFParagraph(ctp, documentBody);

                    replacePatternInParagraph(p, pattern, replacements);

                    node.set(ctp);
                    replacePatternInTextBox(documentBody, p, pattern, replacements);
                } catch (Exception e) {
                    log.error("Failed to process paragraph node: {}", e.getMessage());
                }
            }

            for (XmlObject node : nodes.getOrDefault("table", List.of())) {
                try {
                    CTTbl ctTbl = CTTbl.Factory.parse(node.xmlText());
                    XWPFTable table = new XWPFTable(ctTbl, documentBody);

                    replaceSection.expandSectionsRecursively(table, replacements);
                    replacePatternInTable(documentBody, table, pattern, replacements);

                    node.set(ctTbl);
                } catch (Exception e) {
                    log.error("Failed to process table node: {}", e.getMessage());
                }
            }
        }
    }

    private static byte[] bufferedImageToPngBytes(BufferedImage img) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        }
    }
}
