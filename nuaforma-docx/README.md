# NuaForma DOCX

Development-stage DOCX generation and round-trip processing for Kotlin and Java 17. The implementation edits an isolated native Word model, not a document rebuilt from extracted text. All docx4j and JAXB types remain under `dev.nuaforma.docx.adapters.docx4j`; consumers see only NuaForma's facade and contract types.

The registered `DocxProvider` reads DOCX and writes DOCX using the [restricted template language](../nuaforma-template/README.md), with optional validation and embedding of supplied fonts. New watermarks, PDF conversion and REST endpoints are not implemented. Unsupported explicit options and undeclared resources fail rather than being ignored. `DocxDocuments.open` remains a round-trip facade: it does not evaluate placeholders or process supplied fonts.

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

Traversal includes body, headers, footers and supported legacy VML and modern DrawingML text boxes, including the rotated margin reference. It does not evaluate document metadata, field instructions, comments or footnotes/endnotes. Unsupported Word extensions are not a promise of arbitrary template support. Font-based barcodes remain text: the template owns encoding and font choice, and replacement does not add start/stop characters or uppercase values. See the font policy and fixture-specific scan verification below.

All supported syntax is validated before generation, including unselected branches. One template session shares local scopes and cumulative expression/output/repetition budgets across the document. Structural markers have the same 4096-character ceiling as inline markers. DOCX traversal additionally has conservative ceilings of 128 structure levels and 1,000,000 processing visits across validation and expansion. No consumer business schema or executable expression language is introduced.

## Supplied fonts

Output option `fontMode` defaults to `preserve`: retain the template's font names, sizes and styling without looking up fonts on the engine host. This does not guarantee that a viewer has those fonts; the viewer's own substitution policy still applies. The engine never installs fonts, resolves host font directories or uses a global font cache.

`validate` checks caller-supplied font metadata against the template's declared font families without embedding them. `embed` performs those checks and embeds the complete supplied font faces in the DOCX. Declare fonts in output option `fonts`, using `family`, logical `resource` ID and optional `face` (`regular`, `bold`, `italic` or `boldItalic`; default `regular`). Supply their bytes through `GenerationRequest.resources`. Resource IDs are not filesystem paths or URLs. Missing and unused resources fail. `preserve` does not accept supplied-font declarations.

```kotlin
val outputFormat = FormatRequest(DocumentFormat.DOCX, mapOf(
    "fontMode" to "embed",
    "fonts" to listOf(mapOf(
        "family" to "Libre Barcode 39",
        "face" to "regular",
        "resource" to "barcode-font",
    )),
))
// Provide ResourceSource { ... } under "barcode-font" in GenerationRequest.resources.
```

Strict validation and embedding currently require explicit `ascii` and `hAnsi` document-default fonts and a supplied regular face for every explicitly declared family, including styles, numbering, headers/footers and unused branches. Theme-based font resolution, implicit host defaults and VML text-path fonts fail explicitly in these modes; ordinary text boxes are supported. Optional additional faces are validated and embedded as declared. This is conservative family/face metadata checking, not complete effective-style resolution, glyph coverage validation or a guarantee against every viewer's substitution. These strict-mode restrictions do not change default preservation.

Supported font resources are standalone TrueType/OpenType sfnt files with Unicode family names and a supported OS/2 table. Collections, WOFF and other containers are not supported. Metadata inspection is bounded, not a complete font validator or a font renderer. Limits are 64 family/face declarations, 16 MiB per resource, 64 MiB aggregate resources (also bounded by `maxExpandedBytes`) and 128 declared template families. Cancellation and byte budgets apply while inspecting and embedding; provider-owned resource streams are closed. Existing embedded faces are preserved by default; explicitly replacing one is not supported.

Embedding follows the versioned OpenType `OS/2.fsType` permissions. Editable DOCX output accepts installable or editable embedding, rejects restricted or preview/print-only embedding and, where applicable, bitmap-only embedding. No-subsetting permissions are satisfied by embedding the entire font. Reserved flags and legacy versions follow the specification; the bytes and permission flags are never rewritten to bypass restrictions. `validate` does not embed and therefore does not reject a font merely for restricting embedding. The caller remains responsible for usage rights; NuaForma requires no license evidence or additional consent declaration.

For basic Code 39, a template can render `*{reference}*` in its chosen barcode font. The asterisks belong to that barcode encoding, not NuaForma's expression syntax. Input must already conform to the selected encoding. Correct text replacement alone does not establish scanability, which also depends on the font, size, placement, quiet zone and reader.

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

Reading and serialization each own a unique temporary directory containing one bounded package file, closed and deleted on success, failure or cancellation. Archive names are never extracted into host paths. On POSIX filesystems the JDK's temporary-storage defaults restrict directory/file permissions to the owning user; other platforms use their temporary directory's inherited access policy. Deploy with appropriately restricted temporary storage: this does not isolate requests from other code running as the same OS user. The mutable model and backing package bytes remain in memory until close.

Serialization and output-package validation finish before copying to the caller's output stream. An I/O failure or cancellation during that final copy can still leave partial bytes; use caller-owned staging storage for atomic publication. A failed generation never returns a successful result. Failures use sanitized `GenerationException` diagnostics; caller I/O errors propagate as `IOException`. Cleanup failures propagate instead of being silently reported as success. No logging backend or host-wide XML/library configuration is installed. The default runtime does not log document/data content; applications supplying their own third-party logging configuration must review it before enabling verbose library logs.

## Verification

```sh
./gradlew :nuaforma-docx:test
./gradlew :nuaforma-server:test --tests 'dev.nuaforma.architecture.*'
```

`SyntheticDocx` independently authors a deterministic ZIP/OOXML reference without docx4j. Tests compare every package part: namespace-aware XML structure for rewritten XML and byte equality for media. Only documented semantic equivalents are normalized: unordered OPC/property declarations, the style-default and DrawingML text-box boolean lexical forms, and empty `mc:Ignorable`. Word paragraph/run order, text, properties, IDs and relationship targets remain significant.

`DocxSecurityTest` launches a fresh Java 17 test process with dedicated temporary storage and captures its default standard output/error. Its reproducible `SecurityProbe` exercises successful generation, unsafe archive paths, a highly compressed expanded payload, external XML entities in multiple parts, active content, invalid binding values, XML depth/attribute ceilings, expression/repetition limits, input/output limits, deadlines, cancellation during input and font resource I/O, caller I/O failures and mixed concurrent success/cancellation. It observes scratch ownership and POSIX permissions during processing and verifies empty storage afterward, caller stream ownership, no premature output, no successful result after destination failure and no synthetic private-data canary in default logs. The test process's 192 MiB heap and 60-second timeout are test-harness bounds, not production heap/process isolation. Font concurrency and independent keys/parts are additionally covered by `DocxFontsTest`.

The reference covers styles, mixed run formatting, a split placeholder, bookmarks, numbering, tables, an image, passive hyperlinks, page fields, headers/footers, portrait/landscape section geometry and text boxes containing placeholders, including a rotated margin box. Both legacy VML and modern DrawingML text boxes must retain their structure. Generated reference files live in ignored `nuaforma-docx/build/docx-reference`, not in production artifacts.

Run visual comparison only after the build has finished, so the renderer cannot lock files while the tests regenerate them. A local renderer is an optional verification tool, not a production or CI dependency. Record the renderer/version and compare every page before claiming visible preservation. XML checks alone do not prove pagination, font fidelity or barcode scanability. Generation tests also exercise split-run replacements, nested conditions/repeats, row repeats, safe identifiers, empty cells, invalid branches, shared budgets and provider metadata. The server test runtime checks real generation through core, including concurrent request isolation. Independently authored expected outputs and generated files for local rendering live in ignored `build/docx-generation-reference`.

Local verification on 2026-10-11 used Microsoft Word 16.0.20430.20146's PDF export and Poppler at 120 DPI for this synthetic reference only. Both pages were inspected before and after the round trip; each corresponding PNG was byte-identical. This is evidence for this fixture in that renderer, not a guarantee for all Word documents or other renderers. Microsoft Office is not a NuaForma runtime, build or CI dependency.

Generation verification used that same renderer and resolution for a replacement reference and a nested-block/row-repeat reference. Each has two pages. All eight expected/generated page images were inspected; each generated page was byte-identical to its independently authored expected page image. Header/footer positions, mixed formatting, numbering, image, section geometry and text-box layout were retained, with no clipping, overlap or unintended blank pages in these fixtures. This does not claim identical pagination in other renderers or barcode scanability.

Font verification on 2026-10-11 used the same Word renderer, Poppler at 300 DPI and the independent ZXing-C++ 3.1.1 Code 39 decoder, solely as local QA tools. `DocxFontsTest` reproducibly authors the inputs and independent literal expected content under ignored `build/font-reference`; the pinned Libre Barcode 39 test asset and its license are in `src/test/resources/fonts`. All nine expected/generated, embedded and negative-control page images were inspected. Corresponding expected/generated pages were byte-identical, with the barcode in the lower right margin and its rotation unchanged. The decoder read `NF001` at an orientation of -90 degrees from the positive fixtures; an unframed Code 39 value did not decode.

A renamed, checksum-correct QA derivative whose family was never installed also decoded when embedded and rendered identically to the original-font reference. The same document without embedding fell back to ordinary text and did not decode. This positive/negative pair verifies actual embedded-font use rather than accidentally relying on the installed font or a viewer's font-name cache. The derivative is generated only in ignored reference output; the committed font asset is unmodified. These results establish the selected fixture/font/renderer combination, not arbitrary fonts or documents. Optional decoder/font registration is not part of the build, CI or production runtime.
