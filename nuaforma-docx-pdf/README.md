# NuaForma DOCX/PDF provider

The registered `DocxPdfProvider` reuses `nuaforma-docx` for template processing and DOCX output, translates supported filled DOCX into the neutral model, and writes PDF through `nuaforma-pdf`. Rendering takes place in the hosting JVM with request-local state. There is no LibreOffice backend or external document-renderer fallback. The native source profile is not yet published.

## Output and validation

Standard PDF 1.7 and PDF/A-1b, 2b and 3b use the existing profile identifiers and version rules. The absent profile remains `pdfa-3b`. PDF/A-1b rejects transparent images; there is no silent flattening or profile downgrade. Selected standalone TrueType faces are embedded subject to their permissions. Use exact family/face resources or the configured deployment catalog; no system-font substitution is performed.

Every delivered native PDF requires independent structural/font/active-content checks. Archival output additionally uses the exact requested veraPDF flavour unless the trusted operator explicitly disables conformance checking. The existing immutable, default-on operator policy remains separate from request options. Disabling conformance checking retains structural checks and does not establish PDF/A compliance. PDF/UA, PDF/A level A and attachments remain unsupported.

The separate validator uses Java 17. Container validation retains a private directory, bounded JVM/process, read-only runtime/files, no inherited secrets, denied networking and cleanup. An unavailable or failed validator never causes unvalidated delivery or a renderer fallback. Standard DOCX output remains available without the PDF validator.

## Supported source profile

The native profile is deliberately bounded to the qualified shared layout features: explicit page geometry and fonts, supported inherited paragraph/run/table styles, multilevel lists, tabs, next-page sections, page-band variants and decimal fields, standalone inline PNG-image paragraphs, supported frames/rotations, and qualified fixed/merged/nested/fragmented tables. Roadmap part 10 adds paragraph-bound DrawingML PNG images, rectangular text wrapping and source cropping; [qualification](../tools/pdf-evaluation/own-engine/ANCHORED_IMAGES.md) records its current limits. Language support is per qualified script/font combination; this is not universal Word compatibility. PNG/JPEG image pipes reuse existing DOCX image normalization before native conversion, within standalone image paragraphs. Mixed inline text/image paragraphs remain explicitly unqualified.

Explicit hyperlinks and matched bookmark ranges support inert HTTP/HTTPS/mailto actions, internal named targets and flat user-bookmark outlines. Wrapped links and links inside qualified tables retain their text style, including single underlining. Missing/duplicate targets and unsafe URI schemes reject; conversion never opens or downloads a link. See the [navigation profile](../tools/pdf-evaluation/own-engine/NAVIGATION.md) for source and layout restrictions.

The [contents/reference profile](../tools/pdf-evaluation/own-engine/REFERENCES.md) adds bounded decimal SEQ counters, REF/PAGEREF text/page references, body page totals and a heading-based clickable TOC. Simple and complete complex Word fields are translated into symbolic neutral-model values; saved field results supply formatting only. Generated contents retain explicit TOC paragraph styles and logical heading/caption/reference roles. Cross-paragraph text ranges, general field expressions, custom TOC selectors and general content controls remain unqualified. Preparation for future PDF/A level A does not change the available output profiles.

Word authoring metadata is interpreted separately from print properties. Known editor identifiers, style-list metadata and inactive script fallback metadata may be ignored; unsupported effective settings, remote resource loading, revisions, multiple columns, unqualified fields/anchoring and other visible constructs reject explicitly. Initial production-adapter budgets include 8 MiB compressed/expanded DOCX, 4 MiB per part, 64 parts, 32 pages and the shared model's bounded content limits; stricter caller limits still apply.

Exact line boxes that would clip the supplied font bounds reject explicitly. The native smoke test qualifies 11-point Liberation Serif at a 16-point exact line; its former 13.2-point line remains a negative case. PDF version checks use the effective document/catalog version, together with independent structural and archival validation, rather than the header line alone.

Requested PDF watermarks and `paragraphDefaults` fallbacks are not yet qualified for the native route and reject explicitly. Embedded source-font packages and arbitrary Word-saved documents are not universally supported. These are remaining compatibility gaps, not permissions to omit styling.

## Embedded configuration

`DocxPdfProvider(NativePdfConfiguration(...))` provides explicit embedded composition; `NativeDocxPdfProvider` exposes the same native implementation for direct use. `NativePdfFont` validates font identity and holds an immutable byte snapshot. Supply an independent `PdfProcessValidator` with the reviewed Java 17 distribution. The configuration bounds concurrent conversions with immediate admission failure rather than an unbounded queue. Each call checks the hosting cancellation/deadline and limits PDF output before publishing bytes to the caller.

The default registered provider captures trusted JVM properties and deployment-font snapshots at construction. The container sets `nuaforma.pdf.validator.java`, `nuaforma.pdf.validator.directory` and `nuaforma.pdf.validator.launcher`; `nuaforma.pdf.parallelism` retains its bounded operator setting. Libraries do not read environment variables. `nuaforma.pdf.pdfaValidation` retains the existing strict `true`/`false`, default-on policy. The obsolete renderer launcher does not select another backend.

Explicit embedded composition uses `NativePdfConfiguration`; zero-argument discovery and content/value-pipe overloads capture the same native configuration. Default profile identifiers remain stable. Qualify actual templates against the supported source subset before adopting this evaluation version.

## Operator conformance policy (unreleased issue #93 source)

Only exact `true` or `false` is accepted for the trusted `nuaforma.pdf.pdfaValidation` property; the absent value enables it. The common server/Playground entry point maps `NUAFORMA_PDFA_VALIDATION` to this immutable startup policy. Requests cannot override it. Disabled conformance retains structural/font/active-content validation and never claims verified archival compliance. All validator dependencies and their notices remain bundled.

## Qualification and references

The engine's reviewed golden files and Word-export comparison documents remain protected references. Native adapter tests additionally exercise template expansion, a Word-saved DOCX, branding/fonts, page layout, actual independent validation, limits, concurrency and recovery. LibreOffice-specific orchestration helpers have moved out of production sources into test-only historical comparisons; their tests do not qualify the native backend.

The [registered-provider integration trials](../tools/pdf-evaluation/own-engine/NATIVE-INTEGRATION.md) retain six new Word-matched pages, including an 80-row conditional report, with per-page content/geometry results and visual review.

Full repository/consumer, Word/golden, REST/Playground and native AMD64/ARM64 checks qualify the bounded source profile. Each release still requires exact-candidate validation, signing, source/notices and website handoff. See the [roadmap](../docs/architecture/own-engine-roadmap.md) and [shared engine guide](../nuaforma-pdf/README.md).
