# Optional DOCX and PDF provider

`nuaforma-docx-pdf` is an optional Java 17/Kotlin library. It registers provider `nuaforma-docx-pdf`, reads DOCX and writes DOCX or static PDF 1.7. It delegates template processing and DOCX output unchanged to `nuaforma-docx`; only PDF uses a private conversion copy. These are development APIs, not a stable published release.

## Embedded use and discovery

Add core and this module to use `ProviderRegistry.discover()`. The transitive DOCX provider is also registered, so a DOCX request needs an explicit preference: `nuaforma-docx` or `nuaforma-docx-pdf`. Core never chooses between them by classpath order. PDF selects the ready PDF provider. Core has no dependency on this implementation.

For explicit composition, construct `DocxPdfProvider(LibreOfficeConfiguration(absoluteLauncherPath))` and register it with core through its normal explicit-registration API. Java has equivalent public constructors. `maximumParallelConversions` defaults to one per provider instance and accepts one through eight; share an instance to enforce that concurrency budget. The no-argument provider reads trusted JVM property `nuaforma.pdf.launcher`, not request data. The configured launcher must be the qualified isolated runtime described in [the deployment guide](../deployment/pdf-runtime/README.md), not a bare `soffice` executable or a document-selected command.

An absent launcher, mismatched renderer version or unavailable isolation leaves DOCX executable and reports PDF unavailable. Declared capabilities still include both formats. Availability and assessment probe readiness without consuming a template or accessing `data`; generation rechecks with the caller's deadline and cancellation. Assessment is not a reservation or a content-validity guarantee.

## Output options

DOCX options retain the [DOCX module's semantics](../nuaforma-docx/README.md). PDF accepts the following `output.options`; unknown fields, explicit nulls and unsupported combinations fail rather than silently downgrade.

| Option | Supported values |
| --- | --- |
| `version` | `"1.7"`, also the default |
| `profile` | `"standard"`, also the default; static output, not PDF/A or PDF/UA |
| `fontMode` | `"preserve"` (default), `"validate"`, `"embed"` |
| `fonts` | For validate/embed, the existing array of `{family, face, resource}` declarations and matching resource factories; face defaults to regular |
| `paragraphDefaults` | Optional object with `afterTwips` (integer 0–2880) and/or `line240ths` (integer 1–2400); at least one field |

Example format-specific options:

```json
{
  "version": "1.7",
  "profile": "standard",
  "fontMode": "validate",
  "fonts": [{"family": "My Company Font", "face": "regular", "resource": "body-font"}],
  "paragraphDefaults": {"afterTwips": 160, "line240ths": 278}
}
```

The paragraph option fills only missing document-level defaults in the PDF copy. Existing template defaults, style/paragraph overrides, automatic spacing and non-auto line rules stay leading. The example values are explicitly selected compatibility settings, not inferred Word settings or universal defaults. Omitting the option leaves LibreOffice's handling of missing defaults unchanged; that may paginate differently from Word. The returned DOCX is never rewritten for PDF compatibility.

## Fonts and watermarks

PDF requires explicit ASCII/hAnsi document-default font names and available fonts. It checks explicit run/symbol/drawing/VML font references and rejects unresolved theme-font requests in this initial profile. Available fonts come from the reviewed immutable renderer catalog, request-private supplied resources or template-embedded fonts. Supplied standalone TrueType/OpenType files are limited to 16 MiB each and 64 MiB together, further bounded by the caller's expanded-byte budget. Regular faces are required; declared family/face must match the font metadata. Fonts and caches are not globally installed or shared across requests.

All PDF modes require the resulting fonts to be embedded; static PDF does not inherit DOCX's editable-embedding restriction. Print/preview and editable OpenType embedding permissions are accepted, restricted and bitmap-only embedding is rejected where the specification requires it, and permission flags are never changed. The caller is responsible for usage rights; no license evidence or extra consent declaration is requested. A technical no-subsetting flag currently fails explicitly as unsupported because full-font embedding has not been qualified for this backend. This is a renderer limitation, not an additional licensing rule. No arbitrary font substitution is intentionally configured; structural embedding checks do not prove every requested glyph exists or every renderer font-resolution choice is identical to Word.

General `GenerationOptions.watermark` works exactly as for DOCX. Its text uses the same restricted language, `data`, locale/time zone and cumulative budgets. For example, `CONCEPT — {customer.name}` is resolved once during DOCX processing and remains visible after conversion; inserted values are never recursively evaluated. Color, opacity, rotation, first/odd/even headers and section geometry are preserved in the qualified fixtures. PDF conversion does not add a second watermark.

## Fidelity profile and limitations

The source template owns the layout. The compatibility copy normalizes the measured native rectangular VML type-202 text-box profiles, preserving native paragraph/run/font content, dimensions and insets. The supported margin profile is rotated 90 degrees, bottom/right-margin aligned, zero-inset and fits the explicit section margin; ordinary inline boxes retain explicit dimensions/insets. Unsupported variants of this type-202 geometry profile, placement modes and auto-sized boxes fail instead of being guessed or rasterized. Barcode fonts remain text, not generated images.

This normalization profile requires a single shape inside its picture wrapper, no picture attributes and no authored `_x0000_t202` shape-type override. Explicit fill/stroke/shape-definition variants are not guessed. Other native shape types remain renderer-managed rather than being rewritten by this normalizer; their fidelity is not established by the rectangular-box qualification.

Modern inline DrawingML text boxes can show the temporarily accepted measured vertical difference of 1.69–1.70 points (approximately 0.60 mm), tracked in [issue #32](https://github.com/NuaForma/NuaForma/issues/32). A result containing these boxes carries warning `PDF_TEXTBOX_LAYOUT_LIMITATION`. This is not a general tolerance for layout defects. The documented renderer is LibreOffice 26.8.1.1 on the qualified Linux/amd64 runtime; no cross-renderer or arbitrary-template identity is promised.

The initial output is static, unencrypted PDF 1.7. PDF/A, PDF/UA, other versions, interactive forms and signature fields are not supported yet. Unsupported requests fail. Resource-retrieving or executable Word fields are rejected before conversion; passive hyperlinks remain subject to the static-output policy. Document selection, embedding checks and visual/scan checks are separate validation responsibilities.

## Lifecycle and safety

Caller-owned input/output streams are not closed. Resource streams opened through factories are closed. Generation uses one private directory/profile/font cache; all owned conversion and validation processes must stop before cleanup. Cancellation, the shared deadline and process/concurrency limits apply to readiness, font discovery, conversion and validation. The private work tree is sampled during execution and capped at 512 MiB, 16,384 entries and 64 nested directory levels; the deployment must also enforce a hard temporary-storage limit because sampled accounting is not a filesystem quota.

The caller's output-byte limit also bounds the generated/intermediate DOCX and compressed conversion copy; this initial implementation can therefore reject a large intermediate document even if its eventual PDF might be smaller. The validator has additional fixed ceilings of 250,000 indirect objects, 500,000 visited objects, 100,000 array entries, depth 128 and 10,000 pages, and a decoded non-image-stream budget bounded by `maxExpandedBytes`. Image decoding and glyph/outline validation are not performed by this structural checker.

PDF framing, version, strict structure, page geometry, embedded-font streams and forbidden actions/forms/attachments are checked before copying bytes to the caller. PDFBox runs in a separately sandboxed Java 17 process with a 256 MiB heap, bounded native/JVM memory and the shared deadline; malformed output cannot run its parser inside the engine JVM. The validator limits objects, depth, pages and decoded non-image streams. It is not a conformance validator or a proof of visual fidelity. Its failures never publish success-shaped output. Once copying into a caller-owned stream starts, an I/O failure or cancellation can leave a partial stream; generic streams cannot be rolled back.

## Verification

Run `./gradlew :nuaforma-docx-pdf:test :nuaforma-server:test` on Java 17. Tests cover unchanged DOCX delegation, discovery/ambiguity, missing converters, option filtering, independent PDF syntax, active-content rejection, font permissions, private cleanup, cancellation, process reaping, budgets, Java API use and adapter ownership. Mocked converter tests establish orchestration only, never rendering fidelity.

Tests requiring Linux executables, POSIX permissions and secure directory traversal are explicitly Linux-scoped; missing-converter, DOCX, XML, option and Java API tests do not use Unix-only absolute-path fixtures. This does not claim a qualified native PDF runtime on another operating system.

The runtime qualification uses actual LibreOffice and the actual conversion-copy implementation on fourteen synthetic documents (44 pages). Structural PDF checks, pixel comparisons, complete visual review, Word-coordinate comparisons and independent Code 39 scans at 100 and 300 DPI are separate from unit tests. See [the qualification guide](../deployment/pdf-runtime/README.md) and [research tooling](../tools/pdf-evaluation/README.md). Private font copies, generated documents, images, caches and binaries remain ignored and are not published as product assets.
