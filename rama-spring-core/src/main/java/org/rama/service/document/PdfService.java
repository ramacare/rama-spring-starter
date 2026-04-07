package org.rama.service.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;

public class PdfService {
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
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.setDestinationStream(outputStream);
            while (pdfIterator.hasNext()) {
                byte[] pdf = pdfIterator.next();
                merger.addSource(new RandomAccessReadBuffer(pdf));
            }
            merger.mergeDocuments(null);
            return outputStream.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Error merging PDFs", ex);
        }
    }

    public Path mergePdfBytesToTempFileBlocking(Iterator<byte[]> pdfIterator) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("print-merged-", ".pdf");
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.setDestinationFileName(tempFile.toString());
            while (pdfIterator.hasNext()) {
                merger.addSource(new RandomAccessReadBuffer(pdfIterator.next()));
            }
            merger.mergeDocuments(null);
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

    public byte[] trimPdfBytesBlocking(byte[] pdfBytes, int pages) {
        try (PDDocument original = Loader.loadPDF(pdfBytes); PDDocument trimmed = new PDDocument(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            int pageCount = Math.min(original.getNumberOfPages(), pages);
            for (int i = 0; i < pageCount; i++) {
                trimmed.importPage(original.getPage(i));
            }
            trimmed.save(outputStream);
            return outputStream.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Error trimming PDF", ex);
        }
    }

    public byte[] addWatermarkBytesBlocking(byte[] pdfBytes, String watermark) {
        return addWatermarkBytesBlocking(pdfBytes, watermark, 36f, "gray");
    }

    public byte[] addWatermarkBytesBlocking(byte[] pdfBytes, String watermark, float fontSize, String fontColor) {
        java.awt.Color color = resolveColor(fontColor);
        return addWatermarkBytesBlocking(pdfBytes, watermark, fontSize, color);
    }

    public byte[] addWatermarkBytesBlocking(byte[] pdfBytes, String watermark, float fontSize, java.awt.Color color) {
        try (PDDocument document = Loader.loadPDF(pdfBytes); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            for (var page : document.getPages()) {
                PDRectangle box = page.getMediaBox();
                try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String[] lines = watermark.split("\\r?\\n", -1);
                    float centerX = box.getWidth() / 2f;
                    float centerY = box.getHeight() / 2f;
                    double angle = Math.atan2(box.getHeight(), box.getWidth());

                    stream.beginText();
                    stream.setFont(font, fontSize);
                    stream.setNonStrokingColor(color.getRed(), color.getGreen(), color.getBlue());
                    stream.setTextMatrix(Matrix.getRotateInstance(angle, centerX, centerY));
                    float lineHeight = fontSize * 1.2f;
                    float startOffset = ((lines.length - 1) * lineHeight) / 2f;
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        float lineWidth = font.getStringWidth(line) / 1000f * fontSize;
                        stream.setTextMatrix(Matrix.getRotateInstance(angle, centerX - (lineWidth / 2f), centerY + startOffset - (i * lineHeight)));
                        stream.showText(line);
                    }
                    stream.endText();
                }
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Error adding watermark", ex);
        }
    }

    private java.awt.Color resolveColor(String fontColor) {
        if (fontColor == null || fontColor.isBlank()) {
            return new java.awt.Color(200, 200, 200);
        }
        return switch (fontColor.trim().toLowerCase()) {
            case "red" -> java.awt.Color.RED;
            case "gray", "grey" -> new java.awt.Color(180, 180, 180);
            case "black" -> java.awt.Color.BLACK;
            case "white" -> java.awt.Color.WHITE;
            default -> {
                try {
                    yield java.awt.Color.decode(fontColor);
                } catch (NumberFormatException ex) {
                    yield new java.awt.Color(180, 180, 180);
                }
            }
        };
    }
}
