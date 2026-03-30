package org.rama.service.document.template.docx;

import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.tika.Tika;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.openxmlformats.schemas.drawingml.x2006.main.CTNonVisualDrawingProps;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.rama.service.document.BarcodeService;
import org.rama.service.document.template.ReplacementProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReplacePlaceholder {
    private static final Logger log = LoggerFactory.getLogger(ReplacePlaceholder.class);

    private final ReplacementProcessor replacementProcessor;
    private final BarcodeService barcodeService;

    private final Tika tika = new Tika();

    public ReplacePlaceholder(ReplacementProcessor replacementProcessor, BarcodeService barcodeService) {
        this.replacementProcessor = replacementProcessor;
        this.barcodeService = barcodeService;
    }

    public void replacePlaceholderInParagraph(XWPFParagraph paragraph, String placeholder, String replacement) {
        replacePlaceholderInParagraph(paragraph, placeholder, replacement, null, 0, 0);
    }

    public void replacePlaceholderInParagraph(XWPFParagraph paragraph, String placeholder, byte[] imageBytes, double width, double height) {
        replacePlaceholderInParagraph(paragraph, placeholder, "", imageBytes, width, height);
    }

    /**
     * Drop-in optimized:
     * - bytes-first for images (no InputStream reset games)
     * - detect type on bytes
     * - fallback to PNG if POI can't handle the type
     * - compute size once
     */
    public void replacePlaceholderInParagraph(XWPFParagraph paragraph, String placeholder, String replacement, byte[] imageBytes, double width, double height) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) return;

        for (int i = 0; i < runs.size(); i++) {
            XWPFRun run = runs.get(i);
            String runText = run.getText(0);
            if (runText == null || !runText.contains(placeholder)) continue;

            int index = runText.indexOf(placeholder);
            String before = runText.substring(0, index);
            String after = runText.substring(index + placeholder.length());

            run.setText(before, 0);

            // replacement run
            XWPFRun newRun = paragraph.insertNewRun(i + 1);
            DocxTemplateHelper.copyRunStyle(run, newRun);

            if (replacement != null && !replacement.isEmpty()) {
                String[] lines = replacement.split("\\r?\\n", -1);
                for (int j = 0; j < lines.length; j++) {
                    if (j > 0) newRun.addBreak();
                    newRun.setText(lines[j]);
                }
            }

            // optional image insert
            if (imageBytes != null && imageBytes.length > 0) {
                try {
                    addPictureToRun(newRun, imageBytes, width, height);
                } catch (Exception e) {
                    log.error("replacePlaceholderInParagraph image insert failed: {}", e.getMessage());
                }
            }

            // after run
            XWPFRun afterRun = paragraph.insertNewRun(i + 2);
            DocxTemplateHelper.copyRunStyle(run, afterRun);
            afterRun.setText(after);

            i++;
        }
    }

    /**
     * Image replacement in drawings using docPr descr placeholder (e.g. {{photo:image}})
     * Optimized to bytes-first and minimal stream usage.
     */
    public void replaceImageInParagraph(XWPFParagraph paragraph, Pattern pattern, Map<String, Object> replacements) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) return;

        for (int i = 0; i < runs.size(); i++) {
            XWPFRun run = runs.get(i);

            for (CTDrawing ctDrawing : run.getCTR().getDrawingList()) {
                if (ctDrawing.getInlineArray().length == 0) continue;

                CTInline inline = ctDrawing.getInlineArray(0);
                if (inline == null) continue;

                CTNonVisualDrawingProps docPr = inline.getDocPr();
                if (docPr == null || docPr.getDescr() == null) continue;

                String description = docPr.getDescr();
                Matcher matcher = pattern.matcher(description);
                if (!matcher.find()) continue;

                String placeholder = matcher.group();

                Map<String, String> attributeData = new HashMap<>();
                String replacementKey = replacementProcessor.parsePlaceholder(placeholder, attributeData);

                boolean shouldProcess = replacements.containsKey(replacementKey)
                                || replacementKey.contains(".")
                                || replacementKey.contains("[")
                                || attributeData.containsKey("ifempty")
                                || attributeData.containsKey("else");

                if (!shouldProcess) continue;

                String replacement = replacementProcessor.processReplacement(replacementKey, replacements, attributeData);

                try {
                    Optional<byte[]> imageBytesOpt = resolveImageBytes(replacementKey, replacements, attributeData, replacement);
                    if (imageBytesOpt.isEmpty()) continue;

                    byte[] imageBytes = imageBytesOpt.get();

                    long width = inline.getExtent().getCx();
                    long height = inline.getExtent().getCy();

                    // fixedWidth/fixedHeight recalculation based on aspect ratio
                    if (attributeData.containsKey("fixedWidth") || attributeData.containsKey("fixedHeight")) {
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
                        if (img != null) {
                            int w = img.getWidth();
                            int h = img.getHeight();
                            float aspect = (w > 0) ? ((float) h / (float) w) : 1f;

                            if (attributeData.containsKey("fixedWidth")) {
                                height = Math.round(width * aspect);
                            } else {
                                width = (aspect > 0) ? Math.round(height / aspect) : height;
                            }
                        }
                    }
                    paragraph.removeRun(i);

                    // ensure POI supported format (fallback to PNG)
                    PictureData pic = normalizeToPoiPicture(imageBytes);

                    // replace run
                    XWPFRun newRun = paragraph.insertNewRun(i);
                    newRun.addPicture(new ByteArrayInputStream(pic.bytes), pic.type, null, (int) width, (int) height);
                } catch (Exception e) {
                    log.error("replaceImageInParagraph failed: {}", e.getMessage());
                }

                break;
            }
        }
    }

    public void insertHtmlToParagraph(XWPFParagraph paragraph, String placeholder, String html) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);

        List<XWPFRun> runs = paragraph.getRuns();
        if (runs == null || runs.isEmpty()) return;

        for (int i = 0; i < runs.size(); i++) {
            XWPFRun run = runs.get(i);
            String runText = run.getText(0);
            if (runText == null || !runText.contains(placeholder)) continue;

            int index = runText.indexOf(placeholder);
            String beforePlaceholder = runText.substring(0, index);
            String afterPlaceholder = runText.substring(index + placeholder.length());

            run.setText(beforePlaceholder, 0);

            AtomicInteger runIndex = new AtomicInteger(i + 1);
            processHtmlElement(doc.body(), new HashMap<>(), paragraph, run, runIndex);

            XWPFRun afterRun = paragraph.insertNewRun(runIndex.get() + 1);
            DocxTemplateHelper.copyRunStyle(run, afterRun);
            afterRun.setText(afterPlaceholder);

            i = runIndex.get();
        }
    }

    public void processHtmlElement(Element element, Map<String, String> tagPath, XWPFParagraph paragraph, XWPFRun sourceRun, AtomicInteger i) {
        Map<String, String> newTagPath = new HashMap<>(tagPath);
        if ("font".equals(element.tagName()) && element.hasAttr("color")) {
            String c = element.attr("color");
            if (c.startsWith("#") && c.length() >= 7) newTagPath.put("color", c.substring(1, 7));
        }

        String style = element.attr("style");
        String color = style.replaceAll("^.*color:\\s*(#[0-9a-fA-F]{6}).*$", "$1");
        if (color.startsWith("#") && color.length() == 7) {
            newTagPath.put("color", color.substring(1));
        }

        newTagPath.put(element.tagName(), "");

        for (Node child : element.childNodes()) {
            if (child instanceof TextNode) {
                String text = ((TextNode) child).text().replaceAll("\\s+", " ");
                if (text.isEmpty()) continue;

                XWPFRun newRun = paragraph.insertNewRun(i.get());
                DocxTemplateHelper.copyRunFont(sourceRun, newRun);

                if (newTagPath.containsKey("b")) newRun.setBold(true);
                if (newTagPath.containsKey("u")) newRun.setUnderline(UnderlinePatterns.SINGLE);
                if (newTagPath.containsKey("i")) newRun.setItalic(true);
                if (newTagPath.containsKey("s") || newTagPath.containsKey("strike")) newRun.setStrikeThrough(true);
                if (newTagPath.containsKey("color")) newRun.setColor(newTagPath.get("color"));

                newRun.setText(text);

                if (newTagPath.containsKey("br")) newRun.addBreak();

                i.incrementAndGet();
            } else if (child instanceof Element) {
                processHtmlElement((Element) child, newTagPath, paragraph, sourceRun, i);
            }
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private void addPictureToRun(XWPFRun run, byte[] inputBytes, double widthInInches, double heightInInches) throws Exception {
        PictureData pic = normalizeToPoiPicture(inputBytes);

        // compute size
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(pic.bytes));
        if (img == null) return;

        int pxW = img.getWidth();
        int pxH = img.getHeight();
        float aspect = (pxW > 0) ? ((float) pxH / (float) pxW) : 1f;

        int emuW;
        int emuH;

        // interpret width/height as inches (your existing convention: width * 72 points)
        if (widthInInches == 0 && heightInInches == 0) {
            // use pixel size at assumed 96 dpi
            double wPt = (pxW / 96.0) * 72.0;
            double hPt = (pxH / 96.0) * 72.0;
            emuW = Units.toEMU(wPt);
            emuH = Units.toEMU(hPt);
        } else if (widthInInches != 0 && heightInInches == 0) {
            emuW = Units.toEMU(widthInInches * 72.0);
            emuH = Math.round(emuW * aspect);
        } else if (widthInInches == 0) {
            emuH = Units.toEMU(heightInInches * 72.0);
            emuW = (aspect > 0) ? Math.round(emuH / aspect) : emuH;
        } else {
            emuW = Units.toEMU(widthInInches * 72.0);
            emuH = Units.toEMU(heightInInches * 72.0);
        }

        run.addPicture(new ByteArrayInputStream(pic.bytes), pic.type, null, emuW, emuH);
    }

    private Optional<byte[]> resolveImageBytes(String replacementKey, Map<String, Object> replacements, Map<String, String> attributeData, String replacement) throws Exception {
        if (attributeData.containsKey("qrcode")) {
            BufferedImage img = barcodeService.generateQRCode(replacement);
            return Optional.of(toPngBytes(img));
        }
        if (attributeData.containsKey("barcode39") || attributeData.containsKey("barcode")) {
            BufferedImage img = barcodeService.generateCode39(replacement);
            return Optional.of(toPngBytes(img));
        }
        if (attributeData.containsKey("barcode128")) {
            BufferedImage img = barcodeService.generateCode128(replacement);
            return Optional.of(toPngBytes(img));
        }

        // IMPORTANT: for best performance, add a byte[] path in ReplacementProcessor.
        // For now: accept InputStream, read bytes once, close immediately.
        return replacementProcessor.processBytes(replacementKey, replacements, attributeData);
    }

    private PictureData normalizeToPoiPicture(byte[] bytes) {
        try {
            String ct = tika.detect(bytes);
            Optional<PictureType> typeOpt = DocxTemplateHelper.contentTypeToPictureType(ct);
            if (typeOpt.isPresent()) {
                return new PictureData(bytes, typeOpt.get());
            }
        } catch (Exception ignore) {
        }

        // fallback to PNG
        try {
            byte[] pngBytes = toPngBytesBestEffort(bytes);
            return new PictureData(pngBytes, PictureType.PNG);
        } catch (Exception e) {
            // last resort: still try as PNG
            return new PictureData(bytes, PictureType.PNG);
        }
    }

    private byte[] toPngBytesBestEffort(byte[] input) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(input));
            if (img == null) return input;
            return toPngBytes(img);
        } catch (Exception e) {
            return input;
        }
    }

    private byte[] toPngBytes(BufferedImage img) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        }
    }

    private record PictureData(byte[] bytes, PictureType type) { }
}
