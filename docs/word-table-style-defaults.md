# Word table style defaults and ordinary borders

Source issue #260 extends the native DOCX reader after the preferred-width work in [#259](preferred-table-widths.md). This is unreleased source functionality, without a new module, dependency or public API.

## Style and margin resolution

If a table omits `tblStyle`, the reader selects the document's declared default table style. An explicit style still selects its own bounded `basedOn` chain. Cycles, unknown styles and ambiguous multiple defaults reject.

Each margin edge inherits independently through the selected style chain and direct table properties. A direct top/bottom override therefore retains the inherited left/right values. The source must still supply all four effective edges. The reader does not invent system-dependent margins when no definition exists.

The existing style, paragraph, font, nesting and conditional-region restrictions remain. This is source-specific inheritance: the shared PDF engine receives physical margins and ordinary resolved styles, rather than Word style objects.

## Borders

The qualified ordinary profile accepts uniform solid RGB/automatic-black table borders and uniform absent borders expressed as `none` or `nil`. Zero border spacing is accepted. Inactive absent-border size zero is validated without trying to construct a visible zero-width stroke. Existing cell-border fallback behavior is retained.

Unknown attributes, duplicate edges, malformed values, decorative borders, nonzero spacing and unqualified conflicting table edges fail explicitly. Original border containers and edges are checked before DOCX binding can discard attributes. Valid but unsupported definitions in unused styles remain inactive; rendered edges still pass the stricter ordinary-border profile. No border is silently dropped to make a document pass.

## Reproduction and qualification

`tools/pdf-evaluation/own-engine/build_table_style_defaults_fixture.py` creates A4/Letter explicit and inherited variants from the preferred-width fixtures. It maintains matching definitions in `styles.xml` and the passive `stylesWithEffects.xml` counterpart. The variants contain automatic physical/percentage widths, no-border and solid-border tables, partial direct margins, a derived table style and a merged cell.

Actual Word exports of explicit and inherited variants are pixel-identical at 96 dpi on both papers. Explicit controls rendered by the preceding engine retain all 26 measured markers per paper, with maximum horizontal difference 0.080 points and vertical difference 0.150 points against Word. Initial reference images come from those reviewed controls; existing golden images are unchanged.

`NativeTableStyleDefaultsModelTest` checks inheritance, effective geometry and rejected variants. The parameterized `NativePreferredTableTest` shares the real-provider, all-profile, Word-metric and exact-raster checks with the preferred-width fixtures, including original-source diagnostics and atomic failure. The combined normal build passes 1,514 tests with no failures, errors or skips. The real HTTP route retains 72 exact golden pages across all four PDF profiles, while 27 malformed or unqualified cases remain explicit failures. The unchanged local 250-source corpus retains seven complete PDFs and 243 explicit rejections, with eleven prior pages pixel-exact. Twenty first diagnoses change after the style/glyph increment; this is barrier movement, not complete-conversion evidence.

Playground impact: existing generation and cleanup use this same reader. Supported table metadata no longer needs removal. No UI setting, cleanup-policy relaxation, production fallback or publication is introduced.
