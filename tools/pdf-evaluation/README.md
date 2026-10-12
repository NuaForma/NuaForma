# PDF renderer evaluation

Issue #10 research only. This standalone Java 17 Gradle build is deliberately outside the product module graph and publication. It is not a provider, an untrusted-input sandbox or approval to adopt a production backend. See [the findings](../../docs/pdf-backend-evaluation.md).

## Reproduce the fixtures

From the repository root, run the product build first. If fixture output is missing because Gradle considers tests up to date, explicitly regenerate the fixture builders:

```sh
./gradlew :nuaforma-docx:test --tests dev.nuaforma.docx.DocxDocumentsTest --tests dev.nuaforma.docx.DocxProviderTest --tests dev.nuaforma.docx.DocxFontsTest --tests dev.nuaforma.docx.DocxWatermarksTest --rerun-tasks
./gradlew -p tools/pdf-evaluation installDist --console=plain --warning-mode=fail
```

Run on Java 17. The repository's Gradle Wrapper is reused; do not install another Gradle version. The independent build pins docx4j-export-fo and JAXB ReferenceImpl to 17.1.0 and locks its resolved dependencies. `--write-locks` is only for a deliberate dependency change, not a normal evaluation run.

The fixture-generating test class names above must match the checked-in tests. The synthetic DOCX files live in `nuaforma-docx/build/{docx-reference,docx-generation-reference,font-reference,watermark-reference}`. They are generated and ignored, not customer documents. Do not run the probe on untrusted input.

## Convert and inspect

The installed `nuaforma-pdf-evaluation` launcher accepts `input.docx output.pdf [font files...]`. Each execution must have a private temporary directory and private `user.home`, a process deadline and a fixed heap. The launcher sets `-Xmx512m` and headless Java; the caller must enforce the deadline. Register font files explicitly; the probe disables automatic host font discovery. Font substitution and library defaults still require independent checks; this is not a production font policy.

The primary probe uses the XSL exporter. Set the JVM property `evaluation.visitor=true` to compare the visitor exporter. The reproduction run includes that additional comparison for `font-reference/generated.docx` and `watermark-reference/concept.docx`. Neither exporter preserves the rotated barcode or the watermark in these fixtures. Preserve the FO dump for diagnosing lost content; never enable document dumps by default in production.

For LibreOffice, execute `soffice` headlessly with a fresh `-env:UserInstallation=file:///...` profile for each conversion, `--headless --nologo --nodefault --norestore --convert-to pdf:writer_pdf_Export --outdir <private-output-directory> <synthetic.docx>`. Never share the user's desktop profile or overwrite the DOCX. Pin and record the executable's actual version. No macros, external resources or customer input are part of this comparison.

`compare.ps1` is a research orchestrator for the current Windows/WSL comparison, not a runtime requirement. It takes renderer paths and the repository mapping as parameters, uses fresh output/profile directories, refuses existing run directories, bounds owned processes and records source hashes, exit codes and elapsed times. FOP executes under an additional 90-second process timeout. It rasterizes every resulting PDF through Poppler at 100 DPI. Other environments can invoke the two renderers directly with the same fixture list. Workstation-specific invocation belongs in ignored local instructions.

Use `inspect_sources.py` to enumerate explicit font families in the synthetic DOCX files. After every conversion has finished, run `inspect_results.py <run-directory> --decoder-directory <approved-zxing-cpp-directory>` using Python with pypdf, Pillow and the previously approved ZXing-C++ 3.1.1 decoder. It records page geometry, PDF headers, embedded-font descriptors, extracted text and barcode scan results. Incomplete PDFs must fail inspection. Rendered pages still need visual review: metadata and a successful scan do not establish correct placement or fidelity.

`review_dependencies.py` inspects bundled font notices and queries all locked Maven coordinates against OSV. Network access is required for that explicit research query, not for conversion. The raw response stays ignored under `build`. No findings means no database matches at that moment, not a security guarantee.

Keep DOCX, PDF, FO, raster pages, private profiles, timing logs and generated reports under ignored `build`. Do not commit them, install fonts globally, distribute proprietary test fonts, or treat this research runner as a secure service.

## Conversion-only copies

The approved follow-up is research, not a change to DOCX generation. `conversion_copy.py` changes a private copy of matching synthetic VML text boxes only: explicit positive point dimensions, shape rotation 90 degrees and bottom-to-top text flow. It exchanges width/height and resets shape rotation while retaining text orientation, runs and fonts. The intentionally narrow prefix-based XML patcher is not suitable for arbitrary uploads or production OOXML normalization. Matching other dimensions fails; nonmatching objects, including WordArt watermark shapes, remain untouched. This experiment restores scanability but fails the frame/placement fidelity gate.

From the repository root, use a fresh destination and Python with the approved QA dependencies:

```sh
python -m unittest discover -s tools/pdf-evaluation -p test_conversion_copy.py -v
python tools/pdf-evaluation/prepare_copies.py . tools/pdf-evaluation/build/conversion-copies
```

Run `compare.ps1` with the usual supplied executable paths, a fresh `RunName`, `-Backends libreoffice` and `-ConversionCopiesDirectory <absolute-copy-directory>`. It hashes the original and conversion input before and after each owned process and fails if either changes. FOP is deliberately excluded from this LibreOffice-specific experiment. After the entire conversion run finishes:

```sh
python tools/pdf-evaluation/inspect_results.py tools/pdf-evaluation/build/compatibility-v1 --decoder-directory <approved-decoder-directory>
python tools/pdf-evaluation/assess_copies.py . tools/pdf-evaluation/build/conversion-copies tools/pdf-evaluation/build/comparison-v2 tools/pdf-evaluation/build/compatibility-v1
```

The latter checks original DOCX hashes, page dimensions/counts, exact paired rasters and reports every page's changed pixel bounds. Inspect every distinct changed page; reuse an earlier review only when unchanged/duplicate pixels are verified. `copies.json`, `inspection.json` and `differences.json` stay ignored.

`probe_vml.py <synthetic-isolated-embedded.docx> <fresh-build-directory>` creates controlled geometry, flow, wrapping, rectangle and shape-type variations of the existing `MarginBox` fixture. It deliberately includes failing and hard-coded-position counterexamples; none is a production strategy. Convert diagnostic copies using a private LibreOffice profile and an owned-process deadline, then rasterize each PDF at 150 DPI with `<PDF-stem>-<page>.png` names. `inspect_results.py <parent-of-probe-directories> --decoder-directory <approved-decoder-directory> --named-images` records their fonts, full-page decode results, barcode orientation and position. Do not count a horizontal decode, missing frame or displaced frame as a passing layout check, and do not update expected DOCX fixtures to conceal differences.

## Native geometry and Word oracle

`drawingml_copy.py` is another trusted-fixture-only strategy, not a production normalizer. It replaces the specifically measured right-margin VML profile with a native DrawingML text box, preserves text runs and all other package parts, and rejects matching unknown attributes, nonzero insets, unmeasured alignments or bounds wider than the explicitly supplied right margin. It validates the resulting document XML before creating the destination. It must not be used on arbitrary uploads or as a silent fallback.

```sh
python -m unittest discover -s tools/pdf-evaluation -p 'test*copy.py' -v
python tools/pdf-evaluation/prepare_copies.py . tools/pdf-evaluation/build/<fresh-copies> --strategy drawingml
```

Run the existing LibreOffice-only `compare.ps1` workflow with those copies in a fresh run directory, then run `inspect_results.py` and `assess_copies.py` as above. Do not interpret cached product tests or independent scans as a renderer-fidelity pass.

`probe_geometry.py <isolated-source.docx> <fresh-directory>` creates controlled page, margin, dimension, alignment and wrapping variants. `probe_drawingml.py` creates additional native-text orientation/anchor counterexamples. `render_probes.ps1 -Directory <fresh-probes> -LibreOffice <executable> -PopplerDirectory <directory>` bounds an owned batch process, uses a private profile, verifies input hashes and renders all produced PDFs at 150 DPI. A broken/horizontal/cropped variant is diagnostic evidence, not supported behavior.

For an explicitly authorized local interoperability check, `word_geometry.ps1 -Source <synthetic.docx> -Directory <fresh-word-oracle>` opens only that trusted fixture read-only, disables macros, exports a Word PDF, records shape metadata, closes its own document/application and verifies the input hash. `-CanonicalCopy` additionally saves a separate private Word serialization for inspecting inferred properties; it never overwrites the source. Word remains an optional Windows QA oracle, not a NuaForma production dependency.

After producing a Word oracle under `<word-root>/<fixture>/reference.pdf` for every entry in `copies.json`:

```sh
python tools/pdf-evaluation/assess_geometry.py <copies-directory> <conversion-run> <word-root> --decoder-directory <approved-decoder-directory>
python tools/pdf-evaluation/verify_word_rasters.py <copies-directory> <word-root> --poppler <poppler-directory>
```

The first independently compares vector frame edges, native glyph origins, page geometry and full-page barcode results. The second renders fresh Word PDFs at the original reference DPI and compares every page against previously reviewed Word rasters. Both reports stay ignored; different pixels require visual review. These checks report differences rather than quietly introducing a passing tolerance.

`--strategy drawingml-inline` enables the additional ordinary-inline-box diagnostic. Without explicit paragraph fallback values, it restores the border but still fails the complete layout gate on templates with missing paragraph defaults. `probe_defaults.py <conversion-copy.docx> <private-canonical-word.docx> <new-diagnostic.docx>` materializes only the observed paragraph-default block in another private diagnostic copy, refusing to override explicit defaults. This is for identifying the source of spacing differences, not a universal Word-default policy. Do not use a workstation's inferred settings or Word itself in a runtime conversion pipeline. Neither diagnostic strategy is an available product feature.

## Explicit paragraph fallback experiment

The approved opt-in experiment accepts caller-selected values, with no Word invocation and no inferred host preferences. Supply both fields explicitly; omission leaves styles untouched. `paragraph_defaults.py` adds only missing document-default spacing fields. Existing after-spacing (including line-based/automatic modes), line values, nonautomatic line rules, fonts and paragraph/style overrides remain leading. Its prefix-based XML patcher is limited to trusted fixtures and is not an upload parser or a public engine API. Research bounds are 0..144 points after-spacing in whole twips and 1..2400 automatic line-spacing units; these are not a stable product contract.

```sh
python -m unittest discover -s tools/pdf-evaluation -p 'test_*.py' -v
python tools/pdf-evaluation/prepare_copies.py . tools/pdf-evaluation/build/<fresh-copies> --strategy drawingml-inline --paragraph-after-points 8 --paragraph-line-240ths 278
```

The shown 8 points and 278/240 line spacing are explicit example selections for the measured fixtures, not universal Word defaults. Convert the copies through the same bounded LibreOffice-only workflow, then run `inspect_results.py` and `assess_copies.py`. Add `--inline-boxes` to `assess_geometry.py` to independently measure the fixture's ordinary 260-by-35-point frame and modern 360-by-36-point frame, including a no-overlap assertion. Frame coordinates are measured from the PDF, never inserted as correction constants.

`review_pages.py <conversion-run>` creates fresh ignored QA sheets and `review/coverage.json`, retaining full page pixels and recording exact pixel duplicates. Inspect all unique pages; scans and coordinate reports do not replace visual review. The full explicit-fallback run contains 14 PDFs, 44 pages and 32 unique page rasters. The ordinary frames now match Word exactly, but modern frames remain vertically displaced by 1.69–1.70 points. The user temporarily accepted this measured limitation, tracked in [issue #32](https://github.com/NuaForma/NuaForma/issues/32), and authorized merging the research. No general production fidelity pass or backend adoption is claimed.
