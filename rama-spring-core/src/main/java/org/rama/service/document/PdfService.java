package org.rama.service.document;

import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.DeviceGray;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.colors.WebColors;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.utils.PdfMerger;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;

public class PdfService {
    private static final String WATERMARK_FONT_RESOURCE = "/org/rama/fonts/THSarabunNew.ttf";
    private static final float DEFAULT_WATERMARK_FONT_SIZE = 96f;

    private final WebClient webClient;
    private final String gotenbergServer;

    public PdfService(WebClient.Builder webClientBuilder, String gotenbergServer) {
        this.webClient = webClientBuilder.build();
        this.gotenbergServer = gotenbergServer;
    }

    public byte[] convertDocxToPdfBytesBlocking(byte[] docxBytes) {
        ByteArrayResource resource = new ByteArrayResource(docxBytes) {
            @Override
            public String getFilename() {
                return "template.docx";
            }
        };

        return webClient
                .post()
                .uri(gotenbergServer + "/forms/libreoffice/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("files", resource))
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .as(DataBufferUtils::join)
                .map(dataBuffer -> {
                    byte[] out = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(out);
                    DataBufferUtils.release(dataBuffer);
                    return out;
                })
                .block();
    }

    public byte[] mergePdfBytesBlocking(Collection<byte[]> pdfs) {
        return mergePdfBytesBlocking(pdfs.iterator());
    }

    public byte[] mergePdfBytesBlocking(Iterator<byte[]> pdfIterator) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        mergePdfBytesTo(pdfIterator, outputStream);
        return outputStream.toByteArray();
    }

    public Path mergePdfBytesToTempFileBlocking(Iterator<byte[]> pdfIterator) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("print-merged-", ".pdf");
            try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
                mergePdfBytesTo(pdfIterator, outputStream);
            }
            return tempFile;
        } catch (Exception ex) {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignore) {
                }
            }
            throw new IllegalStateException("Error merging PDFs to temp file", ex);
        }
    }

    private void mergePdfBytesTo(Iterator<byte[]> pdfIterator, OutputStream outputStream) {
        try (PdfDocument mergedPdfDoc = new PdfDocument(new PdfWriter(outputStream))) {
            PdfMerger pdfMerger = new PdfMerger(mergedPdfDoc);
            while (pdfIterator.hasNext()) {
                byte[] pdf = pdfIterator.next();
                try (ByteArrayInputStream in = new ByteArrayInputStream(pdf);
                     PdfDocument sourcePdfDoc = new PdfDocument(new PdfReader(in))) {
                    pdfMerger.merge(sourcePdfDoc, 1, sourcePdfDoc.getNumberOfPages());
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Error merging PDFs", ex);
        }
    }

    public byte[] trimPdfBytesBlocking(byte[] pdfBytes, int pages) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(pdfBytes);
             PdfDocument originalPdfDoc = new PdfDocument(new PdfReader(inputStream));
             PdfDocument trimmedPdfDoc = new PdfDocument(new PdfWriter(outputStream))) {

            int pagesToCopy = Math.min(originalPdfDoc.getNumberOfPages(), pages);
            for (int i = 1; i <= pagesToCopy; i++) {
                originalPdfDoc.copyPagesTo(i, i, trimmedPdfDoc);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Error trimming PDF", ex);
        }
        return outputStream.toByteArray();
    }

    public byte[] addWatermarkBytesBlocking(byte[] pdfBytes, String watermark) {
        return addWatermarkBytesBlocking(pdfBytes, watermark, DEFAULT_WATERMARK_FONT_SIZE, new DeviceGray());
    }

    public byte[] addWatermarkBytesBlocking(byte[] pdfBytes, String watermark, String fontColor) {
        return addWatermarkBytesBlocking(pdfBytes, watermark, DEFAULT_WATERMARK_FONT_SIZE, fontColor);
    }

    public byte[] addWatermarkBytesBlocking(byte[] pdfBytes, String watermark, float fontSize, String fontColor) {
        Color color = resolveColor(fontColor);
        return addWatermarkBytesBlocking(pdfBytes, watermark, fontSize, color);
    }

    public byte[] addWatermarkBytesBlocking(byte[] pdfBytes, String watermark, float fontSize, java.awt.Color color) {
        Color itextColor = new DeviceRgb(color.getRed(), color.getGreen(), color.getBlue());
        return addWatermarkBytesBlocking(pdfBytes, watermark, fontSize, itextColor);
    }

    public byte[] addWatermarkBytesBlocking(byte[] pdfBytes, String watermark, float fontSize, Color fontColor) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(pdfBytes);
             PdfDocument pdfDoc = new PdfDocument(new PdfReader(inputStream), new PdfWriter(outputStream))) {

            PdfFont font = loadWatermarkFont();
            int numberOfLines = watermark.split("\\r?\\n", -1).length;
            float estimateLineHeight = font.getAscent(watermark, fontSize) - font.getDescent(watermark, fontSize);

            for (int pageNum = 1; pageNum <= pdfDoc.getNumberOfPages(); pageNum++) {
                PdfPage page = pdfDoc.getPage(pageNum);
                Rectangle pageSize = page.getPageSize();
                float width = pageSize.getWidth();
                float height = pageSize.getHeight();
                double angle = Math.atan(height / width);
                double boxWidth = Math.sqrt(width * width + height * height);

                try (Canvas canvas = new Canvas(page, pageSize)) {
                    Paragraph paragraph = new Paragraph(watermark)
                            .setFontSize(fontSize)
                            .setBold()
                            .setFont(font)
                            .setFontColor(fontColor)
                            .setOpacity(0.5f)
                            .setTextAlignment(TextAlignment.CENTER)
                            .setRotationAngle(angle)
                            .setWidth((float) boxWidth)
                            .setFixedPosition(estimateLineHeight * numberOfLines / 2f, -fontSize / 2f, (float) boxWidth);
                    canvas.add(paragraph);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Error adding watermark", ex);
        }
        return outputStream.toByteArray();
    }

    private PdfFont loadWatermarkFont() throws java.io.IOException {
        try (InputStream fontStream = PdfService.class.getResourceAsStream(WATERMARK_FONT_RESOURCE)) {
            if (fontStream == null) {
                throw new IllegalStateException("Watermark font not found on classpath: " + WATERMARK_FONT_RESOURCE);
            }
            return PdfFontFactory.createFont(fontStream.readAllBytes(), PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
        }
    }

    private Color resolveColor(String fontColor) {
        if (fontColor == null || fontColor.isBlank()) {
            return new DeviceGray();
        }
        Color color = WebColors.getRGBColor(fontColor.trim());
        return color != null ? color : new DeviceGray();
    }
}
