# Word formatting qualification (#184)

This source increment translates additional Word formatting into the existing neutral
`nuaforma-pdf` model. It adds no production Office dependency, fallback renderer,
publication, or ODT reader. Published alpha.7 behavior remains separate from these
source changes.

## Source rules

- Automatic before/after paragraph spacing cascades independently from physical
  gaps. HTML-compatible automatic gaps are 14 points. Contextual spacing and cell
  edges suppress the applicable automatic gap. `doNotUseHTMLParagraphAutoSpacing`
  instead retains physical gaps and adds adjacent before/after gaps. Modern forced
  page starts suppress before-spacing; compatibility mode 12 retains it. Unreleased
  [source #244](../../../docs/word-compatibility-modes.md) also qualifies legacy mode 14. Ordinary
  document starts preserve physical before-spacing.
- Mirrored physical left/right indents resolve after pagination, using physical
  page parity even when section display numbers restart. Modern compatibility
  mirrors on even pages. Legacy `doNotFlipMirrorIndents` also selects the side.
  Hanging and positive first-line indents retain their individual geometry.
  Mirrored lists, tabs, TOC and RTL combinations remain outside this increment.
- Conditional table styles resolve whole-table, horizontal/vertical bands, edge
  columns, edge rows and corners before ordinary paragraph/character/direct
  formatting. Parent/child layers are resolved within each region. Repeated header
  rows receive first-row formatting. Missing band sizes disable that band; explicit
  sizes 0–3 are qualified. Legacy Normal-style size 11/12 and left-justification
  exceptions apply in the qualified legacy modes with the override switch disabled;
  the original matrix covers mode 12 and source #244 extends qualification to mode 14.
  Positional conditional formatting on merged cells and explicit `cnfStyle` remain
  unsupported rather than receiving guessed geometry.
- `w:kern` uses the final effective font size and its half-point threshold; zero
  disables kerning. Modern/OpenType-enabled text uses the supplied font's OpenType
  positioning. Legacy disabled OpenType uses classic `kern` pairs. Only explicit
  `w14:ligatures` values `none` and `standard` are qualified in this increment.
  Source [#232](../../../docs/word-typography-preferences.md) also qualifies the bounded
  `standardContextual` preference. Equal adjacent runs
  shape together, including ligatures split by passive Word run boundaries.
  Broader contextual scripts, alternate glyph sets and discretionary/historical
  ligatures remain outside this increment.

Source-specific switches remain in the DOCX adapter. The shared model exposes
conditional paragraph spacing, physical-page offsets and font feature policies
without OOXML types, so a future ODT adapter can reuse the same layout and writer.

## Independent references and frozen native goldens

The synthetic cases are under `nuaforma-docx-pdf/src/test/resources`:

| Family | Cases | Word pages | Coverage |
| --- | ---: | ---: | --- |
| `paragraph-spacing` | 8 | 16 | A4/Letter, modern/legacy, inherited flags, physical sums, page/cell edges |
| `mirror-indents` | 5 | 20 | A4/Letter, first/hanging indents, compatibility flags, restarted section numbers |
| `conditional-tables` | 14 | 14 | Regions, stripe phases, repeated headers, Normal/direct precedence, colors and fills |
| `font-features` | 5 | 9 | Noto Serif, thresholds, ligatures, split runs, Greek/Cyrillic, A4/Letter |
| `classic-kerning` | 1 | 1 | Liberation Sans legacy pairs, including contextual space kerning |

Each case retains its deterministic source, a genuine Word ExportAsFixedFormat
PDF and the original source/PDF SHA-256 receipt (Word 16.0, build 16.0.20527).
The source builders and read-only Word exporters live beside this guide. Word is
only required when authoring an independent reference, never in builds or runtime.

`WordLayoutOracle` checks provenance, complete logical text, pagination, labeled
positions, physical font size, face and color against Word; table fixtures also
sample the blank cell fill areas. It separately compares native 96-DPI page rasters
with initial visually reviewed native golden PNGs through image-comparison with
zero changed-pixel allowance. Those PNGs are regression expectations, not a claim
that every pixel equals Word. Existing goldens must not be replaced merely to make
a failing test pass; changes require a reason, visual review and explicit approval.

One visible Word difference is retained deliberately: conditional cell shading can
occlude parts of Word's thin table grid. The native engine paints the complete
specified grid. This is recorded as a rendering difference, not hidden by changing
the reference or masking the comparison.

The Noto Serif 2.015 test face is temporarily activated only for Word reference
export; each receipt records its exact SHA-256. Its licensed, unchanged font bytes
are retained under the PDF module's test resources. The Liberation Sans references
use the installed Word face, while portable native goldens use the retained
Liberation 2.1.5 test faces. Their classic kerning/advance tables agree, but their
font files/outlines are not byte-identical. These tests qualify the retained
family/metrics and reviewed visual differences rather than identical font bytes.

## Shaping and archival output

Shaping loads only caller-supplied font bytes into physical Java fonts. It does not
select fallback font families. Faces, shape caches, glyph aliases and PDF resources
belong to the individual render; concurrent use of one engine cannot share them.
The same glyph advances determine wrapping and painting. Searchable logical text
is retained with a used-cluster ToUnicode map; distinct text clusters sharing one
glyph receive distinct character IDs. Subset embedding includes substituted
ligature outlines; full embedding retains the original complete font stream.

The four output profiles retain their normal independent validation requirements.
For full-font PDF/A-1b the writer replaces PDFBox's full-coverage Unicode map with
used BMP mappings. This removes unused supplementary mappings that would otherwise
raise the PDF version to 1.5; actually requested supplementary mappings are refused
for that path. PDF/A-1b remains PDF 1.4, with its existing watermark/transparency
restrictions. PDF/A-2b/3b retain PDF 1.7. No conformance check is bypassed.
The localized correction follows PDFBox 3.0.8's
[full-font Unicode-map construction](https://github.com/apache/pdfbox/blob/3.0.8/pdfbox/src/main/java/org/apache/pdfbox/pdmodel/font/PDCIDFontType2Embedder.java#L119),
which raises the version for supplementary mappings even when those characters
are unused. It does not lower versions introduced by unrelated PDF features.

Additional tests exercise classic/OpenType advances, real `ffi` glyphs, Unicode
aliases, all four profiles with subset/full embedding, watermark sharing, concurrent
different caller fonts, cancellation and output limits. The registered provider
test covers DOCX serialization and font collection as well as the neutral renderer.
Native container qualification makes an actual caller-font OpenType request in
each service/Playground image on AMD64 and ARM64.

## Reproduction

Run with the repository's Java 17/25 toolchains:

```sh
./gradlew :nuaforma-pdf:test --tests dev.nuaforma.pdf.FontShaperTest --tests dev.nuaforma.pdf.PdfFontFeaturesTest
./gradlew :nuaforma-docx-pdf:test --tests 'dev.nuaforma.docx.pdf.ownengine.OwnEngine*Test' --tests dev.nuaforma.docx.pdf.WordFormattingProviderTest
```

Initial reference authoring is explicit and refuses to overwrite outputs:

```sh
./gradlew :nuaforma-docx-pdf:renderWordFormattingProof --args="src/test/resources/font-features build/fresh-formatting-proof noto-serif"
```

This only writes candidate PDFs/PNGs below the module's ignored build directory.
It cannot update checked-in references. Review candidate pages against the genuine
Word exports before initially adding a new golden; ordinary tests never regenerate
goldens. Test results and CI qualification belong in the delivery PR, rather than
assuming that a source guide alone proves a passing build.

## Qualified run theme colors

Unreleased #245 resolves bounded embedded palettes for run-content colors, including Word HSL tint/shade precedence and the ordinary style cascade. [The qualification guide](../../../docs/word-theme-colors.md) describes exact base colors, the one-channel bound for intermediate transforms, four new reviewed golden pages and the PDF color-operator regression. This does not enable theme-based borders, shading or DrawingML effects.

## Initial section metadata

Unreleased #246 admits an initial `continuous` start marker and validated nonprinting form-protection flags. Later transitions retain their existing bounded policy. [The Word qualification](../../../docs/word-initial-section-metadata.md) covers A4/Letter, enforced protection, mixed-paper sections, four reused golden pages and two new reviewed pages.

For archive-only #247 regression fixtures, tests deterministically add opaque `[trash]/hhhh.dat` items to the existing A4/Letter theme-color sources. Logical XML, Word references and golden images are unchanged. All four profiles must remain pixel-exact against those existing goldens; no new Word-export provenance is implied for the altered ZIP container.

## Mixed Latin font roles and editing preferences

Source #248 adds two A4/Letter mixed-font fixtures, four independently exported Word pages and four initially reviewed immutable engine images. All sixteen profile pages match those new goldens; 84 markers retain physical face, color and size within the existing position tolerances. The two declared regular fixture families are Liberation Sans and Noto Serif. Original font-property errors are tested before intermediate serialization, including real HTTP cases previously accepted after malformed metadata was discarded.

Source #249 adds two Word exports with passive save/recording preferences. Word pixels are exact against the earlier package controls, and all eight profile pages reuse existing typography goldens. No prior reference image is replaced. Builders and source/provenance hashes bind both sets; run the provider tests documented in their owning guides and the normal full build.

Source #250 qualifies bounded unbound custom XML using `unbound-custom-xml/{a4,letter}`: two independent Word references, unchanged base goldens and all four PDF profiles. Stored data never becomes printed or templated text. See [the qualification and rebuild procedure](../../../docs/word-passive-office-package.md#unbound-custom-xml-data-source-250).

Source #251 adds `document-variable-storage/{a4,letter}`: unused braces and non-Latin values must not change Word pages, existing goldens, caller fonts or any PDF profile. [Qualification and rebuild](../../../docs/word-passive-editing-preferences.md#unused-document-variable-storage-source-251) include explicit displayed-field rejection.

Source #252 adds `unicode-spaces/{a4,letter}` with independent explicit/implicit Word controls, 40 measured markers and two initially reviewed goldens. [Qualification and rebuild](../../../docs/word-unicode-spaces.md) retain ordinary XML-space restrictions.

Source #253 adds `table-bookmark-ends/{a4,letter}` with independently exported paragraph/interleaved controls, 12 measured markers and two initially reviewed goldens. [Qualification and rebuild](../../../docs/word-fields-and-bookmarks.md#ordered-table-bookmark-endings-source-253) preserve strict range balance and text-reference boundaries.

## Proofing, tray selection, editing bookmarks and font hints (#254–#257)

`proofing-display-preferences`, `printer-tray-selection` and `editing-bookmark` derive from the passive-package A4/Letter controls and reuse the existing `typography-preferences` images. `default-font-hints` derives from `mixed-latin-fonts`, retaining its two-page A4/Letter images and 42 markers per paper. Source parts, actual Word exports, receipts and golden provenance are retained in each new folder; existing PNGs are referenced rather than copied or replaced.

Build sources with `build_proofing_display_fixture.py`, `build_paper_source_fixture.py`, `build_editing_bookmark_fixture.py` and `build_default_font_hint_fixture.py`, each with `--base nuaforma-docx-pdf/src/test/resources --output <fresh-build-directory>`. Independently export with the exact existing fixture fonts. Ten Word-reference pages must retain control pixels; tests cover all four PDF profiles, original/cleanup bytes and focused malformed-source rejection. Caller/private corpus fonts stay outside resources. No reference generation or golden replacement occurs during tests.
