# Font-independent checkbox qualification (#149)

Source feature; not published in alpha.7. The [user and contract guide](../../../docs/checkbox-content.md)
defines arguments, geometry, insertion boundaries and contract 0.10 compatibility.

## Independent reference and visual review

On 2026-11-07 Microsoft Word 16.0, build 16.0.20527 exported the generated synthetic
DOCX documents through `ExportAsFixedFormat`. `export_word_fonts.ps1` temporarily
registered only the existing licensed Liberation Sans fixture faces and removed
them after export. Word is not a production or CI dependency.

Frozen source DOCX, Word PDF, exporter/font provenance and seven reviewed 96-DPI
native PNGs are under `nuaforma-docx-pdf/src/test/resources/checkbox`. Their
`SHA256SUMS` records the bytes. These are new golden cases; no existing golden
was replaced. Tests must not regenerate these resources.

| Case | Pages | Reviewed scope |
| --- | ---: | --- |
| variants | 1 | Defaults, boxed/unboxed check/cross/empty, expression/fallback, inherited sizes/colors, paired normal/bold marks, standalone automatic line |
| report-a4 | 3 | 28 checked table rows, repeated column headings, header/footer crosses, page numbers, surrounding report text |
| report-letter | 3 | Same report on Letter, with different table/page breaks |

All seven pages were inspected side by side with their Word exports, including
the marks and recurring page bands. Normalized text and page counts match. For
the report, maximum measured word-start differences are 0.274pt horizontally;
word-box top differences are at most 0.163pt. These are observed differences, not an increased test tolerance.
The established Word oracle retains its existing bounds.

The variant page contains 53 vector paths in both Word and native PDFs, without
an image object. Comparing every path's bounding coordinates gives a maximum
difference of 0.015pt. A standalone shape initially exposed a paragraph-mark
baseline difference; its minimum line box was corrected before freezing goldens.
Mixed text keeps its normal text baseline. Word reference PDFs use only Liberation
Sans regular/bold/italic text fonts; the mark itself has no font dependency.

The maintainer reviewed the first preview, requested larger bare check/cross marks
and automatic bold strokes, and approved the revised paired preview. These explicit
visual changes are the reason the new checkbox references were refreshed before
their first commit. The initial copies remain in ignored review output. Existing
pre-#149 golden cases remain unchanged.

## Executable checks

```sh
./gradlew :nuaforma-contracts:test :nuaforma-template:test
./gradlew :nuaforma-docx:test --tests dev.nuaforma.docx.CheckboxPipeTest
./gradlew :nuaforma-docx-pdf:test --tests dev.nuaforma.docx.pdf.NativeCheckboxTest \
  --tests dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxVectorsTest \
  --tests dev.nuaforma.docx.pdf.ownengine.CheckboxWordOracleTest
./gradlew :nuaforma-pdf:test --tests dev.nuaforma.pdf.PdfVectorsTest
```

The direct native test generates all four output profiles on A4 and Letter.
PDF/A-1b/2b/3b pass the configured independent validator. The variant and report
tests assert exact image-comparison goldens from fresh template generation.
The frozen Word oracle separately checks source/PDF provenance, text, font,
color, page and marker positions, and exact native golden images.

Unit cases cover all boolean/outline/mark combinations, strict types and
null/missing/fallback behavior, static validation in unselected branches, split
Word runs, nested scopes, immutable/bounded geometry and cancellation. A direct
PDF containing only vectors renders with no supplied font or image resource.
Malicious/custom DrawingML cannot gain acceptance through an object name: full
structure, transforms, attributes, coordinates, fills and stroke grammar are checked.

Java/Kotlin facade use, capabilities and real HTTP generation use the same
implementation. The Playground needs no dedicated renderer: its existing content
catalog reads the new definition. Full-build/transport qualification is completed
before delivery; no release or website publication is part of this source task.
