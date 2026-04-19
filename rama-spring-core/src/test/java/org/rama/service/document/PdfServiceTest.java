package org.rama.service.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.awt.*;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PdfServiceTest {

    @Mock
    private WebClient.Builder webClientBuilder;

    private PdfService pdfService;

    @BeforeEach
    void setUp() {
        pdfService = new PdfService(webClientBuilder, "http://localhost:3000");
    }

    private byte[] createBlankPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void addWatermarkBytesBlocking_withColor_shouldProduceValidPdf() throws Exception {
        byte[] blank = createBlankPdf();

        byte[] result = pdfService.addWatermarkBytesBlocking(blank, "DRAFT", 36f, Color.RED);

        assertThat(result).isNotNull();
        try (PDDocument doc = Loader.loadPDF(result)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void addWatermarkBytesBlocking_withHighRgbColor_shouldNotCorruptPdf() throws Exception {
        byte[] blank = createBlankPdf();

        byte[] result = pdfService.addWatermarkBytesBlocking(blank, "CONFIDENTIAL", 36f, new Color(255, 0, 0));

        assertThat(result).isNotNull();
        try (PDDocument doc = Loader.loadPDF(result)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", "");
            assertThat(text).contains("CONFIDENTIAL");
        }
    }

    @Test
    void addWatermarkBytesBlocking_withNamedColor_shouldProduceValidPdf() throws Exception {
        byte[] blank = createBlankPdf();

        byte[] result = pdfService.addWatermarkBytesBlocking(blank, "SAMPLE", 36f, "red");

        assertThat(result).isNotNull();
        try (PDDocument doc = Loader.loadPDF(result)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void addWatermarkBytesBlocking_withDefaultColor_shouldProduceValidPdf() throws Exception {
        byte[] blank = createBlankPdf();

        byte[] result = pdfService.addWatermarkBytesBlocking(blank, "WATERMARK");

        assertThat(result).isNotNull();
        try (PDDocument doc = Loader.loadPDF(result)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", "");
            assertThat(text).contains("WATERMARK");
        }
    }

    @Test
    void addWatermarkBytesBlocking_withMultilineText_shouldProduceValidPdf() throws Exception {
        byte[] blank = createBlankPdf();

        byte[] result = pdfService.addWatermarkBytesBlocking(blank, "LINE ONE\nLINE TWO", 36f, Color.GRAY);

        assertThat(result).isNotNull();
        try (PDDocument doc = Loader.loadPDF(result)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void trimPdfBytesBlocking_shouldLimitPages() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(out);

            byte[] result = pdfService.trimPdfBytesBlocking(out.toByteArray(), 2);

            try (PDDocument trimmed = Loader.loadPDF(result)) {
                assertThat(trimmed.getNumberOfPages()).isEqualTo(2);
            }
        }
    }
}
