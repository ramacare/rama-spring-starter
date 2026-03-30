package org.rama.service.document.template.docx;

import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DocxTemplateHelper {
    private DocxTemplateHelper() {
    }
    public static void mergeRunStyle(XWPFRun sourceRun, XWPFRun mergeRun) {
        CTR mergeCTR = mergeRun.getCTR();
        CTR sourceCTR = sourceRun.getCTR();

        CTRPr mergeRPr = mergeCTR.isSetRPr() ? mergeCTR.getRPr() : null;
        CTRPr sourceRPr = sourceCTR.isSetRPr() ? sourceCTR.getRPr() : sourceCTR.addNewRPr();

        if (mergeRPr != null) {
            sourceRPr.set(mergeRPr);
        }

        if (sourceRun.getFontSizeAsDouble()!=null) setFontSizeComplex(sourceRun,sourceRun.getFontSizeAsDouble());
    }

    public static void copyRunStyle(XWPFRun sourceRun, XWPFRun targetRun) {
        CTR sourceCTR = sourceRun.getCTR();
        CTR targetCTR = targetRun.getCTR();

        if (targetCTR.isSetRPr()) {
            targetCTR.unsetRPr();
        }

        if (sourceCTR.isSetRPr()) {
            targetCTR.setRPr(sourceCTR.getRPr());
        }
    }

    public static void copyRunFont(XWPFRun sourceRun, XWPFRun targetRun) {
        targetRun.setFontFamily(sourceRun.getFontFamily());
        targetRun.setColor(sourceRun.getColor());

        if (sourceRun.getTextPosition() != -1) targetRun.setTextPosition(sourceRun.getTextPosition());
        if (sourceRun.getFontSizeAsDouble() != null) setFontSizeComplex(targetRun,sourceRun.getFontSizeAsDouble());
    }

    public static void setFontSizeComplex(XWPFRun run, double fontSize) {
        CTR ctr = run.getCTR();
        CTRPr rPr = ctr.isSetRPr() ? ctr.getRPr() : ctr.addNewRPr();
        rPr.addNewSzCs().setVal(fontSize*2);
    }

    public static Map<String, List<XmlObject>> extractTextBox(XWPFRun run) {
        // Temporary storage to hold references and avoid ConcurrentModificationException
        List<XmlObject> paragraphNodes = new ArrayList<>();
        List<XmlObject> tableNodes = new ArrayList<>();

        String xpath = "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' " +
                "declare namespace wps='http://schemas.microsoft.com/office/word/2010/wordprocessingShape' " +
                ".//w:txbxContent | .//wps:txbxContent";

        // STEP 1: SCAN (No modifications allowed here)
        try (XmlCursor cursor = run.getCTR().newCursor()) {
            cursor.selectPath(xpath);
            while (cursor.toNextSelection()) {
                XmlObject container = cursor.getObject();
                try (XmlCursor innerCursor = container.newCursor()) {
                    if (innerCursor.toFirstChild()) {
                        do {
                            XmlObject child = innerCursor.getObject();
                            String localName = child.getDomNode().getLocalName();
                            if ("p".equals(localName)) {
                                paragraphNodes.add(child);
                            } else if ("tbl".equals(localName)) {
                                tableNodes.add(child);
                            }
                        } while (innerCursor.toNextSibling());
                    }
                }
            }
        }

        return Map.of("paragraph",paragraphNodes,"table",tableNodes);
    }

    public static Optional<PictureType> contentTypeToPictureType(String contentType) {
        PictureType pictureType;
        switch (contentType) {
            case "image/jpeg":
                pictureType = PictureType.JPEG;
                break;
            case "image/png":
                pictureType = PictureType.PNG;
                break;
            case "image/gif":
                pictureType = PictureType.GIF;
                break;
            case "image/bmp":
                pictureType = PictureType.BMP;
                break;
            case "image/wmf":
                pictureType = PictureType.WMF;
                break;
            case "image/emf":
                pictureType = PictureType.EMF;
                break;
            case "image/pict":
                pictureType = PictureType.PICT;
                break;
            case "image/svg+xml":
                pictureType = PictureType.SVG;
                break;
            default:
                return Optional.empty();
        }
        return Optional.of(pictureType);
    }
}
