# NuaForma DOCX

Development-stage DOCX generation and round-trip processing for Kotlin and Java 17. The implementation edits an isolated native Word model, not a document rebuilt from extracted text. All docx4j and JAXB types remain under `dev.nuaforma.docx.adapters.docx4j`; consumers see only NuaForma's facade and contract types.

The registered `DocxProvider` reads DOCX and writes DOCX using the [restricted template language](../nuaforma-template/README.md). New watermarks, supplied-font embedding, PDF conversion and REST endpoints are not implemented. Unsupported explicit options and resource injection fail rather than being ignored. `DocxDocuments.open` remains a round-trip facade: it does not evaluate placeholders.

## Generation through the engine

Add `nuaforma-core` and `nuaforma-docx` to the application runtime. Discovery reads the static provider descriptor before instantiation; core has no concrete-provider dependency.

```kotlin
val engine = ProviderRegistry.discover()
val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))
engine.generate(
    GenerationRequest(templateInput, requirements, mapOf("customer" to mapOf("name" to "Ada"))),
    output,
    GenerationContext(),
    "nuaforma-docx",
)
```

Import `dev.nuaforma.core.ProviderRegistry` and the `dev.nuaforma.contracts` request types. `DocxProvider` can also be used directly through `DocumentProvider`, without core. Generation closes only its own model and temporary files, not the supplied streams.

## Word template rules

Placeholders and inline conditions/repetitions work across adjacent ordinary text runs in a paragraph or hyperlink. Replacement text inherits the opening marker's run properties; retained literal text keeps its original properties. Repeated inline fragments are emitted in evaluation order, including mixed bold/italic runs. Inserted values remain literal and are never evaluated again. Tabs, breaks, drawings, fields and range boundaries are barriers: a marker cannot span them. Text within a mixed text/control run may be replaced within each adjacent text span without moving its other objects.

For structural conditions or repeats, place each opening, alternative and closing marker alone in its own text-only paragraph. Markers must open and close within the same container. They can surround paragraphs or complete tables, and may nest. A table-row marker uses a dedicated row with one cell containing exactly one marker paragraph; merge that cell across the table's columns in the template editor when necessary. Actual repeated rows retain their own cell formatting. Row markers cannot be ordinary populated data rows.

```text
{#each groups as group}
Group {group.name}
    [table row: {#each group.items as item}]
    [table row: {group.name} | {item} | {loop.number}]
    [table row: {/each}]
{/each}
```

Deleting all contents of a cell leaves a required empty paragraph. Removing every row from a table fails; wrap the entire table in a paragraph-level condition instead. Block marker paragraphs cannot carry section breaks. Dynamic elements must contain complete bookmark ranges; bookmarks spanning distinct dynamic paragraphs/rows fail. Dynamic bookmarks with incoming links from outside their element, or in documents containing bookmark-reference fields, are rejected to avoid detached references. Repeated bookmark names/IDs, paragraph/drawing extension IDs, drawing IDs and supported VML shape IDs are renewed; existing same-part image relationships are reused. Dynamic annotation/permission ranges, section breaks and vertical merges, and repeated fields, note references, tracked changes and content controls are rejected rather than blindly copied. Only basic VML `CTShape` objects without Office-specific `spid` identifiers can be repeated yet.

Traversal includes body, headers, footers and supported legacy VML and modern DrawingML text boxes, including the rotated margin reference. It does not evaluate document metadata, field instructions, comments or footnotes/endnotes. Unsupported Word extensions are not a promise of arbitrary template support. Font-based barcodes remain text; this milestone does not establish barcode encoding, availability or scanability.

All supported syntax is validated before generation, including unselected branches. One template session shares local scopes and cumulative expression/output/repetition budgets across the document. Structural markers have the same 4096-character ceiling as inline markers. DOCX traversal additionally has conservative ceilings of 128 structure levels and 1,000,000 processing visits across validation and expansion. No consumer business schema or executable expression language is introduced.

## Embedded use

```kotlin
val context = GenerationContext()
templateInput.use { input ->
    DocxDocuments.open(input, context).use { document ->
        document.writeTo(output)
    }
}
```

```java
try (DocxDocument document = DocxDocuments.open(templateInput)) {
    document.writeTo(output);
}
```

Import `dev.nuaforma.docx.DocxDocument` / `DocxDocuments` and, when specifying limits, `dev.nuaforma.contracts.GenerationContext` / `GenerationLimits`. The examples do not close `output`; its owner must do so. The facade never closes caller streams. Closing the document releases its model and is idempotent; subsequent writes fail. Each opened document has independent state; operations on the same document are serialized. The context supplied at open governs the entire lifetime, including later writes, so it is not a reusable compiled-template cache.

## Input and failure policy

The initial profile accepts unencrypted transitional DOCX ZIP packages with an internal main part at `word/document.xml`. Strict OOXML, legacy DOC, spreadsheets, macros, embedded objects, signed packages, HTML imports, attached templates and external relationships other than passive hyperlinks are rejected, not stripped or converted. Hyperlinks are retained as metadata and never fetched by this adapter.

The original ZIP is checked before docx4j loads it: entry count, safe unique paths, expanded bytes, CRC/size integrity, content types, relationship targets and XML complexity. XML DTDs and entities are prohibited. Known JAXB parts use a per-part unmarshaller that fails on binding errors instead of docx4j's tolerant preprocessing fallback. This is not full ECMA-376 schema validation or a guarantee that every Word extension can be preserved; unsupported structured content can fail explicitly.

Template, expanded and output byte limits, archive entry limits and cancellation/deadline checks apply in embedded use. XML is limited to 256 nested elements and 256 attributes per element. Limits and checks are cooperative; they do not forcibly interrupt blocking caller I/O or every internal library operation and are not a process/heap sandbox. Hosted isolation remains future work.

Reading and serialization use isolated temporary files, removed in `finally` blocks. The mutable model and backing package bytes remain in memory until close. Serialization and output-package validation finish before copying to the caller's output stream. An I/O failure or cancellation during that final copy can still leave partial bytes; use caller-owned staging storage for atomic publication. Failures use sanitized `GenerationException` diagnostics; caller I/O errors propagate as `IOException`. No logging backend or host-wide XML/library configuration is installed.

## Verification

```sh
./gradlew :nuaforma-docx:test
./gradlew :nuaforma-server:test --tests 'dev.nuaforma.architecture.*'
```

`SyntheticDocx` independently authors a deterministic ZIP/OOXML reference without docx4j. Tests compare every package part: namespace-aware XML structure for rewritten XML and byte equality for media. Only documented semantic equivalents are normalized: unordered OPC/property declarations, the style-default and DrawingML text-box boolean lexical forms, and empty `mc:Ignorable`. Word paragraph/run order, text, properties, IDs and relationship targets remain significant.

The reference covers styles, mixed run formatting, a split placeholder, bookmarks, numbering, tables, an image, passive hyperlinks, page fields, headers/footers, portrait/landscape section geometry and text boxes containing placeholders, including a rotated margin box. Both legacy VML and modern DrawingML text boxes must retain their structure. Generated reference files live in ignored `nuaforma-docx/build/docx-reference`, not in production artifacts.

Run visual comparison only after the build has finished, so the renderer cannot lock files while the tests regenerate them. A local renderer is an optional verification tool, not a production or CI dependency. Record the renderer/version and compare every page before claiming visible preservation. XML checks alone do not prove pagination, font fidelity or barcode scanability. Generation tests also exercise split-run replacements, nested conditions/repeats, row repeats, safe identifiers, empty cells, invalid branches, shared budgets and provider metadata. The server test runtime checks real generation through core, including concurrent request isolation. Independently authored expected outputs and generated files for local rendering live in ignored `build/docx-generation-reference`.

Local verification on 2026-10-11 used Microsoft Word 16.0.20430.20146's PDF export and Poppler at 120 DPI for this synthetic reference only. Both pages were inspected before and after the round trip; each corresponding PNG was byte-identical. This is evidence for this fixture in that renderer, not a guarantee for all Word documents or other renderers. Microsoft Office is not a NuaForma runtime, build or CI dependency.

Generation verification used that same renderer and resolution for a replacement reference and a nested-block/row-repeat reference. Each has two pages. All eight expected/generated page images were inspected; each generated page was byte-identical to its independently authored expected page image. Header/footer positions, mixed formatting, numbering, image, section geometry and text-box layout were retained, with no clipping, overlap or unintended blank pages in these fixtures. This does not claim identical pagination in other renderers or barcode scanability.
