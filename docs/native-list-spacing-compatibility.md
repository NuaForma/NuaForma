# Lists, minimum line spacing and section note preferences

Source work for [#231](https://github.com/NuaForma/NuaForma/issues/231), not a published release. These changes extend the existing DOCX reader and shared PDF engine without another package, dependency or rendering process. The Playground uses the same provider; cleanup retains newly qualified structures rather than removing them. Existing goldens remain unchanged.

## List definitions and source locations

Numbered paragraphs inside qualified table cells take part in the main document's source-ordered numbering. Text-frame and page-band lists remain outside this source profile. Empty `lvlOverride` elements inherit the abstract level. A bounded modern full `lvl` override retains its numbering pattern, paragraph/run properties and alignment; an explicit `startOverride` takes precedence over the embedded start.

An embedded full-level start alone does not reset an already active shared sequence. The independent Word fixture starts with `1.`/`2.`, switches to a full override with start 5 and pattern `[%1]`, prints `[3]`/`[4]`, and then resumes the original instance at `5.`.

Starting a list on a deeper level initializes omitted ancestors at their declared starting values, including when the first visible child is a bullet. A first visible numeric child can therefore be `1.1.`, followed by `1.2.` and then the first visible parent `2.`; beginning with two child bullets also makes that first visible parent `2.`. Shared abstract sequences continue across concrete list instances. Unknown instances, contradictory style associations, unresolvable patterns and counter/character budgets still fail explicitly. Diagnostics identify the relevant definition or first failing paragraph instead of an unrelated last style.

Recognized but unused `none` and legacy level definitions are structurally checked and kept inert. This does not qualify their visible layout: a used instance requiring an unnumbered or active legacy level still fails. Unknown/malformed definitions are not treated as passive metadata. Picture bullets and arbitrary counter formats remain unsupported.

## Minimum line height

`PdfParagraph.minimumLineHeight` is an optional number of PDF points. `withMinimumLineHeight(points)` returns a new paragraph; passing null removes the floor. The method retains existing paragraph policy and content. Constructors and existing `copy` descriptors remain unchanged, and copies/spacing/page-offset/paragraph-mark transformations retain the floor. Java callers use `withMinimumLineHeight(14f)` and `getMinimumLineHeight()`.

The floor must be finite and between zero and 14,400 points. It is exclusive with exact or automatic-multiple spacing. Layout uses supplied fonts' natural ascent, descent and line gap, allows larger text to exceed the minimum, and adds extra leading above the baseline when the floor exceeds the natural box. It never scales glyphs to meet the floor. A zero floor still uses the natural box. Existing unsupported mixed ordinary natural font boxes remain explicit failures.

The DOCX adapter maps `w:spacing w:lineRule="atLeast"` from twips to points, resolving document defaults, paragraph styles and direct properties. An exact/automatic override clears an inherited floor; an override changing only before/after spacing preserves it. A default font is not invented for an absent paragraph mark. The same neutral policy is available to a future ODT adapter. Pagination and reconstruction of tables, page bands, frames, notes and fields preserve the policy.

The independent image probe uses a 14-point floor with an 8-point and a 40-point opaque RGB inline image. Both retain their exact Word image bounds and following text positions; a small image is not inflated to an unrelated default-font box. The separate automatic-spacing paragraph-mark policy remains unchanged.

## Note restart boundary

The existing one-paginated-section limit remains. Within that section, `numRestart="eachSect"` has the same numbering sequence as `continuous`, including when physical pages change. It does not restart on each page. Active notes spanning multiple source sections and `eachPage` remain explicit failures. This is not general section-dependent note pagination. Both footnotes and endnotes retain the existing placement, content and resource limits.

## Independent evidence and reproduction

`nuaforma-docx-pdf/src/test/resources/list-spacing-compatibility` contains newly authored synthetic sources and genuine Microsoft Word `ExportAsFixedFormat` references. The provenance receipts bind the exact source and exported PDF hashes. Existing reference images are never refreshed by these tests. `NativeListSpacingCompatibilityTest` compares complete logical text and individual glyph positions, while the registered provider independently validates all four output profiles and embedded fonts. `NativeNoteCompatibilityTest` compares continuous and section preferences across two physical pages, on A4 and Letter, in standard PDF and PDF/A-1b/2b/3b.

Fixture authoring procedure: the DOCX ZIPs contain the complete editable WordprocessingML. The two deep-list documents declare two levels with `%1.`/`%1.%2.`, starts 1/1 or 3/5, right-aligned markers, explicit 16-point lines, and two instances sharing one abstract sequence. The full-override fixture exercises the continuation described above. The minimum-spacing source has four paragraphs with run sizes 10/20/10/10 points, floors 14/14/6/28 points, zero paragraph gaps and a 40-point text area that wraps `Q01 Q02 Q03` onto three lines. To create an additional reference, copy its source to a new fixture directory, deliberately edit the relevant XML, and export that exact file through `tools/pdf-evaluation/own-engine/export_word.ps1 -Source <source.docx> -Output <word-reference.pdf> -UpdateFields`. Retain the exporter receipt and review the rendered pages. Initial engine golden PNGs are rendered with PDFBox `PDFRenderer.renderImageWithDPI(0, 96f)` from the qualified standard candidate after comparison with Word, then frozen; tests only read them and assert exact pixel equality for every output profile. Do not replace an existing reference without maintainer approval.

```sh
./gradlew :nuaforma-pdf:test --tests '*PdfMinimumLineHeightTest' --tests '*PdfNumberingTest'
./gradlew :nuaforma-docx-pdf:test --tests '*NativeListSpacingCompatibilityTest' --tests '*NativeNumberingCompatibilityTest' --tests '*NativeNoteCompatibilityTest'
```

The independent 250-document local corpus is a compatibility probe, not a Word visual-fidelity oracle. Report the original 100 and added 150 separately; only the first blocker of each request is observable. Corpus contents and failure details are not published with this source change.
