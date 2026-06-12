package org.rama.service.document.template;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.rama.service.StorageProvider;
import org.rama.service.document.BarcodeService;
import org.rama.service.document.PdfService;
import org.rama.service.document.replacement.ReplacementHooks;
import org.rama.service.document.template.docx.HeaderFooterMerger;
import org.rama.service.document.template.docx.ReplacePlaceholder;
import org.rama.service.document.template.docx.ReplaceSection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocxTemplateProcessorBaseTemplateTest {

    private final PdfService pdfService = mock(PdfService.class);
    private final StorageProvider storage = mock(StorageProvider.class);
    private final BarcodeService barcode = mock(BarcodeService.class);

    private DocxTemplateProcessor processor(BaseTemplateResolver resolver) {
        ReplacementProcessor rp = new ReplacementProcessor(
                new ReplacementHooks(Map.of(), Map.of()), storage);
        ReplacePlaceholder replace = new ReplacePlaceholder(rp, barcode);
        ReplaceSection section = new ReplaceSection(
                "\\{\\{[^\\{\\}]*startsec[^\\{\\}]*\\}\\}",
                "\\{\\{[\\s]*placeholder[^\\{\\}]*endsec[^\\{\\}]*\\}\\}",
                "\\{\\{[\\s]*placeholder[^\\{\\}]*\\}\\}",
                replace);
        return new DocxTemplateProcessor(
                "\\{\\{(.+?)\\}\\}", "RepeatAttribute", "MaximumPages", "BaseTemplate",
                barcode, pdfService, rp, replace, section,
                new HeaderFooterMerger(), resolver);
    }

    private byte[] mainDocx() throws Exception {
        XWPFDocument d = new XWPFDocument();
        d.createParagraph().createRun().setText("Patient: {{patientName}}");
        d.getProperties().getCustomProperties().addProperty("BaseTemplate", "central");
        XWPFHeader h = d.createHeaderFooterPolicy().createHeader(XWPFHeaderFooterPolicy.DEFAULT);
        h.createParagraph().createRun().setText("ORIGINAL HEADER");
        return bytes(d);
    }

    private InputStream baseDocx() throws Exception {
        XWPFDocument d = new XWPFDocument();
        d.createParagraph().createRun().setText("base body discarded");
        XWPFHeader h = d.createHeaderFooterPolicy().createHeader(XWPFHeaderFooterPolicy.DEFAULT);
        h.createParagraph().createRun().setText("Hospital: {{hospitalName}}");
        return new ByteArrayInputStream(bytes(d));
    }

    private static byte[] bytes(XWPFDocument d) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { d.write(out); return out.toByteArray(); }
    }

    private InputStream baseDocxUnchecked() {
        try { return baseDocx(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void processTemplate_withBaseTemplate_mergesBaseHeaderAndOriginalBody() throws Exception {
        when(pdfService.convertDocxToPdfBytesBlocking(any())).thenReturn("PDF".getBytes());
        BaseTemplateResolver resolver = (code, data) ->
                code.equals("central") ? Optional.of(baseDocxUnchecked()) : Optional.empty();

        Map<String, Object> data = Map.of("patientName", "ALICE", "hospitalName", "RAMA");
        processor(resolver).processTemplate(new ByteArrayInputStream(mainDocx()), data);

        ArgumentCaptor<byte[]> docx = ArgumentCaptor.forClass(byte[].class);
        verify(pdfService).convertDocxToPdfBytesBlocking(docx.capture());
        try (XWPFDocument rendered = new XWPFDocument(new ByteArrayInputStream(docx.getValue()))) {
            StringBuilder body = new StringBuilder();
            for (XWPFParagraph p : rendered.getParagraphs()) body.append(p.getText());
            StringBuilder header = new StringBuilder();
            for (XWPFHeader h : rendered.getHeaderList()) header.append(h.getText());
            assertThat(body.toString()).contains("Patient: ALICE");
            assertThat(header.toString()).contains("Hospital: RAMA").doesNotContain("ORIGINAL HEADER");
        }
    }

    @Test
    void processTemplate_whenResolverReturnsEmpty_fallsBackToNormalProcessing() throws Exception {
        when(pdfService.convertDocxToPdfBytesBlocking(any())).thenReturn("PDF".getBytes());
        BaseTemplateResolver empty = (code, data) -> Optional.empty();

        byte[] result = processor(empty)
                .processTemplate(new ByteArrayInputStream(mainDocx()), Map.of("patientName", "BOB"));

        assertThat(result).isEqualTo("PDF".getBytes());
        ArgumentCaptor<byte[]> docx = ArgumentCaptor.forClass(byte[].class);
        verify(pdfService).convertDocxToPdfBytesBlocking(docx.capture());
        try (XWPFDocument rendered = new XWPFDocument(new ByteArrayInputStream(docx.getValue()))) {
            StringBuilder header = new StringBuilder();
            for (XWPFHeader h : rendered.getHeaderList()) header.append(h.getText());
            assertThat(header.toString()).contains("ORIGINAL HEADER");
        }
    }
}
