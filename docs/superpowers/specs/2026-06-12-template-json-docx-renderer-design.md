# Document Template JSON → docx body renderer (design)

Issue: #32 · Branch: `feat/32-template-json-docx-renderer`

## Problem

The print stack renders a hand-authored `.docx`: `DocxTemplateProcessor` finds
`{{placeholder}}`s, fills them from a `Map<String,Object>` data object, applies
**hooks via placeholder attributes** (`;date`, `;time`, `;datetime`, `;master`,
`;checkbox`, `;join`, `;image`, plus `{{key;startsec}}…{{key;endsec}}` section
repeats), and converts to PDF through Gotenberg.

`rama-modules` defines a **document template JSON** format — an array of
`DocumentTemplateItem` objects (see `../rama-modules/docs/document-template-json-spec.md`)
— used to build Vue forms. We want the server-side, inverse-direction equivalent:
turn that template JSON (available in Java as `List<Map<String,Object>>`) into a
**docx body**, so forms can be printed without hand-authoring a docx per template.

## Approach

A new `DocumentTemplateJsonRenderer` **appends** body content into an
`XWPFDocument`, emitting **labels + `{{placeholder}}`s carrying the right hook
attributes**. It does **not** read the data object and does **not** run hooks.

This is the key decision: the renderer produces the *same placeholder docx* a human
would author. Everything downstream — data-filling, hooks, section expansion,
BaseTemplate header/footer merge, PDF conversion — reuses the existing pipeline
unchanged. The generated body depends only on the template JSON, so it is
**cacheable per template code/version** (a later issue).

Implementation is direct Apache POI XWPF emission (already the stack; no new
dependency). A docx templating library or string-compilation approach is rejected.

## Component

`org.rama.service.document.template.json.DocumentTemplateJsonRenderer`

```java
/** Appends rendered body content into doc. templateJson is a List<Map<String,Object>>
 *  (the DocumentTemplateItem array) or its JSON string. */
void render(XWPFDocument doc, Object templateJson)
```

Internals are organised around small helpers (one responsibility each):
- **item-array walk + grid packing** — group items into rows, flush each row as a table
- **field cell emit** — label paragraph + value-placeholder paragraph
- **placeholder builder** — `inputType` → `{{var…}}` with hook attributes
- **table emit** — `FormTable`/`FormTableData` → bordered table with section markers
- **nested recurse** — `DocumentForm` via `TemplateJsonResolver`

### Grid layout (mirrors Vuetify v-row/v-col)

Walk the item array, accumulating fields into a row while the running sum of
`width` (default `12` when absent) stays ≤ 12; when the next field would exceed 12,
flush the accumulated row. Each flushed row is one **borderless `XWPFTable`** whose
cell widths track `width/12` of the page width.

- `Separator` → flush current row (row break), emits nothing itself.
- `Header` → its own full-width row: a bold, larger paragraph of `inputLabel`.
- `FormTable` / `FormTableData` / `DocumentForm` → each its own full-width row.

### Field cell content

Each field cell holds a **label paragraph (bold)** above a **value paragraph**
containing the placeholder. (`label: value` inline is a trivial later toggle; v1 is
label-above-value.)

### inputType → placeholder + hook mapping

| inputType | Emitted into the value paragraph |
|---|---|
| `VTextField`, `VTextarea`, `VSelect`, `VCombobox`, `VAutocomplete`, `VRadio`, `VRadioInline` | `{{var}}` |
| `FormDate` | `{{var;date}}` |
| `FormTime` | `{{var;time}}` |
| `FormDateTime` | `{{var;datetime}}` |
| `MasterAutocomplete` | `{{var;master;groupKey=<inputOptions>}}` |
| `VCheckbox`, `VSwitch` | `{{var;checkbox}}` (→ ☒ / ☐) |
| `FormCheckboxGroup` | `{{var;join}}` (array → joined) |
| `FormSignPad` | `{{var;image}}` |
| `FormFile` | `{{var;image}}` when `inputAttributes` `accept` is image-typed or absent; otherwise the file name(s): `{{var.originalFileName}}`, or `{{var;join=originalFileName}}` when `multiple` |
| `Header` | bold styled paragraph of `inputLabel` (no placeholder) |
| `Separator` | row break |
| `FormTable` / `FormTableData` | bordered table (see below) |
| `DocumentForm` | resolve `inputOptions` (child code) via `TemplateJsonResolver`, recurse one level |
| `CustomCode` | best-effort plain-text paragraph of `inputCustomCode` |
| unknown | plain `label + {{var}}` |

`var` is `variableName` (sanitised). Where `variableName` is missing for a value
field, the field is skipped (label-only) with a debug log.

### `printConfig` — author-supplied docx hook attributes

Some docx hook config can't be inferred from a Vue form template (image `width`,
date `format`/`locale`, `prefix`/`suffix`, forcing `qrcode`/`barcode`, …). A new
**`printConfig`** field on the template item carries these and is appended to the
emitted placeholder's attributes:

- object map — `{"width": 2}` → `;width=2`; a boolean-`true`/blank value → a bare
  flag (`{"qrcode": true}` → `;qrcode`). Entry order is preserved.
- raw string — `"format=dd/MM/yyyy"` → `;format=dd/MM/yyyy`.

So `FormSignPad` + `printConfig {"width": 2}` → `{{sig;image;width=2}}`, and any field
can be promoted to e.g. a QR code via `printConfig {"qrcode": true, "width": 1}`.

This field is **print-target-only** (the Vue renderer ignores it). It must be added
to the `rama-modules` template-JSON spec/types/builder — tracked as a separate issue
in that repo.

### FormTable / FormTableData

Render a bordered `XWPFTable`:
- Header row from `inputOptions.headers` (`title`/`key`), falling back to the row
  template's field labels when `headers` is absent.
- One body template row whose first cell opens the section with `{{var;startsec}}`
  and last cell closes it with `{{var;endsec}}`; each column cell carries
  `{{var.<field>}}` for the matching row field. `ReplaceSection` then duplicates the
  row per element of `data[var]` and rewrites `var.<field>` → `var[i].<field>`.
- Row fields come from `formTemplate` (FormTable) / `dataTemplate` (FormTableData).
  Each row field's placeholder still uses its own hook attributes (a `FormDate`
  column → `{{var.<field>;date}}`).

### TemplateJsonResolver SPI

```java
@FunctionalInterface
public interface TemplateJsonResolver {
    Optional<List<Map<String, Object>>> resolve(String templateCode);
}
```

Default no-op bean (`Optional.empty()`) registered via `@ConditionalOnMissingBean`,
mirroring the existing `BaseTemplateResolver` pattern. `DocumentForm` renders nothing
when the code does not resolve. Real resolution is the "template" follow-up.

## Auto-configuration

Register two beans in autoconfigure (both `@ConditionalOnMissingBean`):
- `DocumentTemplateJsonRenderer`
- no-op `TemplateJsonResolver`

v1 does **not** wire the renderer into `PrintService` / `DocxTemplateProcessor`.

## Testing

JUnit 5, `@Tag("unit")`. Build template-JSON arrays, render into a fresh
`XWPFDocument`, assert structure by reading paragraph/cell text back:
- field row packing (widths 6+6 → one table, two cells; 8+8 → two rows)
- exact placeholder strings per type (`{{birthDate;date}}`, `{{dept;master;groupKey=...}}`, `{{agree;checkbox}}`, `{{tags;join}}`)
- `Header` → bold paragraph, no placeholder; `Separator` → row break
- `FormTable` → bordered table with `;startsec`/`;endsec` markers and `{{var.field}}` cells
- `DocumentForm` with a stub resolver → recursed child fields; with no-op resolver → nothing
- unknown type → plain `label + {{var}}`
- JSON-string input parses the same as a `List`

## Out of scope (separate follow-up issues)

1. Wire renderer into the print flow (`PrintService` / `DocxTemplateProcessor`).
2. Persist template JSON on the template/document.
3. Cache the generated docx body, keyed by template code/version.
