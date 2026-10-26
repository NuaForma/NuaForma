# NuaForma DOCX

Development-stage DOCX generation and round-trip processing for Kotlin and Java 17. The implementation edits an isolated native Word model, not a document rebuilt from extracted text. All docx4j and JAXB types remain under `dev.nuaforma.docx.adapters.docx4j`; consumers see only NuaForma's facade and contract types.

The registered `DocxProvider` reads DOCX and writes DOCX using the [restricted template language](../nuaforma-template/README.md), with optional supplied-font validation/embedding and requested text watermarks. PDF conversion and REST endpoints belong to the separate optional PDF/server modules. Unsupported explicit options and undeclared resources fail rather than being ignored. `DocxDocuments.open` remains a round-trip facade: it does not evaluate placeholders, process supplied fonts or add watermarks.

<a id="value-pipes-unreleased-source"></a>

## Value pipes

The source provider supports registered [JSON value pipes](../nuaforma-template/README.md#extensible-value-pipes) in ordinary placeholders, including split runs, nested structural repetitions/conditions, headers/footers, supported text boxes and requested text watermarks. The registry is captured once at provider construction, using the current thread context class loader (fallback: template library loader), never rediscovered on request threads or during PDF intermediary generation. Installed implementations must be thread-safe and cooperate with cancellation.

For explicit embedded composition, use `DocxProvider(deploymentFonts, contentPipes, valuePipes)` with `TemplatePipes.of(...)` or `TemplatePipes.discover(extensionLoader)`. Existing constructors and Kotlin default-argument JVM descriptors remain available. The template library is an API dependency because this additive constructor refers to its public registry. Optional `TemplatePipeProvider.templatePipes()` reports that exact immutable extension-definition snapshot; Source REST metadata integration (#60) uses that snapshot. Reserve `image` and every generator content-pipe name against value-pipe collisions. A content-pipe marker cannot be mixed with value/formatter chains.

Source validation checks names/chains before document mutation, including unselected branches. Value pipes retain the marker's insertion formatting and shared generation budgets; maps/lists are not stringified. Watermarks use the same captured registry and root data without granting callbacks implicit access to other fields, options or resources. Existing unsupported field/note/comment processing policies do not change.

## Generation through the engine

Configure operator fonts using trusted JVM property `nuaforma.fonts.directory`, or `DocxProvider(DeploymentFonts.load(absolutePath))`. The facade takes a bounded immutable startup snapshot and exposes metadata through the optional `FontCatalogProvider` facet; it never globally installs fonts. Absent/empty directories give empty catalogs. Unreleased issue #99 source excludes individual unreadable, malformed, unsupported, oversized or symbolic font files with bounded diagnostics, retaining valid entries and provider availability. Conflicting case-insensitive family/face identities exclude every involved file, including all its aliases, without choosing by iteration order; identical bytes are deduplicated. Directory/configuration and aggregate/count-budget failures still abort construction. See the [mount guide](../deployment/pdf-runtime/MOUNTS.md) for the exact policy and source-versus-published distinction. Reload requires a new provider instance/service restart.

Supply an explicit `GenerationLogging` through `GenerationContext` for activation/generation/catalog diagnostics, or call `snapshot.reportFailures(logging)` before composing providers. Each snapshot reports once to its first enabled scope; default library logging remains a no-op. A rejected mounted resource is absent, so explicit validate/embed requests requiring it fail before publishing output. Preserve mode still needs no installed fonts and makes no rendering/substitution guarantee.

For validate/embed, use catalog `resource` IDs in the existing `{family, face, resource}` declarations without supplying corresponding upload parts. Explicit request factories win for supplied IDs. Existing template-family validation and editable embedding checks still apply. Preserve mode does not automatically embed or rewrite mounted fonts; default DOCX output stays unchanged.

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

<a id="generator-content-pipes-unreleased-source"></a>

## Generator content pipes

Trusted generators/extensions may explicitly register `ContentPipe` implementations through the additive two-argument `DocxProvider(deploymentFonts, pipes)` constructor. Existing constructor JVM entry points and ordinary template behavior remain unchanged. The built-in `image` pipe is always registered; an empty custom list adds no other pipes. Up to 127 custom pipes fit alongside it within the 128-registration ceiling.

```kotlin
val companyLabel = object : ContentPipe {
    override val definition = ContentPipeDefinition(
        "companyLabel", "Insert a company label",
        listOf(OptionDefinition("prefix", OptionType.STRING, "Literal prefix", defaultValue = "")),
        listOf(ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.DOCX)),
        "Null inserts nothing",
    )
    override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult {
        context.checkActive()
        if (value == null) return ContentResult.Empty
        if (value !is String) throw IllegalArgumentException("A string is required")
        return ContentResult.Text((arguments[0] as String) + value)
    }
}
val provider = DocxProvider(DeploymentFonts.configured(), listOf(companyLabel))
```

The same constructor is available from Java as `new DocxProvider(DeploymentFonts.configured(), List.of(pipe))`. A provider extension discovered through the existing verified `DocumentProvider` ServiceLoader can compose this provider and expose `ContentPipeProvider` itself. Neither REST data nor templates can register callbacks. Registry metadata is captured at construction, duplicate names fail, and DOCX declarations are narrowed to the actual DOCX-to-DOCX pair. No new external dependency or global extension registry is required.

Use `{company.name | companyLabel("Company: ")}` in ordinary inline text, including split runs, table cells, headers/footers and supported VML/DrawingML text boxes. Nested if/each scopes share the same registrations and budgets. Returned text is literal and inherits the opening marker's formatting; empty results remove only that marker. Content markers inside mixed runs containing field/drawing/break objects, field instructions/results, content controls, comments/footnotes/endnotes or requested text watermarks are not supported insertion locations and fail explicitly. Keep content markers in ordinary text spans; ordinary text in previously unprocessed note/comment parts retains its existing policy.

All content chains and unknown registrations are rejected by the existing whole-document validation pass before transformation or output publication. The open `ContentResult` contract permits other generators to own additional structured variants; this DOCX implementation accepts standard `Text`/`Empty` and its bounded image result. Unknown results fail with sanitized diagnostics without reflection, stringification or fallback. Other invalid data/results also leave the output untouched because package serialization remains transactional. See the [language grammar and limits](../nuaforma-template/README.md#generator-content-pipes-unreleased-source).

<a id="image-content-pipe-unreleased-source"></a>

### Image content pipe

The built-in `image` content pipe accepts a **strict padded RFC 4648 Base64 string containing one static PNG or JPEG**. It never fetches a URL or opens a supplied path. Data URIs, whitespace, URL-safe Base64, empty strings, SVG/GIF/animated PNG, corrupt/truncated images and PNG compressed text/color-profile chunks are rejected. Explicit null inserts nothing; missing paths still fail unless an explicit fallback is supplied.

```text
{company.logo | image()}
{company.logo | image(40, 20)}
{optionalPhoto ?? null | image(25, 35, true)}
{picture | image(30, 10, false)}
{#each people as person}{#if person.photo != null}{person.photo | image(20, 20)}{/if}{/each}
```

Arguments are positional `maxWidthMm`, `maxHeightMm`, `keepRatio`, defaulting to `50`, `50`, `true`. Both maxima must be finite numbers from **0.1 through 500 millimetres**, not pixels or centimetres. Intrinsic dimensions use **96 pixels per inch**, ignoring source DPI/EXIF metadata. With ratio preservation, fit within both maxima without cropping or distortion. With `false`, each axis is independently capped, so an image can be distorted. Neither mode enlarges an image beyond its intrinsic dimensions. Dimensions are rounded to the nearest OOXML EMU (1/36000 mm). The template remains responsible for sufficient paragraph/cell/text-box space; fixed row heights or existing exact line spacing may clip any inserted content.

Insertions support ordinary paragraph/hyperlink text, tables, headers/footers and the supported VML/DrawingML text boxes, including split-run markers and nested conditions/repetitions. The image inherits the opening marker's run properties; surrounding text and paragraph geometry remain unchanged. Only the marker is replaced with an inline drawing, using an internal relationship in its owning part. Fields, mixed object/text runs, content controls, comments/notes, watermarks and text-only evaluation reject content pipes. `image` is reserved and cannot be overridden or chained before/after another pipe.

The generator validates actual PNG/JPEG framing and pixels, then normalizes to metadata-free PNG. Metadata-driven rotation is not applied; pre-orient the source pixels if needed. Alpha is retained, never flattened. The Java SE ImageIO integration uses per-call memory streams and JDK codecs only, without modifying global cache/registry settings, installing fonts or creating image cache files. It adds no dependency or third-party asset/license.

Per generation: at most **128 images**, **8 MiB Base64 characters / 6 MiB decoded input per image**, **8192 pixels per dimension**, **4 million pixels per image**, **32 MiB cumulative Base64 characters**, **16 million cumulative pixels** and **32 MiB cumulative normalized image bytes**. `maxExpandedBytes` additionally caps individual decoded input and charges cumulative Base64 characters plus **12 bytes per cumulative pixel before decoding** (source raster plus normalized raster). `maxOutputBytes` also caps cumulative retained normalized bytes; normalized encoding has a 20 MiB single-image ceiling. Lower `maxRepetitions`, cancellation and deadline limits still apply. No cross-request image cache exists. These are bounded in-process operations, not a claim of a separate process/heap sandbox.

Kotlin and Java use the same default provider; no registration is needed:

```kotlin
DocxProvider().generate(GenerationRequest(templateStream,
    GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)),
    mapOf("company" to mapOf("logo" to base64Png))), outputStream, GenerationContext())
```

```java
new DocxProvider().generate(new GenerationRequest(templateStream,
    new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX)),
    Map.of("company", Map.of("logo", base64Png))), outputStream, new GenerationContext());
```

`ContentPipeProvider.contentPipes()` describes this provider's registered image formats, sizing arguments, defaults, contexts and combinations. Source REST capability listing (#60) uses the same provider-owned metadata. PDF uses the same transformed DOCX before conversion; PDF/A-1b rejects alpha-bearing image sources rather than flattening them.

## Requested watermarks

Pass `GenerationOptions(watermark = TextWatermark("CONCEPT — {customer.name}", "#127F87", 0.3, -45.0))` in the generation requirements. Watermark text uses the same [template language](../nuaforma-template/README.md) and root `data` as document content: placeholders, conditions, repetitions, number/date formatting and escaped braces all work. For example, `{#if draft}CONCEPT — {customer.name}{#else}FINAL{/if}` resolves to `CONCEPT — Ada` for `draft = true` and `customer.name = "Ada"`. Formatters use the request's locale and time zone.

The watermark shares the document's template session and cumulative limits; it is resolved once before placement, not separately for every header or page. Inserted data remains literal and is never evaluated again. Write `{{customer.name}}` to display the literal text `{customer.name}`. Syntax errors, missing values and type errors follow the ordinary language failure policy, including syntax validation in unselected branches. A blank resolved value adds no watermark and leaves existing template watermarks intact.

The shared development contract bounds source text to 4096 characters, RGB color to `#RRGGBB`, opacity to 0–1 and rotation to -360–360 degrees. Resolved DOCX watermark text is also bounded to 4096 characters; exceeding that limit fails with a resource-limit diagnostic. DOCX rejects invalid XML characters in source or resolved text. Zero opacity is supported and produces an invisible watermark.

The adapter appends a page-centered, non-wrapping background WordArt shape to every active first/even/default header, following section inheritance. Shared header parts receive one shape, not one per reference. Existing header content, margins, section geometry and footer content remain unchanged. Missing headers receive a separate minimal anchor; creating a header at or below an explicitly declared body top margin fails rather than moving body text. Nested section breaks, duplicate header kinds and invalid header relationships fail explicitly.

Omitting the option adds nothing and retains existing template watermarks. Requesting a watermark adds it alongside existing objects; it does not identify or delete another watermark, including one previously generated by NuaForma. The font is the explicit document-default `ascii`/`hAnsi` family, otherwise Arial; this uses no host font lookup. Font validation/embedding happens before adding the shape and follows the normal supplied-font rules. A template already containing VML text paths remains outside the strict font-validation/embedding profile; default preservation retains them.

Sizing follows the smallest section page dimension, capped at 400 points wide, with a 4:1 shape aspect ratio. This is a text watermark, not an image watermark, per-section positioning API or guarantee of identical results in every renderer. Background objects may be obscured by opaque template objects. DOCX-to-PDF preservation and REST parity are implemented and qualified in the [PDF](../nuaforma-docx-pdf/README.md#fonts-and-watermarks) and [server](../nuaforma-server/README.md#verification) modules; their profile restrictions still apply, including opaque watermarks for PDF/A-1b.

`DocxWatermarksTest` builds multi-section first/even/default-header fixtures and color/opacity/rotation variants in ignored `build/watermark-reference`, compares unrelated package parts and existing header content, and exercises missing headers, expression-language parity, literal inserted values, shared limits and explicit failure policies. Core integration additionally checks concurrent requests with distinct or absent watermarks.

Local visual verification on 2026-10-11 used Word 16.0.20430.20146 and Poppler at 100 DPI, only as optional QA tools. The multi-section fixture retained five pages across diagonal teal, vertical red, opaque and transparent variants; additional missing/empty-header fixtures retained two pages each. Every distinct page image was inspected. All five plain pages matched the independently authored literal reference, and all nine transparent pages matched their plain controls byte-for-byte. Requested shapes appeared on all pages; headers, footers, text boxes, tables and section geometry retained their positions. This evidence covers these fixtures in this renderer, not arbitrary templates, PDF backend compatibility or an Office runtime requirement.

After enabling watermark expressions, all 42 previously verified control/variant page images remained byte-identical. The additional five-page dynamic fixture resolved its condition and customer placeholder to `CONCEPT — Ada` on every page and was visually inspected with unchanged pagination and content placement.

## Supplied fonts

<a id="automatic-embedding-unreleased-issue-100-source"></a>

### Automatic embedding

For DOCX output, request `fontMode: "embed"` with `fontSelection: "used"`. Selection defaults to `declared`, preserving existing explicit validation/embedding. `used` requires `embed`; default preservation and PDF output retain their behavior. Kotlin and Java use the same output-options map as REST:

```kotlin
val provider = DocxProvider(DeploymentFonts.load(listOf(imageFontDirectory), mountedFontDirectory))
val output = FormatRequest(DocumentFormat.DOCX,
    mapOf("fontMode" to "embed", "fontSelection" to "used"))
```

```java
var provider = new DocxProvider(DeploymentFonts.load(List.of(imageFontDirectory), mountedFontDirectory));
var output = new FormatRequest(DocumentFormat.DOCX,
    Map.of("fontMode", "embed", "fontSelection", "used"));
```

Directories are trusted absolute application/operator configuration. The IMAGE directory list may be empty and the mount may be null. `configured()` reads `nuaforma.fonts.bundledDirectories` (platform path separator) and the existing `nuaforma.fonts.directory`. IMAGE roots are recursively snapshotted without following symbolic links; ordinary embedded applications infer no IMAGE roots. No ambient host font discovery, download, global installation or font-name substitution occurs. See [startup budgets and container defaults](../deployment/pdf-runtime/MOUNTS.md).

Selection runs after supported template expansion and requested watermark insertion. Only effective families/faces used by remaining supported text, symbols and numbering are required; unused styles and catalog entries are not embedded. Family identity is case-insensitive; faces are regular/bold/italic/boldItalic. There is no fixed family list or synthetic bold/italic fallback. Full font bytes are embedded after metadata and editable-outline permission checks. Existing embedded faces are inspected and retained; explicit replacement attempts fail.

Precedence for a used family/face is explicit `fonts` declaration, then MOUNT, then IMAGE. Request bytes win for the same logical resource ID. Other request resources still require declarations, and unused overrides fail. Same-tier ambiguous identities fail deterministically, including identities excluded as conflicting at startup. Generated state and uploaded fonts are request-private. Unused catalog fonts are not opened during generation. Requests allow at most 64 used faces, 16 MiB per font and 64 MiB total, further restricted by the generation expansion budget. Aliases count per selected face even when resource bytes are shared.

The effective-formatting profile covers Latin WordprocessingML text in the body, headers, footers and supported VML/DrawingML Word text boxes; run defaults, default/explicit paragraph and character styles with bounded `basedOn` inheritance; style bold/italic toggles and direct overrides; explicit Latin and major/minor Latin theme fonts; and decimal/Roman/letter/bullet numbering levels and overrides. This is not complete Word font resolution or glyph-coverage validation. Complex-script/East-Asian text selection, RTL/complex-script overrides, conditional/default table font styling, native DrawingML text, picture bullets, numbering-style links, tracked revisions, populated footnote/endnote/comment parts, charts/diagrams and populated VML text paths are rejected when relevant. Missing effective fonts, missing/cyclic style references, unresolved themes, unavailable faces and embedding restrictions fail before publication. Default `preserve` remains available; explicit selection has its own restrictions below.

The Playground exposes **Automatically embed used template fonts** only when the provider/output supports this selection. Its request/export uses the same options; uploads remain explicit overrides. Catalog availability establishes neither embedding permission nor glyph coverage.

Automatic embedding verification on 2026-10-21: `AutomaticFontsTest` authors synthetic independently renamed families/faces under ignored `build/automatic-font-reference`, checks deobfuscated bytes/relationships and compares unaffected document structure. The two-page automatic output and independently requested explicit-selection baseline were rendered with Microsoft Word for Microsoft 365 and inspected after rasterization at 100 DPI; corresponding page PNGs were byte-identical. The fixtures exercise inherited/mixed faces, numbering, headers/footers and both Word text-box forms. Their barcode-derived outlines test font consumption and layout, not general glyph coverage or arbitrary barcode encoding. Actual IMAGE/MOUNT font/PDF qualification is additionally wired into the existing native container checks; its result must be verified on the candidate, not inferred from these fixtures.

### Existing explicit selection

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

Template, expanded and output byte limits, archive entry limits and cancellation/deadline checks apply in embedded use. XML is limited to 256 nested elements and 256 attributes per element. Limits and checks are cooperative; they do not forcibly interrupt blocking caller I/O or every internal library operation and are not a process/heap sandbox. The service/container adds transport and deployment bounds; in-process DOCX parsing is not isolated into a separate child process. The PDF module separately isolates conversion and output validation.

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

## Preparing content for another output format

The additive `DocxProvider.generateForConversion(request, output, context)` facade performs the same assessment, template expansion and bounded transactional DOCX serialization, returning `DocxConversionResult`. It leaves the resolved watermark out of the private intermediate DOCX and returns its immutable `TextWatermark` and selected default font family separately. Resolution shares the document session, so validation, budgets, value pipes and literal inserted values retain their existing behavior. A downstream format adapter owns decoration placement. Ordinary `generate` and DOCX output still place their existing header shapes. No document-library object or renderer is exposed through this facade.
