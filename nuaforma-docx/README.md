# NuaForma DOCX

Development-stage DOCX round-trip facade for Kotlin and Java 17. The implementation reads a Word package into an isolated docx4j model and writes DOCX without reconstructing its content from plain text. All docx4j and JAXB types remain under `dev.nuaforma.docx.adapters.docx4j`; consumers see only NuaForma's facade and contract types.

This is not a template processor or a registered `DocumentProvider` yet. Placeholders remain literal text, including those in text boxes, headers and footers. Conditions, repeats, new watermarks, PDF conversion and REST endpoints are not implemented.

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

Run visual comparison only after the build has finished, so the renderer cannot lock files while the tests regenerate them. A local renderer is an optional verification tool, not a production or CI dependency. Record the renderer/version and compare every page before claiming visible preservation. XML checks alone do not prove pagination, font fidelity or barcode scanability. The placeholder and barcode processing milestones must test replacements in text boxes as well as body, header and footer content; this round trip does not implement replacement.

Local verification on 2026-10-11 used Microsoft Word 16.0.20430.20146's PDF export and Poppler at 120 DPI for this synthetic reference only. Both pages were inspected before and after the round trip; each corresponding PNG was byte-identical. This is evidence for this fixture in that renderer, not a guarantee for all Word documents or other renderers. Microsoft Office is not a NuaForma runtime, build or CI dependency.
