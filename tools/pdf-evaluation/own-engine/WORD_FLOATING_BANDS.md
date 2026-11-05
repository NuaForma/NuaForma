# Floating Word stories and cleanup (#203, 0.4.0-alpha.4)

This source change addresses a cleanup report that removed an anchored image, paragraph-mark highlighting and a complete footer. Four later relationship/content-type removals were consequences of removing that footer. The private document was unavailable; all fixtures are generated from existing public synthetic resources. No private document or existing golden file is changed.

## Supported source and layout

- Qualified images with `wp:anchor` and `wrapNone` can occur in body paragraphs with notes and in header/footer stories. Page-relative physical coordinates and paragraph-relative y coordinates retain their anchor. Signed y offsets are allowed only when final placement stays on the physical page.
- Existing bounded VML rectangle and DrawingML text-frame profiles can occur in page bands. PAGE/NUMPAGES/SECTIONPAGES fields resolve for each repeated page before frame measurement. Frame bounds, rotation and content must still satisfy the existing frame profile.
- Unwrapped page-band art remains below body text in drawing order. Its position does not reserve extra body space or move ordinary footer text; measured ordinary story content still uses the existing reservation policy. Explicit strict neutral-model bands retain their height/margin checks.
- Word's zero-valued `wp14:sizeRelH`/`sizeRelV` placeholders are validated and retain the explicit `wp:extent` rectangle. Active nonzero percentage sizing is rejected rather than guessed. Unknown anchor children identify the actual child path, so cleanup can target it rather than deleting the entire drawing/story.
- Valid `w:pPr/w:rPr/w:highlight` on nonprinting paragraph marks survives cleanup. A separate Word export with `none`, yellow and green mark formatting confirms that it does not create a background behind ordinary visible text. `none` is also accepted on numbered marks; colored numbered marks and visible-run highlighting remain outside this change, because their visible backgrounds require separate qualification.
- Passive attached-template references can still be detached during cleanup. Saved internal formatting remains; no local/network target is fetched. Remaining template instructions stay literal.

Wrapped page-band images/frames, overlapping floating objects within one story, cell anchors, unqualified drawing effects and nonzero relative sizing remain explicit failures. Unwrapped body images with notes must not overlap the reserved footnote area. Source/physical-page bounds, cancellation, resource budgets, request-local state and independent PDF validation remain mandatory. Cleanup validates structural support, not missing fonts, template syntax/data or universal final-layout success.

## Regression coverage

`WordFloatingBandsTest` derives A4/Letter documents from the public Word compatibility fixture, converts large header/smaller footer EMF logos to floating drawings, adds a body logo with Word relative-size placeholders and mark highlighting, and retains actual footnotes/endnotes. It checks cleanup followed by actual provider PDF generation, literal-marker retention, paired passive-template detachment, focused unknown-child cleanup, rejected active sizing/wrapping, a repeated footer text frame with page fields, archival validation and unchanged PDF/A-1b transparency refusal.

`PdfFloatingBandsTest` checks repeated image/frame placement, fields, concurrency/isolation, negative footer-relative offsets without baseline drift, physical overflow, unsupported wrapping/overlap and body glyphs over unwrapped header art. Existing page-band, image, note and visual golden tests remain part of the complete build.

## Independent Word comparison

Real references use Microsoft Word `ExportAsFixedFormat`, version 16.0, build 16.0.20527. The source copies and exporter provenance are retained locally under `build/203-qualification`. Engine test artifacts are written under the adapter's `build/word-floating-bands`. The source OPC entry bytes must match the Word reference inputs; ZIP timestamps are not document content.

Both A4 and Letter retain two pages with matching text and position-sorted words on every page. The maximum measured word-box top difference is approximately 0.70 pt (0.25 mm). These are bounded synthetic observations, not a pixel-identity or general Word-compatibility guarantee. All six final side-by-side pages were visually reviewed for placement, drawing order, clipping, lost content and notes. Both PDFs use embedded Liberation Sans regular/bold faces; no replacement family was observed.

| Case/page | Matching words | Maximum word-box top difference | Raw changed pixels at 144 dpi |
| --- | ---: | ---: | ---: |
| A4/1 | 348/348 | 0.107 pt | 5.880% |
| A4/2 | 117/117 | 0.629 pt | 1.167% |
| LETTER/1 | 327/327 | 0.147 pt | 2.374% |
| LETTER/2 | 138/138 | 0.699 pt | 1.551% |
| FRAME/1 | 351/351 | 0.107 pt | 5.898% |
| FRAME/2 | 120/120 | 0.629 pt | 1.186% |

The FRAME case is A4 with an additional floating footer text frame and a real page field. The raw raster differences include antialiasing, small font metrics and the retained note-separator differences; they are not a relaxed test threshold. Strict normalized text and position-sorted word comparisons pass on all six pages. Existing golden images and tolerances remain unchanged.

An initial comparison exposed a 21.3 pt body displacement from incorrectly reserving unwrapped header art, plus body text painted below header artwork. Those defects were fixed rather than accepted by changing reference files or assertion thresholds.

References: [relative image width](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.office2010.word.drawing.relativewidth?view=openxml-3.0.1), [paragraph-mark properties](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.paragraphproperties.paragraphmarkrunproperties?view=openxml-3.0.1). Source semantics are qualified against the actual Word exports, not inferred from element names alone.
