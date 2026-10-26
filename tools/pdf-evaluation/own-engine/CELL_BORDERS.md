# Solid cell borders and shared-edge precedence

Date: 2026-10-25. Parent: [#57](https://github.com/NuaForma/NuaForma/issues/57). Roadmap part 9 follow-up to [cell alignment](CELL_ALIGNMENT.md). The bounded source profile below is fully verified; broader corner-conflict combinations remain unqualified.

## Neutral model

`PdfTableCell.borders` optionally supplies immutable `PdfCellBorders(top, right, bottom, left)` values. Each visible side is a `PdfEdgeStroke(border, priority)`. A null cell policy retains the uniform table border; a null side in an explicit policy has no stroke. Larger generic priorities win on shared edges; equal priorities prefer wider strokes and then stable input order. The format adapter supplies source-specific precedence. No DOCX/ODT concepts enter shared layout and no module/dependency is added.

The table border remains a minimum layout reservation. Row top insets additionally reserve the largest explicit top stroke in that row; the final closing reserve includes the last row's bottom strokes. Left/right text padding remains physical cell padding. Cell alignment uses the resulting row frame. Uniform tables retain their existing layout and painting route. Custom table edges are emitted as vector geometry, collapsed by table identity with a bounded interval sweep and painted with their priorities after cell fills. They remain sharp at arbitrary PDF zoom levels.

The first qualified target is solid, fixed-width, unmerged body tables occupying one physical page, plus bounded page-band copying. The [combination follow-up](BORDER_COMBINATIONS.md) additionally qualifies consistent borders with spans, one nested level and whole-row pagination. The [row-fragment follow-up](BORDER_FRAGMENTATION.md) qualifies explicitly splittable TOP-aligned rows, including horizontal spans. Conflicting advanced styles and oversized non-splitting custom-border rows still reject explicitly. The existing uniform-border table combinations retain their earlier qualification. Dashed/double/diagonal styles, border spacing/shadows, style-based border inheritance, automatic widths and broader combinations remain follow-up work.

## Test-only DOCX mapping

The reader accepts direct physical top/right/bottom/left `tcBorders`, solid widths of 1–32 eighth-points and explicit RGB colors. It resolves all sides before entering the shared model. In the controlled Word profile, omitted/`none` sides inherit table borders; `nil` removes that side's own stroke and a visible opposing stroke can remain. This distinction is verified by actual exported geometry rather than assuming the names are interchangeable. The [current Word implementation note](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/7c791c5d-44c3-4fe8-abdd-8f136761bb93) describes inheritance and conflicts; the older OE376 note differs in its wording. Source color/width precedence maps to a generic numeric priority, not a Word-specific rule in the PDF writer. The test adapter currently requires all visible resolved sides within each table to share one width and RGB color; conflicting source styles reject explicitly. Different tables can use different styles.

Three qualified synthetic reports each contain four separate tables and twelve body rows. They exercise four colors, widths from half a point through four points, and inherited/removed edges, with alternating fills, caller fonts, a logo and a PAGE footer. Sources place `tcBorders` before `shd` and serialize border children in schema order. Their source and independently exported Word PDF live under `build/own-engine-cell-borders/consistent/<profile>/`.

The earlier conflicting-color/width trials are retained as unqualified research, reproducible with `build_border_fixtures.py <output> --conflicting`. Their full glyph checks passed, but their corner footprints did not pass the quarter-point border gate. Word paints some corner portions through the top cell margin; a simple shared-edge winner does not reproduce those details. These failures are not accepted references or masked exceptions. The adapter rejects the conflicting style combinations, and qualification remains future work. No Word-specific corner recoloring heuristic is included in the shared renderer.

## Independent checks

`compare.py` produces the existing unmasked 144-dpi Word/engine/overlay/difference/side-by-side views. `audit_cell_borders.py` reuses the independent full source/glyph/page/font/color/logo audit and additionally compares visible vector-border footprints per source RGB color. It reconstructs filled rectangles in PDF painting order, preserving occlusion, at eight samples per point for the distance check. Areas are integrated exactly on a coordinate-compressed vector grid; counting raster samples would bias Word's rounded half-point strokes. The border gate permits at most five percent area difference for Word's rounded stroke geometry and requires every visible border sample to remain within a quarter point of the same color in the other PDF. No content mask, image alignment or reference-driven layout is used.

JVM checks reopen real PDFs for border colors, raw top/bottom reserves, simultaneous cell alignment, explicit removal versus uniform defaults, page-band field copying and shared-engine requests with distinct colors/priorities. They exercise precedence, group isolation, invalid geometry/priority, aggregate/per-page edge budgets, and explicit rejection of unqualified merged/nested/fragmented/multiple-page custom-border tables. A Java caller renders actual PDFs through the public edge constructors. Source checks cover unsupported styles, malformed sizes/colors, diagonal edges and the `none`/`nil` distinction.

No production conversion provider, REST/Playground behavior, publication set, release workflow, website or PDF/A/UA qualification changes in this increment.

## Reproduction

Generate the qualified sources with `python3 tools/pdf-evaluation/own-engine/build_border_fixtures.py build/own-engine-cell-borders/consistent`. Independently export each `source.docx` to `word-reference.pdf` with the retained Word exporter and keep its provenance receipt. Run `./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineCellBorders`. For each profile, run `compare.py` with source/font/Word bounds checks and `audit_cell_borders.py <source> <Word PDF> <candidate PDF> <audit JSON>`; visually inspect all generated page views before freezing their hashes. Research fixtures use the explicit `--conflicting` switch and must remain rejected by the adapter.

## Qualified local comparison

| Profile | Pages | Glyphs | Maximum start/baseline delta (pt) | Maximum exact border-area delta | Samples beyond 0.25 pt |
|---|---:|---:|---|---:|---:|
| colors | 1 | 1596 | 0.186 / 0.160 | 4.147% | 0 |
| weights | 1 | 1601 | 0.186 / 0.140 | 2.408% | 0 |
| outline | 1 | 1598 | 0.186 / 0.160 | 4.131% | 0 |

All three side-by-side views (containing both unscaled page renders), overlays and difference views were inspected at original render size. Text, margins, alternating fills, separate tables, logo and footer remain aligned; only sub-point raster/rounding differences remain. The fifteen constituent comparison PNG hashes are frozen in the retained review manifest.

| Profile | Source SHA-256 | Word reference SHA-256 |
|---|---|---|
| colors | 840294e120cbd565007f281c6b9f5744d0c9c71707c83de3897d40cc891c6f8c | ecc514d7f325668067c5cb8920c064f473b7a3237d4320abff8e711f325f3240 |
| weights | 6688a6bfe36a5b399cccd2cb8476902e02e19961299bd48114883f5ef2462f72 | c391f4cde135e352a2c90d3e510a3c6945bdf8fa20f49f9195a740f3049298bd |
| outline | b37876e569c9c4d58f63dfe992d037ec002e5aefd2c95b931d600ccb2abee516 | 4ee99af55a072eabfd585eacf981be3f8cf5b4faa29fc18044e28d909ff054ba |

## Project checks

The full `build verifyEmbeddedConsumers verifyLibraryPublication` plus all existing and new render aggregates succeeded in 19 minutes 35 seconds. All 695 JVM tests passed without failures, errors or skips; all five independent Java/Kotlin consumers passed. This covers the Java 17 library boundary and the Java 25 server. Final source/content/font/geometry/barcode/border audits passed across 36 documents and 155 page pairs. All 775 deliberately reviewed comparison PNGs remain byte-for-byte unchanged, including the fifteen new views. No content mask or alignment exception was added for the new border profiles.
