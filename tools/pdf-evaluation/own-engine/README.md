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
| Logo physical size/location | Both 54 Ã— 54 pt at x = 56.7 pt; page-size rounding accounts for approximately 0.02 pt in the PDF y coordinate |
| Page dimensions | Word 595.32 Ã— 841.92 pt; engine 595.30 Ã— 841.90 pt from the DOCX twips |
| Pixel difference | 18,720 pixels above the threshold at 144 dpi, about 0.933% of the full page; not a quality score |
| Visual inspection | Side-by-side, overlay and difference inspected: logo, alignment, indentation, colors, bold/italic and spacing retained, without clipping, overlap or missing content |

The outputs are close, not pixel-identical. Word exports the 11-point body as 11.04-point PDF text and rounds glyph positioning; the engine uses the DOCX's 11 points and measured cmap advances. Word resamples the logo to 149 Ã— 149 pixels; the engine retains the 1254 Ã— 1254 source image at the same physical size. These differences remain visible in the amplified difference image; the engine does not degrade the source logo to match Word's resampling. The line-start/baseline measurements are anchor measurements, not a bound on every glyph's horizontal displacement.

This proves the end-to-end path for this controlled letter only. It does not establish fidelity for arbitrary existing DOCX files, justify replacing the current converter or qualify any PDF/A profile. Playground, REST/provider behavior, release workflows and website remain unchanged.

The [tabs and leaders trial](TABS.md) adds two actual Word-exported pages, 49 independently audited field anchors and unmasked raster comparisons.

The [style and spacing trial](STYLES.md) qualifies effective run/paragraph/table styles, Latin theme fonts, explicit overrides and automatic line/contextual paragraph spacing.

The [first international increment](INTERNATIONAL.md) compares Russian, Ukrainian and Greek paragraphs, tables and lists with Word, plus independent PDFium extraction/search. The [bounded Hebrew increment](HEBREW.md) adds unpointed RTL text and numbers in paragraphs, tables and page bands. Mixed strong directions, Hebrew marks and Arabic shaping remain unqualified.

The [paper-format matrix](PAGE_FORMATS.md) adds eight genuine Word reports: A4, A3, US Letter and Legal, each in portrait and landscape, including asymmetric margins and Cyrillic/Greek text.

The [mixed-size trial](SECTIONS.md) adds one document with A4/Letter-landscape/A3 content ranges, inherited default page bands and continuous PAGE numbering over the neutral page-sequence model.

The [first/even/odd band trial](PAGE_MASTERS.md) adds two Word reports with resolved variant stories, header logos, independent inheritance, intentional blank bands and explicit switch resets.

The [page-field trial](FIELDS.md) resolves PAGE/NUMPAGES against the final document-wide total, with a 13-page Word report and explicit cache-format/overflow limits.

The [sequence-numbering trial](NUMBERING.md) adds decimal restarts/continuation, SECTIONPAGES, displayed-number band parity and explicit padding policies verified against Word with both shared and different even/odd bands.

The [cell-alignment trials](CELL_ALIGNMENT.md) cover top/center/bottom alignment with natural row heights, spans and atomic nested tables. The [natural-width trials](NATURAL_WIDTHS.md) add bounded content-based column sizing, including later wide cells, styles and A4/Letter pagination; general AutoFit redistribution remains planned. The [solid cell-border qualification](CELL_BORDERS.md) adds per-side solid strokes/removal with one resolved style per source table. The [table-combination proof](BORDER_COMBINATIONS.md) additionally covers spans, one nested level, whole-row pagination and Letter. The [row-fragment proof](BORDER_FRAGMENTATION.md) extends this to explicitly splittable TOP-aligned rows with horizontal spans on A4 and Letter. Conflicting styles and oversized non-splitting custom-border rows remain unqualified.

## Alpha output profile qualification

Roadmap part 15 adds standard PDF 1.7 and independently validated PDF/A-1b, 2b and 3b to the shared engine. Earlier proof sections describe their original ordinary-PDF scope. This does not switch the production DOCX provider or publish an alpha.

After preparing the existing trusted source/font corpus, create a TSV with four tab-separated columns: a safe case name, absolute source DOCX path, `true`/`false` for expected PDF/A-1b transparency rejection, and `SUBSET`/`FULL` font embedding. Use a fresh output directory:

```sh
./gradlew :nuaforma-docx-pdf:qualifyOwnEngineProfiles \
  -PownEngineProfileCases=/absolute/path/cases.tsv \
  -PownEngineProfileOutput=/absolute/path/new-output
python3 tools/pdf-evaluation/own-engine/audit_output_profiles.py \
  /absolute/path/new-output /absolute/path/profile-audit.json --baseline-root build
```

The test-only adapter renders each case through the same neutral model and all four explicit profiles. Every successful archival output must pass the real isolated Java-17 veraPDF validator before writing. `validation.tsv` records the SHA-256 and size of those exact bytes; expected 1b transparency rejections leave no PDF. The independent audit rechecks hashes, headers, metadata, ICC, font streams, Unicode extraction and page geometry. PDFium renders every page at 144 dpi and requires exact pixel equality with that case's standard PDF: no masks, alignment or tolerance.

`build_profile_fixture.py source.docx opaque-source.docx` produces a separate controlled test source with white-backed RGB PNGs. It is a fixture-authoring tool, **never** converter behavior: obtain fresh genuine Word exports for these changed sources using `export_word.ps1`, preserving source/PDF provenance receipts. Do not compare changed source bytes against the original source's Word receipt. Original transparent sources, their Word exports and all reviewed comparison images remain intact. Opaque sources establish positive 1b behavior; original sources establish 2b/3b transparency and explicit 1b rejection. Do not silently flatten images to obtain a passing file.

The profile audit uses the existing manual QA environment's Pillow (fixture authoring), pypdf, PDFium and numpy; these do not enter product dependencies. Independently render and review the new Word comparisons using `compare.py`; the profile-pixel audit supplements that layout evidence. Run the full build, independent consumer/publication checks and previous Word corpus after changes. No profile qualification licenses a release, a production-provider switch or a PDF/UA claim.

### Recorded alpha qualification (2026-10-25)

Fifty cases produce 155 PDFs / 591 pages: the original 44-document corpus, four separately authored opaque-image variants and two full-font variants. Every one of the 105 archival files passed the real explicit-flavour Java-17 veraPDF check. Independent inspection rechecked their exact recorded hashes, metadata, output intents, font streams, extracted text and physical page sizes. All 398 archival pages are pixel-identical to their corresponding standard PDF at 144 dpi in PDFium, without masks or tolerance. The original 179 standard pages also match their previous engine output pixel for pixel.

| Profile | Successful files | Pages | Expected transparency rejection |
|---|---:|---:|---:|
| Standard PDF | 50 | 193 | 0 |
| PDF/A-1b | 5 | 12 | 45 |
| PDF/A-2b | 50 | 193 | 0 |
| PDF/A-3b | 50 | 193 | 0 |

Four new genuine Word exports (build `16.0.20527`) cover 11 opaque-source pages. Every page has the same normalized text; all side-by-side, overlay and difference views were reviewed at original render size. The report's 5,023 visible glyphs preserve order, face, size and color, with unchanged source inventories; its last page correctly has no repeated table header. The Letter-landscape proof retains the previously documented single adjacent-line word-wrap difference on page 1, with unchanged page text and line positions. Its independent paper/source/search/geometry audit passes the existing explicit one-word-wrap check. No new layout exceptions, masks or image alignment are introduced. Fifty-five reviewed PNG hashes are retained alongside the local evaluation receipts.

| Opaque source | Page | Word/engine pixels differing by more than 24/255 | Observation |
|---|---:|---:|---|
| Confirmation letter | 1 | 0.933% | Existing small glyph/logo raster differences |
| Report | 1 | 2.212% | Preserved layout, table and page bands |
| Report | 2 | 2.601% | Preserved rows and repeated header |
| Report | 3 | 1.428% | Preserved final paragraphs and footer |
| Images in both page bands | 1 | 7.313% | Dense text retains order and positions; both logos present |
| Images in both page bands | 2 | 7.643% | Dense text retains order and positions; both logos present |
| Images in both page bands | 3 | 1.422% | Correct short final page and both logos |
| Letter landscape | 1 | 1.552% | Existing one-word line-wrap difference |
| Letter landscape | 2 | 1.517% | Preserved rows, header and footer |
| Letter landscape | 3 | 1.578% | Preserved pagination and table end |
| Letter landscape | 4 | 1.658% | Preserved closing paragraphs |

The percentages describe raw Word-versus-engine raster differences, including font antialiasing; they are not the profile-versus-standard results, which are exactly equal. There is no clipping, overlap, missing text or changed pagination in these new comparisons.

| Opaque fixture | Source SHA-256 | Word PDF SHA-256 |
|---|---|---|
| own-engine-frames/bands | 4bbc832a864a3f2f2482168d4c9404783003c40bb97206c051ea0041a0200423 | bd2f3372210ddbd2e32b50e53eaf632cdbe55c5561c573be8f29795d5c5de225 |
| own-engine-page-formats/letter-landscape | 8b0c9b8fee2a4a04bb0ce8d82a0b32dabbce4dd973b0be18cf77a218b32a694a | eac534cd1be119898ea8291c10519d08eff24394714ac9b100e0eec6b28f0ecb |
| own-engine-proof | 00a7092c2ecad84b15d55d473a82f2a9defff743c016aa948208ac1415cc55eb | 8d61ae856c188aacefc187678335163128f91c269fed04ab23b2ec4ff7abb0af |
| own-engine-report | d4cd9292967cec7b445e5229c003a20f4a44ea82d266af5f841bfac21f9272ee | f1724a79b0fa569a93d9f9be8a3dc74ddc1be7f48ebfb290d69d6f2918be3649 |

A separate full-font audit confirms that all seven full-embedding PDFs contain byte-identical original caller font programs for the used regular, bold and italic faces. Their font names have no subset prefix. The full-font confirmation letter and two-page Hebrew proof also render pixel-identically to their corresponding subset-font standard PDFs, establishing the same appearance across embedding modes.

The final local `build verifyLibraryPublication` succeeds with 736 JVM tests and zero failures/errors/skips, together with the separately completed five independent Java/Kotlin consumer checks. The architecture policy permits veraPDF only in its assigned DOCX/PDF or shared-PDF adapters; three added negative fixtures continue to reject direct, wrong-adapter and public-API access. A workstation startup timing failure was investigated with unchanged tests and resolved using checksum-verified, byte-identical compiled outputs on native storage; no deadlines or assertions were relaxed. Java 17 library/validator and Java 25 server boundaries are preserved. Playground, existing provider defaults, public releases, website and Sonar/workflow settings are unchanged.

Final source/content/font/geometry/barcode/border audits pass for the original 44 documents / 179 Word page pairs. All 895 previously reviewed PNGs remain unchanged after regenerating every original standard-PDF proof; the 55 newly reviewed opaque-source comparison PNGs also retain their frozen hashes. Together this retains 48 Word-reference documents / 190 page pairs / 950 reviewed comparison images. No reference bytes, comparison gates or accepted-difference policies were changed to obtain this result.
