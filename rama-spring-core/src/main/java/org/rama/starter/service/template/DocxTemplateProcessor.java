package org.rama.starter.service.template;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DocxTemplateProcessor implements TemplateProcessor {
    private final String placeholderPattern;
    private final PdfService pdfService;
    private final ReplacementProcessor replacementProcessor;

    public DocxTemplateProcessor(String placeholderPattern, PdfService pdfService, ReplacementProcessor replacementProcessor) {
        this.placeholderPattern = placeholderPattern;
        this.pdfService = pdfService;
        this.replacementProcessor = replacementProcessor;
    }

    @Override
    public byte[] processTemplate(InputStream templateInputStream, Map<String, Object> replacements) {
        try (XWPFDocument document = new XWPFDocument(templateInputStream); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Pattern pattern = Pattern.compile(placeholderPattern);
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                replaceInParagraph(paragraph, pattern, replacements);
            }
            document.write(outputStream);
            return pdfService.convertDocxToPdfBytesBlocking(outputStream.toByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to process template", ex);
        }
    }

    private void replaceInParagraph(XWPFParagraph paragraph, Pattern pattern, Map<String, Object> replacements) {
        for (XWPFRun run : paragraph.getRuns()) {
            String text = run.getText(0);
            if (text == null || text.isBlank()) {
                continue;
            }
            Matcher matcher = pattern.matcher(text);
            StringBuffer buffer = new StringBuffer();
            while (matcher.find()) {
                Map<String, String> attributes = new HashMap<>();
                String replacementKey = replacementProcessor.parsePlaceholder(matcher.group(), attributes);
                String replacement = replacementProcessor.processReplacement(replacementKey, replacements, attributes);
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(buffer);
            run.setText(buffer.toString(), 0);
        }
    }
}
