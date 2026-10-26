# Tabs and leaders

Issue [#57](https://github.com/NuaForma/NuaForma/issues/57), 2026-10-24. Part 3 of the [roadmap](../../../docs/architecture/own-engine-roadmap.md). The shared `nuaforma-pdf` artifact now supports typed tab controls. DOCX translation remains test-only. Production providers, Playground, publications, releases and website are unchanged. No new dependency or bundled font was added.

## Independent Word evidence

`build_tabs_fixture.py` produces two A4 pages with the existing report logo and repeated page bands. Microsoft Word 16.0 exported the exact source using `ExportAsFixedFormat`; reference `word-reference-v2.pdf` supersedes the initial mixed-font fixture. Evidence and export receipts remain ignored under `build/own-engine-tabs/`.

| Evidence | SHA-256 |
|---|---|
| Source DOCX | `594d6dd5b06f4f5782fc58f491df378684faec01d5bf03808b13ab0554a15f8b` |
| Word PDF | `2c45b40ee4e572ba8e657038caf2ef4129fc5d7180b972e337b9eb5f9f26f545` |

| Page | Cases | Result |
|---:|---|---|
| 1 | Left/right/center/decimal tabs; comma decimal symbol, negative/integer values; mixed bold/italic; default grid; wrapped continuation | All field anchors within 0.08 pt of Word; all source body text preserved |
| 2 | Dotted, hyphen and underscore leaders; multiple stops; inherited, cleared and directly overridden stops | All field anchors within 0.08 pt of Word; all source body text preserved |

An independent source-aware audit verifies all 21 tab paragraphs and 49 fields, their order, page assignment and complete body text. Maximum field-start difference is 0.072 pt; maximum baseline difference is 0.071 pt. Both pages were independently rendered with Poppler 26.07.0 at 144 dpi and visually reviewed. Text, logo, page bands and alignment survive without clipping or overlap. Ten reviewed comparison images have recorded hashes.

Word wraps the authored compound `alinea-inspringing` at its hyphen; the engine currently wraps at ordinary spaces, yielding a different break within the same three-line paragraph. This accepted wrapping difference is visible on page 1. Leader glyph counts/spaces differ slightly, so raw extracted PDF text is not identical. The audit removes only repeated decorative leader runs and rejoins only compounds proven in the source, then requires exact source body equality. It rejects source punctuation conflicting with that controlled policy. The raster comparison retains every pixel, without masks. `compare.py --allow-content-differences` records the raw differences; it is not an equality assertion. These results qualify this profile, not arbitrary DOCX or pixel identity.

## Model and limits

`PdfTab` is an inline control carrying its actual text style. `PdfTabSettings` snapshots sorted physical stops, default interval and explicit period/comma decimal separator. Stops align the following field left/right/center or at its first decimal separator; absent separators align the field end. Following mixed-font runs use their real glyph advances. Default stops use the declared grid. Left-tab fields wrap with ordinary paragraph continuation indentation. Right/center/decimal fields must fit the current line. Overlapping stops and out-of-bounds fields reject rather than silently move or drop text.

Dot, hyphen and underscore leaders fill the measured gap and are marked as PDF artifacts. This is not tagged-PDF or PDF/UA qualification. Whole-paragraph alignment must remain left for tabbed paragraphs. Bar/heavy/middle-dot leaders, locale-specific decimal inference, complex scripts and automatic line spacing are not qualified. The test DOCX reader resolves paragraph-style stop inheritance, `clear` and direct overrides; lists with inline body tabs remain outside its qualified source profile.

Bounds: 32 stops/settings, 32 controls/paragraph, 2,000 controls/document, 4,096 generated glyphs/control and 100,000 total painted leader glyphs after layout/repeated bands. Positions are finite 0–14,400 pt; default interval 1–14,400 pt. Existing source text, page, font, image, output and interruption budgets remain active. Settings are immutable and concurrent shared-engine tests verify request separation.

## Reproduction

```sh
python3 tools/pdf-evaluation/own-engine/build_tabs_fixture.py build/own-engine-tabs/source.docx
# Export this exact source with Word using export_word.ps1.
./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineTabs
python3 tools/pdf-evaluation/own-engine/audit_tabs.py build/own-engine-tabs/source.docx build/own-engine-tabs/word-reference-v2.pdf build/own-engine-tabs/own-engine.pdf build/own-engine-tabs/audit.json
```

Normal tests reopen actual PDF bytes and check text, physical anchors, mixed fonts, artifact operators, page continuation, inherited stops, invalid input, resource bounds, concurrency and Java 17 calls. Word remains an external QA oracle, not a conversion dependency. The maintainer's preferred automated visual comparison library is still awaiting identification; no new comparison dependency is introduced here.

Delivery verification: full repository build and local publication checks passed, with 597 JVM tests and zero failures/errors/skips. Five independent Java/Kotlin consumers passed. Eleven Word-reference documents cover 38 page pairs; all 190 reviewed comparison PNGs retain their hashes, alongside strict content/geometry/barcode audits.
