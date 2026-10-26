# Effective styles and automatic spacing

Issue [#57](https://github.com/NuaForma/NuaForma/issues/57), 2026-10-24. Part 4 of the [roadmap](../../../docs/architecture/own-engine-roadmap.md). This bounded source-only increment adds explicit automatic line boxes to the shared `nuaforma-pdf` artifact and expands the trusted-fixture DOCX reader. Production provider/REST behavior, Playground, releases, publications and website are unchanged. There is no production DOCX/ODT reader or new dependency.

## Independent Word evidence

`build_styles_fixture.py` generates two A4 pages with a logo and repeated page bands. Microsoft Word 16.0, build 16.0.20430, exported the exact source using `ExportAsFixedFormat`. The final reference is `word-reference-v4.pdf`; earlier exports are superseded. The final source includes an explicit false style override as well as repeated positive values.

| Evidence | SHA-256 |
|---|---|
| Source DOCX | `9f04a465a368d725c5e171633b0d3b360f7a5caf36c421b19efc861976b8d177` |
| Word PDF | `ea7015dec7ea55429d84029c064d002f6b5cb8a5efce91b8d59a1fdd79b87413` |

| Page | Coverage | Lines incl. bands | Max baseline difference | Max word-start difference | Changed full-page pixels above 24/255 |
|---:|---|---:|---:|---:|---:|
| 1 | Single/1.5/double spacing, paragraph/character inheritance, Latin theme fonts, positive inheritance and explicit false/direct overrides | 19 | 0.172 pt | 0.188 pt | 1.109% |
| 2 | Contextual paragraph spacing, base/derived table styles and direct cell-text overrides | 11 | 0.140 pt incl. footer | 0.136 pt | 0.502% |

Both pages have identical normalized text, word order, line breaks and pagination. `audit_styles.py` independently requires complete source-body equality, matching font weight/slant and RGB color for every visible body character, and font-size differences below 0.05 pt (Word emits 11.04 pt for the 11 pt source). Its 1,324 visible body characters retain the same effective font faces and colors. No glyph leaves the physical page. Body line-start differences stay within 0.036 pt and baseline differences within 0.172 pt. Both complete pages were rendered independently with Poppler 26.07.0 at 144 dpi and visually reviewed, including logo, spacing, mixed bold/italic, cell text and borders. Full pixels remain unmasked; percentages measure text-edge differences, not document similarity.

The Word trial exposed an incorrect prior assumption in the test reader: repeating a positive bold/italic value in a based-on style did not disable it in this Word profile. The reader and its earlier assertion now follow the observed positive inheritance and explicit false/direct overrides. This is consistent with the paragraph-style case discussed in [Microsoft's implementation notes on toggle properties](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/f7130225-2368-48f3-acae-a9d278d0fb25). Complex document-default toggle combinations are not qualified by this proof.

## Shared model and source resolution

`PdfAutomaticLineSpacing` explicitly selects a finite 1–10 multiple of natural font spacing. The PDF adapter reads validated `head.unitsPerEm` and `hhea` ascent/descent/gap from the supplied TrueType bytes. Layout uses `(ascent + descent + gap) * multiple` and a baseline at `ascent + gap`, with additional spacing after the line. There is no Word-specific fitted baseline offset. Flow, table/frame measurement and page keeps use the same calculated box. The existing exact-line policy and original default single-page behavior retain their previous geometry. Conflicting explicit policies reject.

The qualified automatic profile requires equal natural metrics across runs/label fonts; matching regular/bold/italic faces can mix. Mixed sizes or differing natural boxes and inline images reject explicitly. Missing/invalid natural metrics reject as invalid resources; the engine does not guess or substitute fonts. This does not establish every source application's font-line-metric selection policy for arbitrary fonts.

The test reader resolves document defaults, base table paragraph/run styles, paragraph styles, character styles and direct run properties in source order. Physical table geometry still comes from explicit properties; conditional row/column/band table styles are unsupported. Theme major/minor Latin references resolve from an embedded, relationship-bound theme to the explicitly supplied proof font family. Unknown roles, missing themes, cycles and wrong style types reject. No DOCX theme/style tree enters the neutral model; future ODT import resolves its own source rules into the same effective text styles and line/paragraph geometry.

Contextual paragraph spacing is resolved against adjacent paragraphs of the same source style: before/after space is suppressed on the relevant side, including after frame extraction aliases. Ordinary differing-style spacing retains the existing collapse rule. This qualifies automatic same-style suppression, not `beforeAutospacing`/`afterAutospacing`, line-count paragraph units, HTML spacing or all document-default compatibility settings. At-least spacing, mixed auto metrics and complex scripts remain outside the qualified profile and reject where recognized.

The table fixture explicitly uses 16 pt exact lines for its 11 pt bold-italic text. A 14 pt variant was rejected by the existing conservative full-font bounding-box guard: its 80% baseline is slightly above the font's maximum advertised ascent, even though Word renders this text. That guard is retained, so this proof does not qualify all tight exact-line/font combinations. Refining actual ink bounds remains follow-up work; source formatting is never silently enlarged or clipped.

One additional unmodified OFL Liberation Sans BoldItalic 2.1.5 test fixture completes the existing family. Its license, package provenance and hash are recorded in the [font fixture guide](../../../nuaforma-pdf/src/test/resources/fonts/README.md). No font is bundled into production/source library JARs or installed by the engine. The local Word comparison uses the existing caller-supplied family and records all caller-font hashes, including its bold-italic face.

## Verification and reproduction

```sh
python3 tools/pdf-evaluation/own-engine/build_styles_fixture.py build/own-engine-styles/source.docx
# Export this exact source with Word using export_word.ps1.
./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineStyles
python3 tools/pdf-evaluation/own-engine/audit_styles.py build/own-engine-styles/source.docx build/own-engine-styles/word-reference-v4.pdf build/own-engine-styles/own-engine.pdf build/own-engine-styles/audit.json
```

Tests reopen actual PDFs to verify font-driven advances, page counts, once-only content, malformed metrics, explicit rejection, Java 17 calls and request separation across eight concurrent shared-engine renders. Source tests check theme/style precedence, contextual spacing, table inheritance and unsupported cases. Word remains an external QA oracle. Generated sources, Word receipts, candidate/font hashes and raster reports remain under ignored `build/own-engine-styles/`. Automated external visual-regression dependency selection still awaits the maintainer's library identification.

Delivery verification: the full repository build and local publication checks passed, with 607 JVM tests and zero failures/errors/skips. All five independent Java/Kotlin consumers passed. The final rendered corpus covers twelve documents / 40 Word page pairs / 200 reviewed PNGs with unchanged hashes, plus content/geometry/font/color/barcode audits. This is source-only qualification; no production switch, publication or merge is made.
