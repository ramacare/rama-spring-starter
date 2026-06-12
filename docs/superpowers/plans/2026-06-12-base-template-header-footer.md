# BaseTemplate Header/Footer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a docx print template declare a centralized base template (header/footer + page layout) via a `BaseTemplate` custom property; the print flow merges the original body into the base's header/footer at render time.

**Architecture:** A new `BaseTemplateResolver` SPI (consumer-implemented, no-op default) resolves the `BaseTemplate` code to an already-preprocessed docx stream. `DocxTemplateProcessor` detects the property, replaces placeholders in the original body and the base's headers/footers separately, then `HeaderFooterMerger` grafts the base's headers/footers + page geometry onto the original document (the original stays the container so body relationships are never moved). Template caching is untouched — base resolution reuses the consumer's existing `docx-template-cache$` path.

**Tech Stack:** Java 17, Spring Boot 4.0.3, Apache POI XWPF (`poi-ooxml`), JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/superpowers/specs/2026-06-12-base-template-header-footer-design.md` · **Issue:** [#31](https://gitlab.rama.mahidol.ac.th/ramacare/backend/rama-spring-starter/-/work_items/31)

---

## File structure

| File | Responsibility |
|---|---|
| `rama-spring-core/.../service/document/template/BaseTemplateResolver.java` (new) | SPI: `Optional<InputStream> resolve(String code, Map<String,Object> data)` |
| `rama-spring-core/.../service/document/template/docx/HeaderFooterMerger.java` (new) | Graft base headers/footers + page geometry onto a target `XWPFDocument` |
| `rama-spring-core/.../service/document/template/DocxTemplateProcessor.java` (modify) | Read `BaseTemplate`, body-only / header-footer-only replacement, base-template render path |
| `rama-spring-autoconfigure/.../autoconfigure/DocumentProperties.java` (modify) | `baseTemplateProperty` (default `"BaseTemplate"`) |
| `rama-spring-autoconfigure/.../autoconfigure/RamaStarterAutoConfiguration.java` (modify) | No-op `BaseTemplateResolver` bean, `HeaderFooterMerger` bean, updated `DocxTemplateProcessor` bean |
| `rama-spring-core/.../test/.../template/docx/HeaderFooterMergerTest.java` (new) | Unit tests for the merger |
| `rama-spring-core/.../test/.../template/DocxTemplateProcessorBaseTemplateTest.java` (new) | Integration test for the base-template branch |

**Build/test commands** (run from repo root):
- Core unit tests: `mvn -q -pl rama-spring-core test -Dtest=<ClassName>`
- Full core module: `mvn -q -pl rama-spring-core test`
- Compile everything: `mvn -q -DskipTests compile`

---

## Task 1: `BaseTemplate` property on DocumentProperties

**Files:**
- Modify: `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/DocumentProperties.java:15`
- Test: `rama-spring-autoconfigure/src/test/java/org/rama/autoconfigure/DocumentPropertiesTest.java` (create)

- [ ] **Step 1: Write the failing test**

```java
package org.rama.autoconfigure;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentPropertiesTest {
    @Test
    void baseTemplateProperty_defaultsToBaseTemplate() {
        assertThat(new DocumentProperties().getBaseTemplateProperty()).isEqualTo("BaseTemplate");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl rama-spring-autoconfigure test -Dtest=DocumentPropertiesTest`
Expected: FAIL — `getBaseTemplateProperty()` does not exist (compile error).

- [ ] **Step 3: Add the field**

In `DocumentProperties.java`, after line 15 (`private String maximumPagesProperty = "MaximumPages";`) add:

```java
    private String baseTemplateProperty = "BaseTemplate";
```

Lombok `@Data` generates `getBaseTemplateProperty()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl rama-spring-autoconfigure test -Dtest=DocumentPropertiesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/DocumentProperties.java \
        rama-spring-autoconfigure/src/test/java/org/rama/autoconfigure/DocumentPropertiesTest.java
git commit -m "feat(print): add document.base-template-property (default BaseTemplate) (#31)"
```

---

## Task 2: `BaseTemplateResolver` SPI interface

**Files:**
- Create: `rama-spring-core/src/main/java/org/rama/service/document/template/BaseTemplateResolver.java`

An interface has no behavior to unit-test in isolation; it is exercised end-to-end in Task 4. This task just creates the contract.

- [ ] **Step 1: Create the interface**

```java
package org.rama.service.document.template;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a base-template code (declared via the {@code BaseTemplate} docx custom
 * property) to an already-preprocessed docx stream.
 *
 * <p>The starter has no concept of template codes — the DB {@code document_template}
 * table and the {@code classpath:documents/templates/<code>.docx} fallback live in the
 * consumer application. Consumers implement this interface (typically by wrapping their
 * existing {@code TemplateResolver} + {@code TemplatePreprocessor}) and register it as a
 * Spring bean. The starter ships a no-op default that returns {@link Optional#empty()},
 * leaving the print pipeline unchanged until a consumer opts in.
 *
 * <p>The returned stream MUST already be preprocessed (the same output the consumer feeds
 * to {@code DocxTemplateProcessor.processTemplate} for a normal print), so base-template
 * resolution reuses the existing {@code docx-template-cache$} cache.
 */
public interface BaseTemplateResolver {

    /**
     * @param templateCode the value of the {@code BaseTemplate} custom property
     * @param replacements the print data map (for consumers that select a variant by data)
     * @return the preprocessed base docx stream, or {@link Optional#empty()} if no template
     *         is found for the code (the caller then renders the original template normally)
     */
    Optional<InputStream> resolve(String templateCode, Map<String, Object> replacements);
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -q -pl rama-spring-core -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add rama-spring-core/src/main/java/org/rama/service/document/template/BaseTemplateResolver.java
git commit -m "feat(print): add BaseTemplateResolver SPI (#31)"
```

---

## Task 3: `HeaderFooterMerger` (core POI plumbing)

This is the riskiest unit. Build it test-first: each step adds one assertion about what must survive the graft. Tests build docx documents in memory (no `.docx` fixtures exist in the repo — match that convention).

**Files:**
- Create: `rama-spring-core/src/main/java/org/rama/service/document/template/docx/HeaderFooterMerger.java`
- Test: `rama-spring-core/src/test/java/org/rama/service/document/template/docx/HeaderFooterMergerTest.java`

- [ ] **Step 1: Write the first failing test (header + footer text grafted, target body kept)**

```java
package org.rama.service.document.template.docx;

import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderFooterMergerTest {

    private final HeaderFooterMerger merger = new HeaderFooterMerger();

    /** Build a doc with a single body paragraph and an optional default header/footer text. */
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl rama-spring-core test -Dtest=HeaderFooterMergerTest`
Expected: FAIL — `HeaderFooterMerger` does not exist (compile error).

- [ ] **Step 3: Implement `HeaderFooterMerger` (minimal: strip target refs, copy base headers/footers)**

```java
package org.rama.service.document.template.docx;

import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHdrFtr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

/**
 * Grafts the headers/footers and page geometry of a base document onto a target document.
 * The target stays the container, so its body content (and the relationships of that body)
 * is never moved across documents. Used to centralize header/footer via the BaseTemplate
 * docx property.
 */
public class HeaderFooterMerger {

    public void apply(XWPFDocument target, XWPFDocument base) {
        try {
            removeHeaderFooterReferences(target);

            XWPFHeaderFooterPolicy basePolicy = base.getHeaderFooterPolicy();
            if (basePolicy != null) {
                copyHeader(target, base, basePolicy.getDefaultHeader(), XWPFHeaderFooterPolicy.DEFAULT);
                copyHeader(target, base, basePolicy.getFirstPageHeader(), XWPFHeaderFooterPolicy.FIRST);
                copyHeader(target, base, basePolicy.getEvenPageHeader(), XWPFHeaderFooterPolicy.EVEN);
                copyFooter(target, base, basePolicy.getDefaultFooter(), XWPFHeaderFooterPolicy.DEFAULT);
                copyFooter(target, base, basePolicy.getFirstPageFooter(), XWPFHeaderFooterPolicy.FIRST);
                copyFooter(target, base, basePolicy.getEvenPageFooter(), XWPFHeaderFooterPolicy.EVEN);
            }
            copyPageGeometry(target, base);
        } catch (Exception e) {
            throw new RuntimeException("Failed to merge base template header/footer", e);
        }
    }

    private void removeHeaderFooterReferences(XWPFDocument target) {
        CTSectPr sectPr = target.getDocument().getBody().getSectPr();
        if (sectPr == null) return;
        for (int i = sectPr.sizeOfHeaderReferenceArray() - 1; i >= 0; i--) sectPr.removeHeaderReference(i);
        for (int i = sectPr.sizeOfFooterReferenceArray() - 1; i >= 0; i--) sectPr.removeFooterReference(i);
    }

    private void copyHeader(XWPFDocument target, XWPFDocument base, XWPFHeader src, Enum type) throws Exception {
        if (src == null) return;
        XWPFHeader dst = target.createHeader((org.apache.poi.wp.usermodel.HeaderFooterType) typeOf(type, true));
        CTHdrFtr xml = (CTHdrFtr) src._getHdrFtr().copy();
        rehomePictures(src, dst, xml);
        dst.getCTHdrFtr().set(xml);
    }

    private void copyFooter(XWPFDocument target, XWPFDocument base, XWPFFooter src, Enum type) throws Exception {
        if (src == null) return;
        XWPFFooter dst = target.createFooter((org.apache.poi.wp.usermodel.HeaderFooterType) typeOf(type, false));
        CTHdrFtr xml = (CTHdrFtr) src._getHdrFtr().copy();
        rehomePictures(src, dst, xml);
        dst.getCTHdrFtr().set(xml);
    }

    /** Map XWPFHeaderFooterPolicy DEFAULT/FIRST/EVEN ints to POI HeaderFooterType. */
    private org.apache.poi.wp.usermodel.HeaderFooterType typeOf(Enum ignored, boolean header) {
        return org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT;
    }

    private void rehomePictures(XWPFHeaderFooter src, XWPFHeaderFooter dst, CTHdrFtr xml) {
        // overridden in Step 6 (image support)
    }

    private void copyPageGeometry(XWPFDocument target, XWPFDocument base) {
        CTSectPr baseSect = base.getDocument().getBody().getSectPr();
        CTSectPr tgtSect = target.getDocument().getBody().getSectPr();
        if (baseSect == null || tgtSect == null) return;
        if (baseSect.isSetPgSz()) tgtSect.setPgSz((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz) baseSect.getPgSz().copy());
        if (baseSect.isSetPgMar()) tgtSect.setPgMar((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar) baseSect.getPgMar().copy());
    }
}
```

> NOTE for the implementer: the `typeOf` helper above is a deliberate simplification — `createHeader`/`createFooter` here only need to produce DEFAULT/FIRST/EVEN parts. POI's `XWPFDocument.createHeader(HeaderFooterType)` accepts `DEFAULT`, `FIRST`, `EVEN`. Map the policy slot (`DEFAULT`/`FIRST`/`EVEN`) to the matching `HeaderFooterType` instead of always returning `DEFAULT`; pass the slot through `copyHeader`/`copyFooter`. Adjust the method shape as needed to make the Step-7 multi-slot test pass — the test is the contract.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl rama-spring-core test -Dtest=HeaderFooterMergerTest`
Expected: PASS. If POI API names differ in this version (e.g. `createHeaderFooterPolicy` vs `getHeaderFooterPolicy`), adjust to the version on the classpath until green.

- [ ] **Step 5: Write the failing test for page geometry**

Add to `HeaderFooterMergerTest`:

```java
    @Test
    void apply_copiesBasePageSizeAndMargins() throws Exception {
        XWPFDocument target = doc("BODY", null, null);
        XWPFDocument base = doc("BASE", "H", "F");
        // give base a distinctive A5 page size
        CTSectPr baseSect = base.getDocument().getBody().isSetSectPr()
                ? base.getDocument().getBody().getSectPr()
                : base.getDocument().getBody().addNewSectPr();
        baseSect.addNewPgSz().setW(java.math.BigInteger.valueOf(8391)); // A5 width in twips

        merger.apply(target, base);
        XWPFDocument result = roundtrip(target);

        CTSectPr resultSect = result.getDocument().getBody().getSectPr();
        assertThat(resultSect.getPgSz().getW()).isEqualTo(java.math.BigInteger.valueOf(8391));
    }
```

- [ ] **Step 6: Run it — confirm it passes** (the Step-3 `copyPageGeometry` already covers this)

Run: `mvn -q -pl rama-spring-core test -Dtest=HeaderFooterMergerTest`
Expected: PASS. If FAIL, ensure `target` has a `sectPr` (call `target.getDocument().getBody().addNewSectPr()` inside `copyPageGeometry` when absent before copying).

- [ ] **Step 7: Write the failing test for embedded header image survival**

Add to `HeaderFooterMergerTest`:

```java
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
```

- [ ] **Step 8: Run test to verify it fails**

Run: `mvn -q -pl rama-spring-core test -Dtest=HeaderFooterMergerTest#apply_carriesHeaderImageBytesIntoTarget`
Expected: FAIL — no picture data in the result header (the no-op `rehomePictures` drops the image; the copied XML still references the base part's relationship id).

- [ ] **Step 9: Implement `rehomePictures` (re-add image data, rewire relationship ids)**

Replace the no-op `rehomePictures` with:

```java
    private void rehomePictures(XWPFHeaderFooter src, XWPFHeaderFooter dst, CTHdrFtr xml) {
        java.util.List<XWPFPictureData> pictures = src.getAllPictures();
        if (pictures.isEmpty()) return;

        String text = xml.xmlText();
        for (XWPFPictureData pic : pictures) {
            String oldId = src.getRelationId(pic);
            String newId;
            try {
                newId = dst.addPictureData(pic.getData(), pic.getPictureType());
            } catch (Exception e) {
                throw new RuntimeException("Failed to copy header/footer image", e);
            }
            if (oldId != null && newId != null) {
                text = text.replace("r:embed=\"" + oldId + "\"", "r:embed=\"" + newId + "\"")
                           .replace("r:id=\"" + oldId + "\"", "r:id=\"" + newId + "\"");
            }
        }
        try {
            CTHdrFtr reparsed = CTHdrFtr.Factory.parse(text);
            xml.set(reparsed);
        } catch (org.apache.xmlbeans.XmlException e) {
            throw new RuntimeException("Failed to rewire header/footer image references", e);
        }
    }
```

> If `CTHdrFtr.Factory.parse(text)` throws a namespace error, swap the string-rewrite for a cursor walk: iterate `xml` with an `XmlCursor`, and for each attribute whose local name is `embed` or `id` in the relationships namespace, replace the matching old id with the new id in place. The test in Step 7 is the contract — make it green.

- [ ] **Step 10: Run test to verify it passes**

Run: `mvn -q -pl rama-spring-core test -Dtest=HeaderFooterMergerTest`
Expected: PASS (all four tests).

- [ ] **Step 11: Commit**

```bash
git add rama-spring-core/src/main/java/org/rama/service/document/template/docx/HeaderFooterMerger.java \
        rama-spring-core/src/test/java/org/rama/service/document/template/docx/HeaderFooterMergerTest.java
git commit -m "feat(print): add HeaderFooterMerger to graft base header/footer + page geometry (#31)"
```

---

## Task 4: Wire the base-template branch into `DocxTemplateProcessor`

**Files:**
- Modify: `rama-spring-core/src/main/java/org/rama/service/document/template/DocxTemplateProcessor.java`
- Test: `rama-spring-core/src/test/java/org/rama/service/document/template/DocxTemplateProcessorBaseTemplateTest.java` (create)

- [ ] **Step 1: Write the failing integration test**

```java
package org.rama.service.document.template;

import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.rama.service.StorageProvider;
import org.rama.service.document.BarcodeService;
import org.rama.service.document.PdfService;
import org.rama.service.document.replacement.ReplacementHooks;
import org.rama.service.document.template.docx.HeaderFooterMerger;
import org.rama.service.document.template.docx.ReplacePlaceholder;
import org.rama.service.document.template.docx.ReplaceSection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocxTemplateProcessorBaseTemplateTest {

    private final PdfService pdfService = mock(PdfService.class);
    private final StorageProvider storage = mock(StorageProvider.class);
    private final BarcodeService barcode = mock(BarcodeService.class);

    private DocxTemplateProcessor processor(BaseTemplateResolver resolver) {
        ReplacementProcessor rp = new ReplacementProcessor(
                new ReplacementHooks(Map.of(), Map.of()), storage);
        ReplacePlaceholder replace = new ReplacePlaceholder(rp, barcode);
        ReplaceSection section = new ReplaceSection(
                "\\{\\{[^\\{\\}]*startsec[^\\{\\}]*\\}\\}",
                "\\{\\{[\\s]*placeholder[^\\{\\}]*endsec[^\\{\\}]*\\}\\}",
                "\\{\\{[\\s]*placeholder[^\\{\\}]*\\}\\}",
                replace);
        return new DocxTemplateProcessor(
                "\\{\\{(.+?)\\}\\}", "RepeatAttribute", "MaximumPages", "BaseTemplate",
                barcode, pdfService, rp, replace, section,
                new HeaderFooterMerger(), resolver);
    }

    /** main template: body {{patientName}}, custom property BaseTemplate=central. */
    private byte[] mainDocx() throws Exception {
        XWPFDocument d = new XWPFDocument();
        d.createParagraph().createRun().setText("Patient: {{patientName}}");
        d.getProperties().getCustomProperties().addProperty("BaseTemplate", "central");
        // an original header that must be discarded
        XWPFHeader h = d.createHeaderFooterPolicy().createHeader(XWPFHeaderFooterPolicy.DEFAULT);
        h.createParagraph().createRun().setText("ORIGINAL HEADER");
        return bytes(d);
    }

    /** base template: header {{hospitalName}}. */
    private InputStream baseDocx() throws Exception {
        XWPFDocument d = new XWPFDocument();
        d.createParagraph().createRun().setText("base body discarded");
        XWPFHeader h = d.createHeaderFooterPolicy().createHeader(XWPFHeaderFooterPolicy.DEFAULT);
        h.createParagraph().createRun().setText("Hospital: {{hospitalName}}");
        return new ByteArrayInputStream(bytes(d));
    }

    private static byte[] bytes(XWPFDocument d) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { d.write(out); return out.toByteArray(); }
    }

    @Test
    void processTemplate_withBaseTemplate_mergesBaseHeaderAndOriginalBody() throws Exception {
        when(pdfService.convertDocxToPdfBytesBlocking(any())).thenReturn("PDF".getBytes());
        BaseTemplateResolver resolver = (code, data) ->
                code.equals("central") ? Optional.of(baseDocxUnchecked()) : Optional.empty();

        Map<String, Object> data = Map.of("patientName", "ALICE", "hospitalName", "RAMA");
        processor(resolver).processTemplate(new ByteArrayInputStream(mainDocx()), data);

        ArgumentCaptor<byte[]> docx = ArgumentCaptor.forClass(byte[].class);
        verify(pdfService).convertDocxToPdfBytesBlocking(docx.capture());
        XWPFDocument rendered = new XWPFDocument(new ByteArrayInputStream(docx.getValue()));

        StringBuilder body = new StringBuilder();
        for (XWPFParagraph p : rendered.getParagraphs()) body.append(p.getText());
        StringBuilder header = new StringBuilder();
        for (XWPFHeader h : rendered.getHeaderList()) header.append(h.getText());

        assertThat(body.toString()).contains("Patient: ALICE");
        assertThat(header.toString()).contains("Hospital: RAMA").doesNotContain("ORIGINAL HEADER");
    }

    @Test
    void processTemplate_whenResolverReturnsEmpty_fallsBackToNormalProcessing() throws Exception {
        when(pdfService.convertDocxToPdfBytesBlocking(any())).thenReturn("PDF".getBytes());
        BaseTemplateResolver empty = (code, data) -> Optional.empty();

        byte[] result = processor(empty)
                .processTemplate(new ByteArrayInputStream(mainDocx()), Map.of("patientName", "BOB"));

        assertThat(result).isEqualTo("PDF".getBytes());
        ArgumentCaptor<byte[]> docx = ArgumentCaptor.forClass(byte[].class);
        verify(pdfService).convertDocxToPdfBytesBlocking(docx.capture());
        XWPFDocument rendered = new XWPFDocument(new ByteArrayInputStream(docx.getValue()));
        StringBuilder header = new StringBuilder();
        for (XWPFHeader h : rendered.getHeaderList()) header.append(h.getText());
        // normal path keeps the original header (no base merge)
        assertThat(header.toString()).contains("ORIGINAL HEADER");
    }

    private InputStream baseDocxUnchecked() {
        try { return baseDocx(); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl rama-spring-core test -Dtest=DocxTemplateProcessorBaseTemplateTest`
Expected: FAIL — the `DocxTemplateProcessor` constructor with `baseTemplateProperty`, `HeaderFooterMerger`, `BaseTemplateResolver` does not exist (compile error).

- [ ] **Step 3: Extend the `DocxTemplateProcessor` constructor and fields**

In `DocxTemplateProcessor.java`, add imports near the top:

```java
import org.rama.service.document.template.docx.HeaderFooterMerger;
```

Add fields after line 38 (`private final ReplaceSection replaceSection;`):

```java
    private final String baseTemplateProperty;
    private final HeaderFooterMerger headerFooterMerger;
    private final BaseTemplateResolver baseTemplateResolver;
```

Replace the constructor (lines 40-58) so it accepts the three new args (keep all existing params, add the new ones at the positions used by the test — `baseTemplateProperty` after `maximumPagesProperty`, and `headerFooterMerger` + `baseTemplateResolver` last):

```java
    public DocxTemplateProcessor(
            String placeholderPattern,
            String repeatAttributeProperty,
            String maximumPagesProperty,
            String baseTemplateProperty,
            BarcodeService barcodeService,
            PdfService pdfService,
            ReplacementProcessor replacementProcessor,
            ReplacePlaceholder replacePlaceholder,
            ReplaceSection replaceSection,
            HeaderFooterMerger headerFooterMerger,
            BaseTemplateResolver baseTemplateResolver
    ) {
        this.compiledPattern = Pattern.compile(placeholderPattern);
        this.repeatAttributeProperty = repeatAttributeProperty;
        this.maximumPagesProperty = maximumPagesProperty;
        this.baseTemplateProperty = baseTemplateProperty;
        this.barcodeService = barcodeService;
        this.pdfService = pdfService;
        this.replacementProcessor = replacementProcessor;
        this.replacePlaceholder = replacePlaceholder;
        this.replaceSection = replaceSection;
        this.headerFooterMerger = headerFooterMerger;
        this.baseTemplateResolver = baseTemplateResolver;
    }
```

- [ ] **Step 4: Add `readBaseTemplate` and the base-template render path**

Add this helper after `readMaximumPages` (after line 121):

```java
    private String readBaseTemplate(POIXMLProperties.CustomProperties customProperties) {
        if (customProperties == null) return null;
        if (!customProperties.contains(baseTemplateProperty)) return null;
        var p = customProperties.getProperty(baseTemplateProperty);
        try {
            if (p.isSetLpwstr()) {
                String v = p.getLpwstr();
                return (v != null && !v.isBlank()) ? v.trim() : null;
            }
        } catch (Exception ignore) { }
        return null;
    }

    private byte[] processWithBaseTemplate(byte[] mainContent, InputStream baseStream,
                                           Map<String, Object> replacements, int maximumPages) throws IOException {
        try (XWPFDocument baseDoc = new XWPFDocument(baseStream);
             XWPFDocument mainDoc = new XWPFDocument(new ByteArrayInputStream(mainContent))) {

            replaceHeadersFooters(baseDoc, replacements);  // base: header/footer placeholders only
            replaceBody(mainDoc, replacements);            // original: body placeholders only
            headerFooterMerger.apply(mainDoc, baseDoc);    // graft base header/footer + layout

            byte[] docxBytes;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                mainDoc.write(out);
                docxBytes = out.toByteArray();
            }
            byte[] pdfBytes = pdfService.convertDocxToPdfBytesBlocking(docxBytes);
            return (maximumPages > 0) ? pdfService.trimPdfBytesBlocking(pdfBytes, maximumPages) : pdfBytes;
        }
    }
```

Refactor `processDocument` (lines 139-151) into three methods so the body and headers/footers can be replaced independently:

```java
    private void processDocument(XWPFDocument document, Map<String, Object> replacements) {
        replaceBody(document, replacements);
        replaceHeadersFooters(document, replacements);
    }

    private void replaceBody(XWPFDocument document, Map<String, Object> replacements) {
        replacePatternInBody(document, compiledPattern, replacements);
    }

    private void replaceHeadersFooters(XWPFDocument document, Map<String, Object> replacements) {
        for (XWPFHeader header : document.getHeaderList()) {
            replacePatternInBody(header, compiledPattern, replacements);
        }
        for (XWPFFooter footer : document.getFooterList()) {
            replacePatternInBody(footer, compiledPattern, replacements);
        }
    }
```

- [ ] **Step 5: Branch into the base path inside `processTemplate`**

In `processTemplate`, immediately after `int maximumPages = readMaximumPages(customProperties);` (line 78), insert the base-template detection BEFORE the repeat-mode block:

```java
                String baseTemplateCode = readBaseTemplate(customProperties);
                if (baseTemplateCode != null) {
                    Optional<InputStream> baseStream = Optional.empty();
                    try {
                        baseStream = baseTemplateResolver.resolve(baseTemplateCode, replacements);
                    } catch (Exception e) {
                        log.warn("BaseTemplate '{}' resolution failed; rendering without base template", baseTemplateCode, e);
                    }
                    if (baseStream.isPresent()) {
                        // Compose with repeat mode: render each item through the base path.
                        if (customProperties.contains(repeatAttributeProperty)
                                && customProperties.getProperty(repeatAttributeProperty).isSetLpwstr()) {
                            String repeatAttribute = customProperties.getProperty(repeatAttributeProperty).getLpwstr();
                            Object value = (replacements != null) ? replacements.get(repeatAttribute) : null;
                            if (value instanceof Collection<?> collection) {
                                byte[] baseBytes = baseStream.get().readAllBytes();
                                List<byte[]> pdfs = new ArrayList<>(collection.size());
                                for (Object item : collection) {
                                    replacements.put(repeatAttribute + "Item", item);
                                    pdfs.add(processWithBaseTemplate(originalContent,
                                            new ByteArrayInputStream(baseBytes), replacements, maximumPages));
                                }
                                return pdfService.mergePdfBytesBlocking(pdfs);
                            }
                        }
                        return processWithBaseTemplate(originalContent, baseStream.get(), replacements, maximumPages);
                    }
                    // not found -> fall through to normal processing
                }
```

(`originalContent`, `customProperties`, `Collection`, `ArrayList`, `List`, `Optional`, `InputStream`, `ByteArrayInputStream` are all already in scope / imported in this file.)

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q -pl rama-spring-core test -Dtest=DocxTemplateProcessorBaseTemplateTest`
Expected: PASS (both tests).

- [ ] **Step 7: Run the full core module to confirm nothing else broke**

Run: `mvn -q -pl rama-spring-core test`
Expected: PASS (existing document tests still green; the normal path is unchanged).

- [ ] **Step 8: Commit**

```bash
git add rama-spring-core/src/main/java/org/rama/service/document/template/DocxTemplateProcessor.java \
        rama-spring-core/src/test/java/org/rama/service/document/template/DocxTemplateProcessorBaseTemplateTest.java
git commit -m "feat(print): render via BaseTemplate when the docx property is set (#31)"
```

---

## Task 5: Auto-configuration wiring

**Files:**
- Modify: `rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java`

- [ ] **Step 1: Add imports**

After line 84 (`import org.rama.service.document.template.docx.ReplaceSection;`) add:

```java
import org.rama.service.document.template.BaseTemplateResolver;
import org.rama.service.document.template.docx.HeaderFooterMerger;
import java.util.Optional;
```

(`java.util.Optional` only if not already imported — check the existing import block first.)

- [ ] **Step 2: Add the no-op resolver bean and the merger bean**

Insert just before the `docxTemplateProcessor` bean (before line 481):

```java
    @Bean
    @ConditionalOnMissingBean(BaseTemplateResolver.class)
    BaseTemplateResolver baseTemplateResolver() {
        return (templateCode, replacements) -> Optional.empty();
    }

    @Bean
    @ConditionalOnMissingBean
    HeaderFooterMerger headerFooterMerger() {
        return new HeaderFooterMerger();
    }
```

- [ ] **Step 3: Update the `docxTemplateProcessor` bean to inject the new collaborators**

Replace the `docxTemplateProcessor` bean (lines 481-502) with:

```java
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({PdfService.class, ReplacementProcessor.class, BarcodeService.class, ReplacePlaceholder.class, ReplaceSection.class})
    DocxTemplateProcessor docxTemplateProcessor(
            DocumentProperties documentProperties,
            BarcodeService barcodeService,
            PdfService pdfService,
            ReplacementProcessor replacementProcessor,
            ReplacePlaceholder replacePlaceholder,
            ReplaceSection replaceSection,
            HeaderFooterMerger headerFooterMerger,
            BaseTemplateResolver baseTemplateResolver
    ) {
        return new DocxTemplateProcessor(
                documentProperties.getPlaceholderPattern(),
                documentProperties.getRepeatAttributeProperty(),
                documentProperties.getMaximumPagesProperty(),
                documentProperties.getBaseTemplateProperty(),
                barcodeService,
                pdfService,
                replacementProcessor,
                replacePlaceholder,
                replaceSection,
                headerFooterMerger,
                baseTemplateResolver
        );
    }
```

- [ ] **Step 4: Compile the autoconfigure module**

Run: `mvn -q -pl rama-spring-autoconfigure -am -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Build + test the whole project to confirm context wiring**

Run: `mvn -q -DskipTests verify` then `mvn -q -pl rama-spring-core test`
Expected: BUILD SUCCESS / tests PASS. (If the demo module has a `@SpringBootTest`, it will fail fast on a wiring error — watch for `BaseTemplateResolver` / `HeaderFooterMerger` bean errors.)

- [ ] **Step 6: Commit**

```bash
git add rama-spring-autoconfigure/src/main/java/org/rama/autoconfigure/RamaStarterAutoConfiguration.java
git commit -m "feat(print): auto-configure BaseTemplateResolver (no-op) + HeaderFooterMerger (#31)"
```

---

## Task 6: Document the feature

**Files:**
- Modify: `CLAUDE.md` (repo root — the "Connection/service properties" / `document.*` area)

- [ ] **Step 1: Add a short note to `CLAUDE.md`**

Under the `document.*` documentation, add:

```markdown
- `document.base-template-property` -- name of the docx custom property that names a centralized base template (default `BaseTemplate`). When a template sets this property to a `template_code`, the print flow renders the original template's **body** inside the **header/footer + page layout** of the resolved base template. Resolution is delegated to a consumer-provided `BaseTemplateResolver` bean (the starter ships a no-op default, so the feature is off until a consumer wires its `TemplateResolver`). The resolver must return an already-preprocessed docx stream, so the `docx-template-cache$` cache is reused unchanged. One nesting level only.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(print): document BaseTemplate centralized header/footer property (#31)"
```

---

## Task 7: Push and open the MR

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/31-base-template-header-footer
```

- [ ] **Step 2: Open the MR against `main`** (test plan as checkboxes, cross-link issue #31)

```bash
glab mr create --source-branch feat/31-base-template-header-footer --target-branch main \
  --title "feat(print): centralized header/footer via BaseTemplate docx property" \
  --description "Closes #31

## What
Render a per-document template's body inside a centralized base template's header/footer + page layout, selected by a \`BaseTemplate\` docx custom property.

## How
- New \`BaseTemplateResolver\` SPI (no-op default) resolves the code to an already-preprocessed docx stream — resolution stays in the consumer; cache reused.
- \`DocxTemplateProcessor\` reads \`BaseTemplate\`, replaces body vs header/footer placeholders separately, and \`HeaderFooterMerger\` grafts base header/footer + page geometry onto the original document.

## Test plan
- [ ] \`DocumentPropertiesTest\` — default property name
- [ ] \`HeaderFooterMergerTest\` — header/footer graft, page geometry, image survival
- [ ] \`DocxTemplateProcessorBaseTemplateTest\` — base merge + fallback when resolver empty
- [ ] \`mvn -pl rama-spring-core test\` green
- [ ] \`mvn -DskipTests verify\` green
- [ ] Caching unaffected: base resolution reuses \`docx-template-cache\$\`; merge mutates only in-memory clones"
```

---

## Self-review notes

- **Spec coverage:** property read (Task 4) · SPI + no-op default (Tasks 2, 5) · merge approach B (Task 3) · body-only/header-footer-only replacement (Task 4) · repeat-mode composition (Task 4 Step 5) · caching invariant (reused path, asserted via fallback test) · error fallback (Task 4 Step 5 try/catch) · one-level nesting (base body discarded, base's own `BaseTemplate` never read because only headers/footers of the base are processed) · tests (Tasks 3, 4). All covered.
- **Type consistency:** constructor arg order (`...maximumPagesProperty, baseTemplateProperty, barcode, pdf, rp, replace, section, headerFooterMerger, baseTemplateResolver`) is identical in the interface use (Task 4 Step 3), the test (Task 4 Step 1), and the bean (Task 5 Step 3). Method names `replaceBody` / `replaceHeadersFooters` / `readBaseTemplate` / `processWithBaseTemplate` / `HeaderFooterMerger.apply` are consistent across tasks.
- **POI version caveat:** header/footer API names (`createHeaderFooterPolicy`, `XWPFHeaderFooterPolicy.DEFAULT/FIRST/EVEN`, `createHeader(HeaderFooterType)`, `getAllPictures`, `getRelationId`, `addPictureData`) should be verified against the `poi-ooxml` version on the classpath during Task 3; tests are the contract and the implementer adjusts to green.
