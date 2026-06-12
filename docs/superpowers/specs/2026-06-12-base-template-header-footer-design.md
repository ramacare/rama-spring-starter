# Centralized header/footer via `BaseTemplate` docx property

- **Issue:** [#31](https://gitlab.rama.mahidol.ac.th/ramacare/backend/rama-spring-starter/-/work_items/31)
- **Date:** 2026-06-12
- **Module:** `rama-spring-core` (+ `rama-spring-autoconfigure` wiring)

## Problem

The DOCX print pipeline reads a per-document `.docx` template, replaces `{{...}}`
placeholders in the body/headers/footers, and converts it to PDF via Gotenberg.
Headers and footers (hospital logo, address, page numbers, verification QR) are
duplicated across every template. We want to **centrally control the header and
footer** from a single shared template, without re-authoring every document
template.

## Solution overview

A document template may declare a **base template** via a docx custom property
`BaseTemplate` (string, holding a `template_code`). When present, the print flow
adds one extra step:

1. Resolve `BaseTemplate` as a `template_code` (DB `document_template` +
   classpath `documents/templates/<code>.docx`). If it resolves to nothing,
   **process the original template normally** (feature is a no-op).
2. Use the base template's **header + footer** (with their placeholders
   replaced) and its **page layout**; discard the base template's body.
3. Replace placeholders in the **original template's body** and combine it with
   the base's header/footer.
4. Return the resulting PDF.

Net result document = *original body* + *base header/footer* + *base page layout*.

## Key architectural fact

The starter's `DocxTemplateProcessor.processTemplate(InputStream, Map)` is
**template-code-agnostic** — it only ever sees an `InputStream`. Resolution of a
`template_code` to docx bytes (the DB `document_template` table and the
`classpath:documents/templates/<code>.docx` fallback) lives in the **consumer
app's `TemplateResolver` / `PrintService`**, not in the starter. The starter has
no `document_template` entity and must not grow one.

Therefore the starter cannot resolve `BaseTemplate` itself. It exposes a small
**SPI** that the consumer implements by wrapping its existing resolver.

## Design decisions (locked during brainstorming)

| Decision | Choice |
|---|---|
| Where resolution lives | **SPI resolver bean** in the starter; consumer wires its existing `TemplateResolver`. No-op default so behavior is unchanged when absent. |
| Merge semantics | Base provides **header/footer + page layout**; original provides **body**. Base body discarded; original's own headers/footers discarded. |
| Container document (POI) | **Original doc is the container**; base's header/footer + page geometry are grafted onto it (approach B). Same visual output as the literal "base as container", far lower relationship-corruption risk because the body never moves across documents. |
| Property key | Custom property `BaseTemplate` (string / `Lpwstr`), PascalCase to match `MaximumPages` / `RepeatAttribute`. Configurable via `DocumentProperties`. |
| SPI return type | **Already-preprocessed** docx `InputStream` — reuses the consumer's existing resolve→preprocess→cache path, so the `docx-template-cache$` MD5 cache is unchanged. |
| Nesting | **One level only**. A `BaseTemplate` property on a base template is ignored. No cycle handling needed. |
| Not found / error | Fall back to normal processing, log a warning. |

## Components

### New: `BaseTemplateResolver` (SPI)

`org.rama.service.document.template.BaseTemplateResolver`

```java
public interface BaseTemplateResolver {
    /**
     * Resolve a base template code to a print-ready (already preprocessed) docx
     * stream, or empty if no template is found for the code.
     */
    Optional<InputStream> resolve(String templateCode, Map<String, Object> replacements);
}
```

- The `replacements` map is passed so a consumer resolver may select a variant
  per data (parity with the existing `TemplateResolver.resolve(code, data)`).
- The returned stream is **already preprocessed** (SDT→placeholder, run merge) —
  the consumer obtains it through the same `TemplatePreprocessor` path it uses
  for main templates, so it lands in the shared MD5 cache.

### New: no-op default bean

In `RamaStarterAutoConfiguration`:

```java
@Bean
@ConditionalOnMissingBean(BaseTemplateResolver.class)
BaseTemplateResolver baseTemplateResolver() {
    return (code, data) -> Optional.empty();   // feature off until consumer provides one
}
```

`Optional.empty()` ⇒ the pipeline runs exactly as today. Consumers opt in by
declaring their own `BaseTemplateResolver` bean.

### New: `HeaderFooterMerger` (POI plumbing — core risk)

`org.rama.service.document.template.docx.HeaderFooterMerger`

```java
void apply(XWPFDocument target, XWPFDocument base);
```

Responsibilities:
- Remove `target`'s existing header/footer parts and their `sectPr` references.
- Copy each of `base`'s header/footer parts into `target`, **including embedded
  images** — add each relationship to the target part and rewire `r:embed` /
  `r:id` references to the new relationship IDs.
- Copy `base`'s page geometry into `target`'s final `sectPr`: `pgSz`, `pgMar`,
  and the header/footer references (`headerReference` / `footerReference`,
  including first-page / even-page variants).

This is the highest-risk unit. It is built test-first against a fixture `.docx`
that has a header logo image, a footer page number, and non-default margins.

### Modified: `DocxTemplateProcessor`

- Inject `BaseTemplateResolver` (new constructor arg) and `baseTemplateProperty`
  (new constructor arg).
- `readBaseTemplate(customProperties)` — reads the `BaseTemplate` `Lpwstr`
  custom property (mirrors `readMaximumPages`); returns `null` when absent or not
  a string.
- Factor placeholder replacement so it can run on a subset:
  - `replaceBody(doc, data)` — body + tables + text boxes only.
  - `replaceHeadersFooters(doc, data)` — all headers + footers only.
  - existing `processDocument` = both (unchanged for the normal path).
- New private path `processWithBaseTemplate(mainBytes, baseStream, data, maxPages)`:
  1. `baseDoc = open(baseStream)` (already preprocessed),
     `replaceHeadersFooters(baseDoc, data)`.
  2. `mainDoc = open(mainBytes)`, `replaceBody(mainDoc, data)`.
  3. `headerFooterMerger.apply(mainDoc, baseDoc)`.
  4. `mainDoc → docx bytes → PdfService → trim(maxPages)`.
- Composition with **repeat mode**: when both `BaseTemplate` and
  `RepeatAttribute` are set, each repeat iteration is rendered through
  `processWithBaseTemplate` and the PDFs are merged as today.

### Modified: `DocumentProperties`

Add `baseTemplateProperty` (default `"BaseTemplate"`), exposed like the existing
properties and threaded into the `DocxTemplateProcessor` bean.

### Modified: `RamaStarterAutoConfiguration`

- Add the no-op `BaseTemplateResolver` bean.
- Add the `HeaderFooterMerger` bean.
- Update the `DocxTemplateProcessor` bean definition to inject the resolver,
  `HeaderFooterMerger`, and `baseTemplateProperty`.

## Data flow

```
processTemplate(mainStream, data):
  bytes        = read(mainStream)
  mainDoc      = open(bytes)
  customProps  = mainDoc.customProperties
  maximumPages = readMaximumPages(customProps)        // unchanged
  baseCode     = readBaseTemplate(customProps)        // NEW
  base         = baseCode != null
                   ? baseTemplateResolver.resolve(baseCode, data)
                   : Optional.empty()

  if base.isPresent():                                // NEW path
     return processWithBaseTemplate(bytes, base.get(), data, maximumPages)
                                                       // composes with repeat mode
  else:
     ... existing repeat / single-document flow, fully unchanged ...
```

## Caching invariant (explicit requirement)

The template cache must not break. It does not, because:

- Base resolution reuses the consumer's **existing** resolve →
  `DocxTemplatePreprocessor` → `docx-template-cache$` (MD5 keyed) path. The
  starter adds **no new cache and no new cache key**; base templates are ordinary
  entries in the same bucket.
- The SPI returns already-preprocessed bytes, so preprocessing/caching happens
  exactly once, in the same place as today.
- The merge mutates only **in-memory cloned `XWPFDocument`s**. Cached
  preprocessed bytes are read, never written, during a print.

## Error handling

- Resolver returns empty, throws, or the base docx is unreadable ⇒ **log warn,
  fall back to normal processing** ("if not found, process the template like
  normal").
- `BaseTemplate` present on the base template itself ⇒ ignored (one level only).
- Base template has no header/footer ⇒ result has none (base controls them; if it
  defines none, there are none).

## Testing

- **Unit**
  - `readBaseTemplate`: present (`Lpwstr`), missing, wrong type → `null`.
  - No-op `BaseTemplateResolver` returns `Optional.empty()`.
  - `HeaderFooterMerger.apply`: grafts header text + footer page number, carries
    the header logo image (relationship rewired, image bytes intact), copies page
    geometry (`pgSz`/`pgMar`), against a fixture `.docx`.
- **Integration**
  - `processTemplate` with a stub `BaseTemplateResolver` bean and a mocked
    `PdfService`: assert the merged docx contains the **base's** header/footer and
    the **original's** body.
  - Assert fallback to normal processing when the resolver returns empty.
  - Assert input template bytes / cached bytes are not mutated by a print.

## Out of scope

- Adding a `document_template` entity or any template-code resolution to the
  starter (stays in the consumer).
- Centralizing body styles/numbering from the base template (only header/footer +
  page layout are centralized).
- Multi-level base-template nesting and cycle detection.
