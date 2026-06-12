package org.rama.service.document.template.docx;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderFooterMergerTest {

    private final HeaderFooterMerger merger = new HeaderFooterMerger();

    private XWPFDocument doc(String bodyText, String headerText, String footerText) {
        XWPFDocument d = new XWPFDocument();
        d.createParagraph().createRun().setText(bodyText);
        XWPFHeaderFooterPolicy policy = d.createHeaderFooterPolicy();
        if (headerText != null) {
            XWPFHeader h = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
            h.createParagraph().createRun().setText(headerText);
        }
        if (footerText != null) {
            XWPFFooter f = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
            f.createParagraph().createRun().setText(footerText);
        }
        return d;
    }

    private XWPFDocument roundtrip(XWPFDocument d) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.write(out);
            return new XWPFDocument(new ByteArrayInputStream(out.toByteArray()));
        }
    }

    private String headerText(XWPFDocument d) {
        StringBuilder sb = new StringBuilder();
        for (XWPFHeader h : d.getHeaderList()) sb.append(h.getText());
        return sb.toString();
    }
    private String footerText(XWPFDocument d) {
        StringBuilder sb = new StringBuilder();
        for (XWPFFooter f : d.getFooterList()) sb.append(f.getText());
        return sb.toString();
    }
    private String bodyText(XWPFDocument d) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : d.getParagraphs()) sb.append(p.getText());
        return sb.toString();
    }

    @Test
    void apply_graftsBaseHeaderAndFooter_keepsTargetBody_dropsTargetOwnHeader() throws Exception {
        XWPFDocument target = doc("PATIENT BODY", "OLD ORIGINAL HEADER", "OLD ORIGINAL FOOTER");
        XWPFDocument base = doc("BASE BODY (discarded)", "CENTRAL HEADER", "CENTRAL FOOTER");

        merger.apply(target, base);
        XWPFDocument result = roundtrip(target);

        assertThat(bodyText(result)).contains("PATIENT BODY");
        assertThat(headerText(result)).contains("CENTRAL HEADER").doesNotContain("OLD ORIGINAL HEADER");
        assertThat(footerText(result)).contains("CENTRAL FOOTER").doesNotContain("OLD ORIGINAL FOOTER");
    }

    @Test
    void apply_copiesBasePageSizeAndMargins() throws Exception {
        XWPFDocument target = doc("BODY", null, null);
        XWPFDocument base = doc("BASE", "H", "F");
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr baseSect =
                base.getDocument().getBody().isSetSectPr()
                        ? base.getDocument().getBody().getSectPr()
                        : base.getDocument().getBody().addNewSectPr();
        baseSect.addNewPgSz().setW(java.math.BigInteger.valueOf(8391)); // A5 width in twips

        merger.apply(target, base);
        XWPFDocument result = roundtrip(target);

        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr resultSect =
                result.getDocument().getBody().getSectPr();
        assertThat(resultSect.getPgSz().getW()).isEqualTo(java.math.BigInteger.valueOf(8391));
    }

    private static final byte[] PNG_1PX = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @Test
    void apply_carriesHeaderImageBytesIntoTarget() throws Exception {
        XWPFDocument target = doc("BODY", null, null);
        XWPFDocument base = new XWPFDocument();
        base.createParagraph().createRun().setText("BASE BODY");
        XWPFHeader h = base.createHeaderFooterPolicy().createHeader(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFRun run = h.createParagraph().createRun();
        run.addPicture(new java.io.ByteArrayInputStream(PNG_1PX), Document.PICTURE_TYPE_PNG,
                "logo.png", 100, 100);

        merger.apply(target, base);
        XWPFDocument result = roundtrip(target);

        byte[] found = null;
        for (XWPFHeader rh : result.getHeaderList()) {
            for (XWPFPictureData pd : rh.getAllPictures()) found = pd.getData();
        }
        assertThat(found).isNotNull();
        assertThat(found).isEqualTo(PNG_1PX);
    }
}
