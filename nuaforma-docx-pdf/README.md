# Optional DOCX and PDF provider

`nuaforma-docx-pdf` is an optional Java 17/Kotlin library. It registers provider `nuaforma-docx-pdf`, reads DOCX and writes DOCX or static PDF with selectable standard/PDF/A profiles. It delegates template processing and DOCX output unchanged to `nuaforma-docx`; only PDF uses a private conversion copy. These are development APIs, not a stable published release.

## Embedded use and discovery

Version `0.2.0-alpha.1` supports the same [value-pipe registry](../nuaforma-template/README.md#extensible-value-pipes-unreleased-source) as DOCX, captured once at provider construction. `DocxPdfProvider(configuration, contentPipes, valuePipes)` is an additive explicit-registry overload; existing constructors/default-argument JVM descriptors remain usable. PDF intermediary generation and requested watermarks reuse the captured registry, not a request thread's class loader. `TemplatePipeProvider.templatePipes()` reports the same extension snapshot. The template library is an API dependency for this public constructor; no new external dependency or renderer behavior is introduced. Historical `0.1` containers remain unchanged; source capability endpoint integration (#60) uses the same metadata.

Add core and this module to use `ProviderRegistry.discover()`. The transitive DOCX provider is also registered, so a DOCX request needs an explicit preference: `nuaforma-docx` or `nuaforma-docx-pdf`. Core never chooses between them by classpath order. PDF selects the ready PDF provider. Core has no dependency on this implementation.

For explicit composition, construct `DocxPdfProvider(LibreOfficeConfiguration(absoluteLauncherPath))` and register it with core through its normal explicit-registration API. Java has equivalent public constructors. `maximumParallelConversions` defaults to one per provider instance and accepts one through eight; share an instance to enforce that concurrency budget. The no-argument provider reads trusted JVM property `nuaforma.pdf.launcher`, not request data. Source builds additionally accept `nuaforma.pdf.parallelism` (1–8, default 1); explicit constructor configuration takes precedence. The container maps `NUAFORMA_PDF_PARALLELISM` to that property. These operator settings are not generation options and do not change the published alpha image. The configured launcher must be the qualified isolated runtime described in [the deployment guide](../deployment/pdf-runtime/README.md), not a bare `soffice` executable or a document-selected command.

An absent launcher, mismatched renderer version or unavailable isolation leaves DOCX executable and reports PDF unavailable. Declared capabilities still include both formats. Availability and assessment probe readiness without consuming a template or accessing `data`; generation rechecks with the caller's deadline and cancellation. Assessment is not a reservation or a content-validity guarantee.

### Source runtime verification and contention

Issue [#91](https://github.com/NuaForma/NuaForma/issues/91) separates a busy PDF process budget from a failed runtime. Successful runtime identity/isolation probes are shared within one provider instance for at most 30 seconds, using monotonic time and launcher file identity. Missing/non-executable or changed launchers invalidate reuse; an expired or failed probe cannot fall back to stale success. Cold/expired probes are single-flight, and waiters retain their own deadlines/cancellation instead of an unrelated ten-second availability deadline. A real deadline/resource/cancellation failure remains that failure, not a false unavailable provider. The legacy no-context availability overload uses the normal bounded generation context.

Only successful request-independent runtime verification is cached. Document models, template values, supplied/embedded fonts, conversion files and output are never cached here. Request-specific font checks and every conversion, bounded structural PDF check, PDF/A conformance check and digest comparison still execute for every document. All processes retain the same isolation and share the configured process ceiling; increasing it changes aggregate resource demand, not per-process permissions or limits. No availability check reserves capacity or guarantees that the next process will succeed. Qualification of a new candidate and post-fix VPS measurements remain separate from source tests.

`python3 tools/container/qualify_pdf_parallelism.py --image nuaforma:local --output build/pdf-parallelism-qualification` checks a locally built source candidate with four concurrent synthetic PDF/A-3b requests at process limits one and two, then repeats after verification expiry. It uses the existing reviewed security profile, the real isolated validators and the already approved pypdf test tool for independent tenant-reference checks. It verifies per-document validation counts and private cleanup; it is not a broad load test, visual qualification or publication step. Native ARM64 and the intended VPS need their own corresponding checks before compatibility/performance claims.

<a id="generator-content-pipes-unreleased-source"></a>

## Generator content pipes

The additive `DocxPdfProvider(configuration, pipes)` constructor registers trusted generator-owned content implementations using the same contract as DOCX. Existing constructor JVM signatures remain unchanged. A pipe must declare unprofiled DOCX-to-DOCX support, plus explicit DOCX-to-PDF/profile support if it may run for PDF. A PDF request registers only the implementations applicable to its selected profile; a marker for an unsupported combination fails before output publication. Empty PDF profile sets cover all this provider's existing profiles, not arbitrary PDF variants. Unsupported provider/profile declarations fail at registration.

Content is inserted into the transformed DOCX intermediate before the existing isolated conversion/validation path. There is no second PDF positioning engine, and DOCX output remains delegated unchanged. Use provider-owned `ContentPipeProvider` metadata for embedded inspection; Source REST capabilities expose declared/usable metadata through #60. Source builds include the [bounded Base64 PNG/JPEG image pipe](../nuaforma-docx/README.md#image-content-pipe-unreleased-source). Its metadata lists DOCX output plus `standard`, `pdfa-1b`, `pdfa-2b` and `pdfa-3b` PDF profiles; PDF availability still requires the qualified isolated runtime. Alpha-bearing images require standard/2b/3b; 1b explicitly rejects them before conversion, with no flattening or downgrade. Requested dimensions and aspect rules apply to the DOCX model, not a separate PDF layout engine. These APIs are available from `0.2.0-alpha.1`; historical `0.1` artifacts remain unchanged.

## Output options

DOCX options retain the [DOCX module's semantics](../nuaforma-docx/README.md). PDF accepts the following `output.options`; unknown fields, explicit nulls and unsupported combinations fail rather than silently downgrade.

| Option | Supported values |
| --- | --- |
| `version` | Derived from profile: `"1.4"` for PDF/A-1b, `"1.7"` otherwise; explicit values must agree |
| `profile` | `"pdfa-3b"` (default), `"pdfa-2b"`, `"pdfa-1b"`, or `"standard"` |
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

Omitting `profile` selects PDF/A-3b, not ordinary PDF; use `{"profile":"standard"}` explicitly to retain ordinary static PDF 1.7. The archival default was selected before the first publication. Embedded and REST callers use the same options; the result reports the effective profile/version. Every PDF/A output must pass the explicit selected veraPDF profile and the structural safety check before its exact validated bytes are delivered. A failed or incomplete validation never downgrades the result. No attachments, encryption, level-A conformance or PDF/UA claim is introduced.

PDF/A-1b has a deliberately restricted transparency-free source profile. It rejects non-opaque requested watermarks, DrawingML alpha modifications/drawing effects, VML opacity/style filters, charts/diagrams/embedded objects, and media other than opaque PNG/JPEG. PNG alpha channels and palette transparency are rejected. Source opacity values must be explicitly supported opaque values. We do not flatten transparency or silently accept changed table/text-box borders; choose 2b/3b for those templates. Assessment checks known options without reading the template; generation additionally checks its actual package contents.

The 1b inspection also checks custom XML/media locations and actual image-relationship targets, not only conventional `word/media` paths or declared MIME types. Direct `src` image references, external/unsafe image targets and unsupported image encodings are outside this initial profile. Unused unsupported resources can conservatively reject a template; no automatic rewrite or transparency removal is offered.

## Fonts and watermarks

Unreleased issue #99 source shares the DOCX module's [per-file mount recovery](../deployment/pdf-runtime/MOUNTS.md#fonts): rejected files leave no resources or catalog aliases, valid fonts/providers survive, and conflicting identities exclude all involved files. Directory/configuration and global budget failures remain fatal to snapshot construction. Both providers report captured failures through explicit logging when activated; restart reloads the snapshot. No PDF font availability, print-embedding, output-validation or sandbox rule is relaxed: generation requiring a missing family/resource still fails clearly instead of silently substituting it. Published `0.2.0-alpha.1` artifacts retain the previous loader.

The optional font catalog lists renderer-bundled and mounted families/faces without paths or private request/template fonts. Operator fonts are immutable startup snapshots, selected explicitly by logical declaration IDs or staged automatically for missing required template families. Explicit private and template-embedded families precede mounted fallback, then bundled fonts. Only selected missing mounted families and their available faces enter the request's private font directory, never the whole mount. The combined staged standalone-font ceiling remains 64 MiB. Bundled catalog enumeration runs in a bounded fontconfig sandbox; variable faces do not guarantee arbitrary axis/style support.

PDF requires explicit ASCII/hAnsi document-default font names and available fonts. It checks explicit run/symbol/drawing/VML font references and rejects unresolved theme-font requests in this initial profile. Available fonts come from the reviewed immutable renderer catalog, request-private supplied resources or template-embedded fonts. Supplied standalone TrueType/OpenType files are limited to 16 MiB each and 64 MiB together, further bounded by the caller's expanded-byte budget. Regular faces are required; declared family/face must match the font metadata. Fonts and caches are not globally installed or shared across requests.

All PDF modes require the resulting fonts to be embedded; static PDF does not inherit DOCX's editable-embedding restriction. Print/preview and editable OpenType embedding permissions are accepted, restricted and bitmap-only embedding is rejected where the specification requires it, and permission flags are never changed. The caller is responsible for usage rights; no license evidence or extra consent declaration is requested. A technical no-subsetting flag currently fails explicitly as unsupported because full-font embedding has not been qualified for this backend. This is a renderer limitation, not an additional licensing rule. No arbitrary font substitution is intentionally configured; structural embedding checks do not prove every requested glyph exists or every renderer font-resolution choice is identical to Word.

General `GenerationOptions.watermark` works exactly as for DOCX. Its text uses the same restricted language, `data`, locale/time zone and cumulative budgets. For example, `CONCEPT — {customer.name}` is resolved once during DOCX processing and remains visible after conversion; inserted values are never recursively evaluated. Color, opacity, rotation, first/odd/even headers and section geometry are preserved in the qualified fixtures. PDF conversion does not add a second watermark.

## Fidelity profile and limitations

The source template owns the layout. The compatibility copy normalizes the measured native rectangular VML type-202 text-box profiles, preserving native paragraph/run/font content, dimensions and insets. The supported margin profile is rotated 90 degrees, bottom/right-margin aligned, zero-inset and fits the explicit section margin; ordinary inline boxes retain explicit dimensions/insets. Unsupported variants of this type-202 geometry profile, placement modes and auto-sized boxes fail instead of being guessed or rasterized. Barcode fonts remain text, not generated images.

This normalization profile requires a single shape inside its picture wrapper, no picture attributes and no authored `_x0000_t202` shape-type override. Explicit fill/stroke/shape-definition variants are not guessed. Other native shape types remain renderer-managed rather than being rewritten by this normalizer; their fidelity is not established by the rectangular-box qualification.

Modern inline DrawingML text boxes can show the temporarily accepted measured vertical difference of 1.69–1.70 points (approximately 0.60 mm), tracked in [issue #32](https://github.com/NuaForma/NuaForma/issues/32). A result containing these boxes carries warning `PDF_TEXTBOX_LAYOUT_LIMITATION`. This is not a general tolerance for layout defects. The documented renderer is LibreOffice 26.8.1.1; the measured layout baseline below is Linux/amd64, and current source service checks also pass natively on AMD64/ARM64. Those checks do not promise cross-renderer or arbitrary-template identity.

Output is static and unencrypted: standard PDF 1.7, PDF/A-1b (PDF 1.4), PDF/A-2b or PDF/A-3b (PDF 1.7). Level-A profiles, PDF/UA, other versions, interactive forms and signature fields are not supported. Unsupported requests fail. Resource-retrieving or executable Word fields are rejected before conversion; passive hyperlinks remain subject to the static-output policy. Document selection, embedding/conformance checks and visual/scan checks are separate validation responsibilities.

## Lifecycle and safety

Caller-owned input/output streams are not closed. Resource streams opened through factories are closed. Generation uses one private directory/profile/font cache; all owned conversion and validation processes must stop before cleanup. Cancellation, the shared deadline and process/concurrency limits apply to readiness, font discovery, conversion and validation. The private work tree is sampled during execution and capped at 512 MiB, 16,384 entries and 64 nested directory levels; the deployment must also enforce a hard temporary-storage limit because sampled accounting is not a filesystem quota.

The caller's output-byte limit also bounds the generated/intermediate DOCX and compressed conversion copy; this initial implementation can therefore reject a large intermediate document even if its eventual PDF might be smaller. The validator has additional fixed ceilings of 250,000 indirect objects, 500,000 visited objects, 100,000 array entries, depth 128 and 10,000 pages, and a decoded non-image-stream budget bounded by `maxExpandedBytes`. Image decoding and glyph/outline validation are not performed by this structural checker.

PDF framing, version, strict structure, page geometry, embedded-font streams and forbidden actions/forms/attachments are checked before copying bytes to the caller. PDFBox runs in a separately sandboxed Java 17 process with a 256 MiB heap, bounded native/JVM memory and the shared deadline; malformed output cannot run its parser inside the engine JVM. The validator limits objects, depth, pages and decoded non-image streams. It is not a conformance validator or a proof of visual fidelity. Its failures never publish success-shaped output. Once copying into a caller-owned stream starts, an I/O failure or cancellation can leave a partial stream; generic streams cannot be rolled back.

## Verification

The B-profile integration was locally qualified on 2026-10-12 with service image identity `sha256:5a85daff3642b41e278de5760e7f686a1ee2748f16a9c2ae80af6f8a22d3f41f`. The complete Java 17 build, architecture checks, OpenAPI export and publication metadata passed: 308 tests, zero failures/errors/skips. Actual HTTP generation exercised the default 3b profile, all three explicit B profiles, explicit standard output, version/profile rejection, private/concurrent fonts, transparent-1b watermark rejection, opaque-1b watermarks, shutdown during conversion, missing converters and deadline cleanup. Eight delivered archival PDFs additionally passed the verified official veraPDF 1.30.3 CLI against their explicit profiles; all ten service PDFs (24 pages) had the expected identifiers/versions, embedded fonts and no attachments/forms/encryption. Nine tenant/watermark raster pages matched the prior qualified standard output pixel-for-pixel; all five opaque-1b watermark pages matched opaque standard output. Rotated Code 39 references scanned at 100 and 300 DPI. That measured image is a historical local candidate, not the identity of a later published alpha or the current source container. Retained inventories and past qualification do not replace the final candidate's distribution audit.

The separate bounded research matrix covered fifteen B-profile exports across five fixtures, with equivalent official validator outcomes after excluding StAX report formatting and successful native-adapter validation. Its transparent-1b watermark case demonstrated border loss and is deliberately rejected by the production source profile, not accepted as a new renderer tolerance. Research and retained raw reports are described [here](../tools/pdf-evaluation/pdfa3a/README.md).

Run `./gradlew :nuaforma-docx-pdf:test :nuaforma-server:test` on Java 17. Tests cover unchanged DOCX delegation, discovery/ambiguity, missing converters, option filtering, independent PDF syntax, active-content rejection, font permissions, private cleanup, cancellation, process reaping, budgets, Java API use and adapter ownership. Mocked converter tests establish orchestration only, never rendering fidelity.

Tests requiring Linux executables, POSIX permissions and secure directory traversal are explicitly Linux-scoped; missing-converter, DOCX, XML, option and Java API tests do not use Unix-only absolute-path fixtures. This does not claim a qualified native PDF runtime on another operating system.

The runtime qualification uses actual LibreOffice and the actual conversion-copy implementation on fourteen synthetic documents (44 pages). Structural PDF checks, pixel comparisons, complete visual review, Word-coordinate comparisons and independent Code 39 scans at 100 and 300 DPI are separate from unit tests. See [the qualification guide](../deployment/pdf-runtime/README.md) and [research tooling](../tools/pdf-evaluation/README.md). Private font copies, generated documents, images, caches and binaries remain ignored and are not published as product assets.
