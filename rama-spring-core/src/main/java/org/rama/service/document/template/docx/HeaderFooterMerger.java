package org.rama.service.document.template.docx;

import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHdrFtr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

/**
 * Grafts a base document's headers/footers (including embedded images) and page geometry
 * (page size + margins) onto a target document.
 *
 * <p>The target stays the container: its body never moves, so body relationships stay intact.
 * Only the centrally-controlled header/footer content and the page geometry are moved across
 * documents. The target's own header/footer references are stripped so they no longer render.
 */
public class HeaderFooterMerger {

    /** OOXML relationships namespace; {@code r:embed} / {@code r:id} attributes live here. */
    private static final String REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /**
     * Apply the base document's header/footer and page geometry onto {@code target}.
     *
     * @param target the container document whose body is preserved
     * @param base   the base document supplying header/footer + page geometry
     */
    public void apply(XWPFDocument target, XWPFDocument base) {
        try {
            stripTargetHeaderFooterReferences(target);
            graftHeadersAndFooters(target, base);
            copyPageGeometry(target, base);
        } catch (Exception e) {
            throw new RuntimeException("Failed to merge base header/footer onto target document", e);
        }
    }

    /**
     * Remove all header/footer references from the target body's {@code sectPr} so the target's
     * own header/footer parts no longer render. Orphaned parts left in the package are harmless.
     */
    private void stripTargetHeaderFooterReferences(XWPFDocument target) {
        if (!target.getDocument().getBody().isSetSectPr()) {
            return;
        }
        CTSectPr sectPr = target.getDocument().getBody().getSectPr();
        while (sectPr.sizeOfHeaderReferenceArray() > 0) {
            sectPr.removeHeaderReference(0);
        }
        while (sectPr.sizeOfFooterReferenceArray() > 0) {
            sectPr.removeFooterReference(0);
        }
    }

    /**
     * Copy every populated header/footer slot (DEFAULT, FIRST, EVEN) from the base document into a
     * matching new part on the target, deep-copying the part XML and rehoming embedded pictures.
     */
    private void graftHeadersAndFooters(XWPFDocument target, XWPFDocument base) throws Exception {
        XWPFHeaderFooterPolicy basePolicy = base.getHeaderFooterPolicy();
        if (basePolicy == null) {
            return;
        }

        graftHeader(target, basePolicy.getDefaultHeader(), HeaderFooterType.DEFAULT);
        graftHeader(target, basePolicy.getFirstPageHeader(), HeaderFooterType.FIRST);
        graftHeader(target, basePolicy.getEvenPageHeader(), HeaderFooterType.EVEN);

        graftFooter(target, basePolicy.getDefaultFooter(), HeaderFooterType.DEFAULT);
        graftFooter(target, basePolicy.getFirstPageFooter(), HeaderFooterType.FIRST);
        graftFooter(target, basePolicy.getEvenPageFooter(), HeaderFooterType.EVEN);
    }

    private void graftHeader(XWPFDocument target, XWPFHeader src, HeaderFooterType type) throws Exception {
        if (src == null) {
            return;
        }
        XWPFHeader dst = target.createHeader(type);
        copyHeaderFooterPart(src, dst);
    }

    private void graftFooter(XWPFDocument target, XWPFFooter src, HeaderFooterType type) throws Exception {
        if (src == null) {
            return;
        }
        XWPFFooter dst = target.createFooter(type);
        copyHeaderFooterPart(src, dst);
    }

    /**
     * Deep-copy the source part's XML into the destination part, rehoming embedded pictures so the
     * copied relationship ids point at picture parts that actually exist in the destination.
     */
    private void copyHeaderFooterPart(XWPFHeaderFooter src, XWPFHeaderFooter dst) throws Exception {
        // copy() preserves the concrete XMLBeans type, so the cast is always safe
        CTHdrFtr xml = (CTHdrFtr) src._getHdrFtr().copy();
        rehomePictures(src, dst, xml);
        dst.setHeaderFooter(xml);
    }

    /**
     * Copy page size and margins from the base body {@code sectPr} onto the target body
     * {@code sectPr}. Geometry is copied field-by-field so the header/footer references added
     * earlier are preserved.
     */
    private void copyPageGeometry(XWPFDocument target, XWPFDocument base) {
        if (!base.getDocument().getBody().isSetSectPr()) {
            return;
        }
        CTSectPr baseSect = base.getDocument().getBody().getSectPr();

        CTSectPr targetSect = target.getDocument().getBody().isSetSectPr()
                ? target.getDocument().getBody().getSectPr()
                : target.getDocument().getBody().addNewSectPr();

        if (baseSect.isSetPgSz()) {
            targetSect.setPgSz((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz)
                    baseSect.getPgSz().copy());
        }
        if (baseSect.isSetPgMar()) {
            targetSect.setPgMar((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar)
                    baseSect.getPgMar().copy());
        }
    }

    /**
     * Move embedded picture bytes from the source part into the destination part, then rewrite the
     * relationship ids in the copied {@code xml} so each {@code r:embed}/{@code r:id} that pointed
     * at a source relationship now points at the new destination relationship.
     */
    private void rehomePictures(XWPFHeaderFooter src, XWPFHeaderFooter dst, CTHdrFtr xml) throws Exception {
        for (XWPFPictureData pic : src.getAllPictures()) {
            String oldId = src.getRelationId(pic);
            String newId = dst.addPictureData(pic.getData(), pic.getPictureType());
            if (oldId != null && newId != null && !oldId.equals(newId)) {
                rewriteRelationshipId(xml, oldId, newId);
            }
        }
    }

    /**
     * In-place {@link XmlCursor} walk replacing every relationships-namespace attribute whose local
     * name is {@code embed} or {@code id} and whose value equals {@code oldId} with {@code newId}.
     */
    private void rewriteRelationshipId(CTHdrFtr xml, String oldId, String newId) {
        try (XmlCursor cursor = xml.newCursor()) {
            while (cursor.hasNextToken()) {
                if (cursor.isStart()) {
                    try (XmlCursor attrs = cursor.newCursor()) {
                        if (attrs.toFirstAttribute()) {
                            do {
                                javax.xml.namespace.QName name = attrs.getName();
                                if (REL_NS.equals(name.getNamespaceURI())
                                        && ("embed".equals(name.getLocalPart()) || "id".equals(name.getLocalPart()))
                                        && oldId.equals(attrs.getTextValue())) {
                                    attrs.setTextValue(newId);
                                }
                            } while (attrs.toNextAttribute());
                        }
                    }
                }
                cursor.toNextToken();
            }
        }
    }
}
