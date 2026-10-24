# First own-engine DOCX fidelity proof

The subsequent [three-page report proof](REPORT.md) adds fixed tables, repeated table headers, flowing pagination and shared page headers/footers with PAGE fields. The [advanced table trials](ADVANCED.md) then exercise merged cells, nested tables and rows spanning pages. The [frame trials](FRAMES.md) add page-band images, text boxes and rotated barcodes. The [paragraph pagination trial](PAGINATION.md) adds keeps, widow/orphan control and explicit page breaks in a ten-page Word comparison. The [lists trial](LISTS.md) adds native numbering, chapter styles, restarts and hanging indentation. The scope and measurements below describe the original letter; the later guides own their additional supported subsets and per-page evidence.

This source-only experiment converts a real synthetic DOCX to ordinary PDF 1.7 through a DOCX reader in `nuaforma-docx-pdf` and the neutral model/layout/writer in `nuaforma-pdf`. Microsoft Word is used only to export an independent reference. No Word, LibreOffice or external renderer participates in the own-engine conversion. No new third-party dependency, provider switch, hosted-input qualification, archival profile or publication is introduced.

## Scope and placement

The reader and manual entry point deliberately live in `nuaforma-docx-pdf/src/test/kotlin/dev/nuaforma/docx/pdf/ownengine` while the supported input profile is being established. They are not in the published library JAR and are not registered with provider discovery. `nuaforma-docx-pdf` uses `nuaforma-pdf` as a **test-only** dependency for this proof; its production dependency graph remains unchanged. Moving the adapter into production and choosing a public conversion API are later reviewed steps.

The proof reads one section with explicit page size/margins, paragraph and character styles (defaults, `basedOn` inheritance, style toggles and direct overrides), explicit Liberation Sans faces/sizes/RGB colors, left/center/right alignment, physical left/right/first-line or hanging indentation, exact line spacing and collapsed paragraph spacing. A single inline PNG in an image-only paragraph retains its explicit size. The neutral engine model now supports inline image items and paragraph geometry, without OOXML/ODF types or property names.

This is intentionally a strict, small input profile rather than a general Word importer. Other parts/features, including tables, headers/footers, lists, fields, floating/rotated/cropped/effected images, themes, tabs, underlining, tracked changes, mixed text/image paragraphs, empty paragraphs, automatic/multiple line spacing and multiple sections are outside the proof. Unsupported meaningful content fails explicitly. The ZIP/XML reader has byte/entry/depth/node bounds, no part extraction and no external resource loading, but remains an in-process test adapter for trusted synthetic input. It is not the planned hostile-input isolation boundary.

The experimental exact-line policy puts the baseline at 80% of the line box. This deterministic policy matches the reference fixture closely; it is not a claim that all Word font/line-layout policies have been implemented. Other text scripts, general shaping, font fallback, pagination, tables, PDF/A and ODT import remain future work. The prototype model/API is still a development draft.

## Reproduce the DOCX and Word reference

`build_fixture.py` uses only Python's standard library, a fixed ZIP timestamp and the existing approved project logo. It builds a Dutch business letter with inherited title formatting, a right-aligned date, bold/italic and colored text, accents/euro, a centered callout and an indented wrapping paragraph. It does not put measured reference coordinates or PDF data into the DOCX or reader. The first logo paragraph uses a 72-point line box to keep the full 54-point logo inside the page margins in Word.

From the repository root:

```sh
python3 tools/pdf-evaluation/own-engine/build_fixture.py build/own-engine-proof/source.docx
```

On a machine with Microsoft Word installed, export that exact DOCX using the supplied PowerShell helper:

```powershell
./tools/pdf-evaluation/own-engine/export_word.ps1 -Source build/own-engine-proof/source.docx -Output build/own-engine-proof/word-reference.pdf
```

The helper creates a private invisible Word instance, disables macros, opens the synthetic DOCX read-only, calls Word's [`ExportAsFixedFormat`](https://learn.microsoft.com/en-us/office/vba/api/word.document.exportasfixedformat), and closes its document/application. It refuses to overwrite an existing reference. Choose a new output filename when deliberately regenerating a reference; investigate differences before replacing a reference. The adjacent provenance JSON records the actual Word version/build and SHA-256 of source and PDF. Word is a reference-authoring tool here, not a library/build/CI requirement.

## Run the own engine and compare

Supply the same installed Liberation Sans regular/bold/italic font files used for the Word reference in the ignored `build/own-engine-proof/fonts` directory, named `LiberationSans-{Regular,Bold,Italic}.ttf`. Keep font license/provenance; these local caller files are not distributed in a production artifact. The portable tests instead use the existing licensed test fixtures and never require desktop Word or host font discovery.

Run with the project's Java 17/Java 25 toolchains:

```sh
./gradlew :nuaforma-docx-pdf:test --tests 'dev.nuaforma.docx.pdf.ownengine.*'
./gradlew :nuaforma-docx-pdf:renderOwnEngineProof
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-proof/word-reference.pdf build/own-engine-proof/own-engine.pdf build/own-engine-proof/comparison --source build/own-engine-proof/source.docx --font-dir build/own-engine-proof/fonts --word-bounds
```

The comparison tool uses the already available QA tools Pillow, pypdf and Poppler. Use `--poppler-dir <directory>` if Poppler is outside `PATH`. It verifies the Word provenance hashes when `--source` is supplied, independently extracts content/geometry, renders both PDFs at 144 dpi and writes a side-by-side view, 50% overlay, amplified absolute difference and JSON report. It does not shift or scale content to improve the result. Minor canvas-size differences are padded only on the right/bottom. Pixel counts use a 24/255 per-channel threshold; the whole-page changed-pixel fraction is a diagnostic, **not a similarity score or an acceptance criterion**, because white space dominates a letter. Inspect the pages and differences as well as the content/geometry checks.

Geometry uses the available pdfplumber QA tool's text matrices, independently of pypdf content extraction, including across graphics-state changes. The optional `--word-bounds` check additionally compares word rectangles across each page. The report also records Poppler's actual version and, when `--font-dir` is supplied, the caller font hashes. These QA tools are outside the engine dependency graph.

`generateOwnEngineFixture` regenerates the same DOCX into the PDF provider's ignored build directory for tests. Normal tests do not invoke Word, create the external reference or compare against a fabricated reference. Tests cover actual DOCX interpretation and PDF output, inheritance/direct overrides, image/text geometry, unsupported-feature rejection and malformed/oversized input.

## Measured first comparison

Measured on 2026-10-24 against Microsoft Word 16.0, build `16.0.20430`, using its actual local PDF export. The exact deterministic source SHA-256 was `35579a7316b6e37363294a9335aabae2ee57825ed4a287be0d76c61c78c46246`. Generated evidence remains ignored under `build/own-engine-proof`; the run's reference was named `word-reference-v2.pdf`.

| Check | Observed result |
|---|---|
| Pages and content | One page each; identical normalized searchable text, including accents and euro |
| Wrapping | All 12 nonempty text lines contain the same text |
| Line-start displacement | Maximum 0.093 pt horizontally |
| Baseline displacement from page top | Maximum 0.110 pt vertically |
| Independent word rectangles | Same 90 words; maximum word-start displacement 0.167 pt, word-end displacement 0.216 pt and word-box top displacement 0.075 pt |
| Logo physical size/location | Both 54 × 54 pt at x = 56.7 pt; page-size rounding accounts for approximately 0.02 pt in the PDF y coordinate |
| Page dimensions | Word 595.32 × 841.92 pt; engine 595.30 × 841.90 pt from the DOCX twips |
| Pixel difference | 18,720 pixels above the threshold at 144 dpi, about 0.933% of the full page; not a quality score |
| Visual inspection | Side-by-side, overlay and difference inspected: logo, alignment, indentation, colors, bold/italic and spacing retained, without clipping, overlap or missing content |

The outputs are close, not pixel-identical. Word exports the 11-point body as 11.04-point PDF text and rounds glyph positioning; the engine uses the DOCX's 11 points and measured cmap advances. Word resamples the logo to 149 × 149 pixels; the engine retains the 1254 × 1254 source image at the same physical size. These differences remain visible in the amplified difference image; the engine does not degrade the source logo to match Word's resampling. The line-start/baseline measurements are anchor measurements, not a bound on every glyph's horizontal displacement.

This proves the end-to-end path for this controlled letter only. It does not establish fidelity for arbitrary existing DOCX files, justify replacing the current converter or qualify any PDF/A profile. Playground, REST/provider behavior, release workflows and website remain unchanged.

The [tabs and leaders trial](TABS.md) adds two actual Word-exported pages, 49 independently audited field anchors and unmasked raster comparisons.
