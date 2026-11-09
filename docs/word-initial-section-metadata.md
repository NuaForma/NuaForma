# Initial section starts and form-editing protection

Unreleased source qualification under [#246](https://github.com/NuaForma/NuaForma/issues/246).

## Bounded section policy

The first section may explicitly declare `continuous`: it has no preceding section to continue from. Its explicit physical page geometry and every other existing profile restriction still apply. Later sections retain the existing `nextPage`/omitted-start policy. Genuine later continuous, odd/even-page and next-column transitions remain unsupported, including when their page sizes happen to match. This does not add columns or implicitly merge sections.

Microsoft describes the section type as positioning the current section relative to its predecessor in the [section-type definition](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.sectiontype?view=openxml-3.0.1). The adapter admits the qualified initial marker and uses the same neutral page sequences as before; no shared model or public API is added.

## Editing protection and source preservation

The section's `formProt` flag controls editing, not printed content. Its standard boolean spellings and omitted-value default are validated and then omitted only from the private print interpretation. Global protection and section flags remain in the original template and qualified cleanup output. DOCX-to-DOCX handling keeps its existing behavior. This does not implement interactive PDF form fields or qualify unsupported Word controls.

See Microsoft's [form-protection definition](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.formprotection?view=openxml-3.0.1). A protection flag can be present without enforcement; the Word proofs include enforced form protection and both true/false section settings.

Original section-start/protection structure is checked before intermediate DOCX serialization: namespaces, leaf shape, known schema values and uniqueness must be valid. That check does not qualify every schema-known transition; the native reader still checks its actual position. Invalid or unqualified inputs fail with a source part/construct and no partial PDF. The Playground uses the same qualification and development diagnostics; no UI or deployment change is needed.

## Independent Word proof and golden policy

Three synthetic sources cover a two-page A4 palette/table document, a two-page Letter document with enforced form protection, and A4 followed by landscape Letter with false section-protection flags. Real Word exports preserve six reference-page rasters exactly against the corresponding baseline sources. Word's protection state was independently inspected; no source was saved by Word.

The real provider covers all four PDF profiles, independent archival validation, unchanged cleanup bytes, page content and geometry, embedded fonts, 124 marker positions/faces/colors and table fills where present. The geometry differences are at most 0.071 pt horizontally and 0.131 pt vertically. The inherited theme-color references retain their documented one-channel quantization bound; the mixed-section text colors are exact.

Four existing theme-color golden pages are reused only after independent Word and engine pixel equality. Two initially reviewed mixed-paper pages are new goldens. All 24 profile-page comparisons are exact at 96 dpi without masks; no old golden is replaced. Provenance binds source, Word export and golden hashes.

Rebuild sources with `python tools/pdf-evaluation/own-engine/build_initial_section_fixture.py --base nuaforma-docx-pdf/src/test/resources --output build/initial-section-sources`. Export independently through the documented Word font workflow. Run `./gradlew :nuaforma-docx-pdf:test --tests 'dev.nuaforma.docx.pdf.NativeInitialSectionMetadataTest'` and the normal full build. Tests never regenerate references.

## Printer tray selection (source #255)

Unreleased #255 qualifies the section's `w:paperSrc` printer-tray metadata. Optional `w:first` and `w:other` codes are bounded unsigned 16-bit decimal values (0–65535). A single correctly namespaced leaf is allowed per section. Invalid values, duplicate/foreign nodes, unknown attributes, children and nonblank text are rejected against the original source before DOCX serialization.

Only tray selection is omitted from the private print model. Explicit physical page size, orientation, margins, section starts, headers and footers retain their normal interpretation; a tray code is never treated as a paper size. Original template and qualified cleanup bytes are preserved. Printer-metrics compatibility options retain their separate restrictions.

The synthetic `printer-tray-selection/{a4,letter}` sources change only document section metadata. Independent Word exports retain the existing page pixels, and standard PDF/PDF-A-1b/2b/3b reuse existing immutable goldens. Source-shape and range boundaries are tested, including malformed originals through the provider with no partial PDF. The Playground uses the same admission and original-source diagnostics.

Rebuild with `python tools/pdf-evaluation/own-engine/build_paper_source_fixture.py --base nuaforma-docx-pdf/src/test/resources --output build/printer-tray-sources`. Run `./gradlew :nuaforma-docx-pdf:test --tests 'dev.nuaforma.docx.pdf.NativePaperSourceTest'` and the normal build. Microsoft defines [paper source](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.papersource) as printer-specific tray selection.
