package org.rama.starter.service.template;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collection;

public class PdfService {
    private final WebClient.Builder webClientBuilder;
    private final String gotenbergServer;

    public PdfService(WebClient.Builder webClientBuilder, String gotenbergServer) {
        this.webClientBuilder = webClientBuilder;
        this.gotenbergServer = gotenbergServer;
    }

    public byte[] convertDocxToPdfBytesBlocking(byte[] docxBytes) {
        ByteArrayResource resource = new ByteArrayResource(docxBytes) {
            @Override
            public String getFilename() {
                return "template.docx";
            }
        };

        return webClientBuilder.build()
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
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.setDestinationStream(outputStream);
            for (byte[] pdf : pdfs) {
                merger.addSource(new RandomAccessReadBuffer(pdf));
            }
            merger.mergeDocuments(null);
            return outputStream.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Error merging PDFs", ex);
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
        try (PDDocument document = Loader.loadPDF(pdfBytes); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            for (var page : document.getPages()) {
                PDRectangle box = page.getMediaBox();
                try (PDPageContentStream stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.beginText();
                    stream.setFont(font, 36);
                    stream.setNonStrokingColor(200, 200, 200);
                    stream.newLineAtOffset(box.getLowerLeftX() + 40, box.getUpperRightY() / 2);
                    stream.showText(watermark);
                    stream.endText();
                }
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Error adding watermark", ex);
        }
    }
}
