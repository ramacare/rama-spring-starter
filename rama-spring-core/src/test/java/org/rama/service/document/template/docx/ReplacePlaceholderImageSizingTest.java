package org.rama.service.document.template.docx;

import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.rama.service.document.template.ReplacementProcessor;
import org.rama.service.document.template.docx.ReplacePlaceholder.ImageSizing;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class ReplacePlaceholderImageSizingTest {

    private static final Pattern PATTERN = Pattern.compile("\\{\\{(.+?)}}");

    private ReplacementProcessor replacementProcessor;
    private ReplacePlaceholder replacePlaceholder;

    @BeforeEach
    void setUp() {
        replacementProcessor = mock(ReplacementProcessor.class);
        when(replacementProcessor.parsePlaceholder(anyString(), anyMap())).thenCallRealMethod();
        when(replacementProcessor.processReplacement(anyString(), any(), anyMap())).thenReturn("");
        replacePlaceholder = new ReplacePlaceholder(replacementProcessor, null);
    }

    // ---- sizeInBox -------------------------------------------------------------------------

    @Test
    void sizeInBox_fit_tallImage_shouldMatchBoxHeightAndKeepAspect() {
        long[] size = ReplacePlaceholder.sizeInBox(100, 200, 1000, 1410, ImageSizing.FIT);
        assertThat(size).containsExactly(705, 1410);
    }

    @Test
    void sizeInBox_fit_wideImage_shouldMatchBoxWidthAndKeepAspect() {
        long[] size = ReplacePlaceholder.sizeInBox(400, 100, 1000, 1000, ImageSizing.FIT);
        assertThat(size).containsExactly(1000, 250);
    }

    @Test
    void sizeInBox_fit_smallImage_shouldScaleUpToTouchTheBox() {
        long[] size = ReplacePlaceholder.sizeInBox(10, 10, 1000, 500, ImageSizing.FIT);
        assertThat(size).containsExactly(500, 500);
    }

    @Test
    void sizeInBox_stretch_shouldReturnTheBox() {
        assertThat(ReplacePlaceholder.sizeInBox(100, 200, 1000, 1410, ImageSizing.STRETCH)).containsExactly(1000, 1410);
    }

    @Test
    void sizeInBox_fixedWidthAndFixedHeight_shouldKeepOneSide() {
        assertThat(ReplacePlaceholder.sizeInBox(100, 200, 1000, 1410, ImageSizing.FIXED_WIDTH)).containsExactly(1000, 2000);
        assertThat(ReplacePlaceholder.sizeInBox(100, 200, 1000, 1410, ImageSizing.FIXED_HEIGHT)).containsExactly(705, 1410);
    }

    @Test
    void imageSizing_of_shouldPreferFitThenFixedWidthThenFixedHeight() {
        assertThat(ImageSizing.of(Map.of())).isEqualTo(ImageSizing.STRETCH);
        assertThat(ImageSizing.of(Map.of("stretch", ""))).isEqualTo(ImageSizing.STRETCH);
        assertThat(ImageSizing.of(Map.of("fixedHeight", ""))).isEqualTo(ImageSizing.FIXED_HEIGHT);
        assertThat(ImageSizing.of(Map.of("fixedHeight", "", "fixedWidth", ""))).isEqualTo(ImageSizing.FIXED_WIDTH);
        assertThat(ImageSizing.of(Map.of("fixedWidth", "", "fit", ""))).isEqualTo(ImageSizing.FIT);
    }

    // ---- image-in-shape --------------------------------------------------------------------

    @Test
    void replaceImageInParagraph_fit_shouldKeepAspectAndMatchBoxHeight() throws Exception {
        // 1:2 image into a 1:1.41 dummy
        CTInline inline = replaceInShape("{{scan.id; fit}}", png(100, 200), 1_000_000, 1_410_000);

        assertThat(inline.getExtent().getCy()).isEqualTo(1_410_000);
        assertThat((double) inline.getExtent().getCy() / inline.getExtent().getCx()).isCloseTo(2.0, within(0.001));
    }

    @Test
    void replaceImageInParagraph_withoutAttribute_shouldStillStretchToDummy() throws Exception {
        CTInline inline = replaceInShape("{{scan.id}}", png(100, 200), 1_000_000, 1_410_000);

        assertThat(inline.getExtent().getCx()).isEqualTo(1_000_000);
        assertThat(inline.getExtent().getCy()).isEqualTo(1_410_000);
    }

    @Test
    void replaceImageInParagraph_fixedWidth_shouldBeUnchanged() throws Exception {
        CTInline inline = replaceInShape("{{scan.id; fixedWidth}}", png(100, 200), 1_000_000, 1_410_000);

        assertThat(inline.getExtent().getCx()).isEqualTo(1_000_000);
        assertThat(inline.getExtent().getCy()).isEqualTo(2_000_000);
    }

    // ---- image keyword ---------------------------------------------------------------------

    @Test
    void replacePlaceholderInParagraph_fit_landscapeImage_shouldFitInsideWidthAndHeight() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph paragraph = doc.createParagraph();
            paragraph.createRun().setText("{{page}}");

            replacePlaceholder.replacePlaceholderInParagraph(paragraph, "{{page}}", png(300, 200), 6.5, 9.2, true);

            CTInline inline = firstInline(paragraph);
            assertThat(inline.getExtent().getCx()).isEqualTo(Units.toEMU(6.5 * 72.0));
            assertThat(inline.getExtent().getCy()).isLessThan(Units.toEMU(9.2 * 72.0));
            assertThat((double) inline.getExtent().getCx() / inline.getExtent().getCy()).isCloseTo(1.5, within(0.001));
        }
    }

    @Test
    void replacePlaceholderInParagraph_withoutFit_shouldStillStretch() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph paragraph = doc.createParagraph();
            paragraph.createRun().setText("{{page}}");

            replacePlaceholder.replacePlaceholderInParagraph(paragraph, "{{page}}", png(300, 200), 6.5, 9.2);

            CTInline inline = firstInline(paragraph);
            assertThat(inline.getExtent().getCx()).isEqualTo(Units.toEMU(6.5 * 72.0));
            assertThat(inline.getExtent().getCy()).isEqualTo(Units.toEMU(9.2 * 72.0));
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private CTInline replaceInShape(String descr, byte[] image, int dummyW, int dummyH) throws Exception {
        when(replacementProcessor.processBytes(anyString(), any(), anyMap())).thenReturn(Optional.of(image));
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph paragraph = doc.createParagraph();
            XWPFRun dummy = paragraph.createRun();
            dummy.addPicture(new ByteArrayInputStream(png(10, 10)), PictureType.PNG, "dummy.png", dummyW, dummyH);
            firstInline(paragraph).getDocPr().setDescr(descr);

            replacePlaceholder.replaceImageInParagraph(paragraph, PATTERN, Map.of());

            return firstInline(paragraph);
        }
    }

    private static CTInline firstInline(XWPFParagraph paragraph) {
        return paragraph.getRuns().stream()
                .flatMap(r -> r.getCTR().getDrawingList().stream())
                .filter(d -> d.getInlineArray().length > 0)
                .map(d -> d.getInlineArray(0))
                .findFirst()
                .orElseThrow();
    }

    private static byte[] png(int w, int h) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        }
    }
}
