package org.rama.service.document;

import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.awt.*;
import java.io.ByteArrayInputStream;
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

    private byte[] createPdf(int pageCount) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfDocument doc = new PdfDocument(new PdfWriter(out))) {
            for (int i = 0; i < pageCount; i++) {
                doc.addNewPage(PageSize.A4);
            }
        }
        return out.toByteArray();
    }

    private byte[] createBlankPdf() {
        return createPdf(1);
    }

    private int pageCount(byte[] pdfBytes) throws Exception {
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdfBytes)))) {
            return doc.getNumberOfPages();
        }
    }

    private String extractText(byte[] pdfBytes) throws Exception {
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdfBytes)))) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                sb.append(PdfTextExtractor.getTextFromPage(doc.getPage(i)));
            }
            return sb.toString().replaceAll("\\s+", "");
        }
    }

    @Test
    void addWatermarkBytesBlocking_withColor_shouldProduceValidPdf() throws Exception {
        byte[] result = pdfService.addWatermarkBytesBlocking(createBlankPdf(), "DRAFT", 36f, Color.RED);

        assertThat(result).isNotNull();
        assertThat(pageCount(result)).isEqualTo(1);
    }

    @Test
    void addWatermarkBytesBlocking_withHighRgbColor_shouldNotCorruptPdf() throws Exception {
        byte[] result = pdfService.addWatermarkBytesBlocking(createBlankPdf(), "CONFIDENTIAL", 36f, new Color(255, 0, 0));

        assertThat(result).isNotNull();
        assertThat(pageCount(result)).isEqualTo(1);
        assertThat(extractText(result)).contains("CONFIDENTIAL");
    }

    @Test
    void addWatermarkBytesBlocking_withNamedColor_shouldProduceValidPdf() throws Exception {
        byte[] result = pdfService.addWatermarkBytesBlocking(createBlankPdf(), "SAMPLE", 36f, "red");

        assertThat(result).isNotNull();
        assertThat(pageCount(result)).isEqualTo(1);
    }

    @Test
    void addWatermarkBytesBlocking_withDefaultColor_shouldProduceValidPdf() throws Exception {
        byte[] result = pdfService.addWatermarkBytesBlocking(createBlankPdf(), "WATERMARK");

        assertThat(result).isNotNull();
        assertThat(pageCount(result)).isEqualTo(1);
        assertThat(extractText(result)).contains("WATERMARK");
    }

    @Test
    void addWatermarkBytesBlocking_withMultilineText_shouldProduceValidPdf() throws Exception {
        byte[] result = pdfService.addWatermarkBytesBlocking(createBlankPdf(), "LINE ONE\nLINE TWO", 36f, Color.GRAY);

        assertThat(result).isNotNull();
        assertThat(pageCount(result)).isEqualTo(1);
    }

    @Test
    void addWatermarkBytesBlocking_withNamedColorOnly_shouldProduceValidPdf() throws Exception {
        byte[] result = pdfService.addWatermarkBytesBlocking(createBlankPdf(), "REVIEW", "red");

        assertThat(result).isNotNull();
        assertThat(pageCount(result)).isEqualTo(1);
        assertThat(extractText(result)).contains("REVIEW");
    }

    @Test
    void addWatermarkBytesBlocking_withThaiText_shouldProduceValidPdf() throws Exception {
        byte[] result = pdfService.addWatermarkBytesBlocking(createBlankPdf(), "ตัวอย่าง");

        assertThat(result).isNotNull();
        assertThat(pageCount(result)).isEqualTo(1);
        assertThat(extractText(result)).contains("ตัวอย่าง");
    }

    @Test
    void trimPdfBytesBlocking_shouldLimitPages() throws Exception {
        byte[] result = pdfService.trimPdfBytesBlocking(createPdf(3), 2);

        assertThat(pageCount(result)).isEqualTo(2);
    }

    @Test
    void mergePdfBytesBlocking_shouldCombinePages() throws Exception {
        byte[] result = pdfService.mergePdfBytesBlocking(java.util.List.of(createPdf(2), createPdf(3)));

        assertThat(pageCount(result)).isEqualTo(5);
    }
}
