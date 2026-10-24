# First international text increment

Issue [#57](https://github.com/NuaForma/NuaForma/issues/57), 2026-10-25. This is the qualified **Greek/Cyrillic increment of roadmap part 5**, not completion of international shaping/RTL support. It extends the existing measured cmap text route and retains language metadata in the same `nuaforma-pdf` artifact. There is no new dependency, font asset, module, production provider switch, Playground UI, publication, release or website change.

## Word and independent-reader evidence

`build_international_fixture.py` creates two A4 pages with the existing logo/page bands and supplied Liberation Sans faces. Page 1 has Russian, Ukrainian and Greek paragraphs, bold identifiers, italic mixed-script text, accents, currency and numbers. Page 2 repeats all three scripts in fixed table cells and adds three numbered list items. Microsoft Word 16.0 build 16.0.20430 exported the exact source through `ExportAsFixedFormat`; the final reference is `word-reference-v2.pdf`.

| Evidence | SHA-256 |
|---|---|
| Source DOCX | `6ddf4d924b8f471026bd0c357ebb56d9ba9e08aba9269e9e30e083f01addb1ff` |
| Word PDF | `c2a4b2eb7f941acfdf87baecf51dd192610bdb32e921378316b6681424d03fcb` |

| Page | Coverage | Lines including bands | Max word-start difference | Changed full-page pixels above 24/255 |
|---:|---|---:|---:|---:|
| 1 | Russian/Ukrainian/Greek paragraphs, bold/italic and mixed identifiers/amounts | 9 | 0.252 pt | 0.509% |
| 2 | Table text and continued decimal numbering | 9 | 0.212 pt | 0.360% |

Both page pairs have identical normalized text, word order, line breaks and pagination. They were independently rasterized with Poppler 26.07.0 at 144 dpi and visually reviewed; all ten comparison images have reviewed hashes. No pixel masks, shifting or rescaling are applied. Source/reference/font hashes and detailed geometry remain in ignored `build/own-engine-international/`.

`audit_international.py` uses the existing bundled PDFium binding as an independent parser: complete source-body equality is required in both Word and candidate, after removing only the three generated list labels at their known item identifiers. It separately checks successful searches for Russian, Ukrainian and Greek words in both PDFs and identical text per page. A second parser checks balanced marked-content scopes and the exact set of emitted language tags. Normal JVM tests reopen candidate bytes through PDFBox and require preserved Unicode, embedded fonts, once-only source IDs, numbering, Java 17 calls and shared-engine concurrent request separation.

## Scope and neutral metadata

The simple route now accepts Latin, Greek, Cyrillic and common punctuation with actual supplied-font glyph coverage. Precomposed source text is qualified; combining marks, bidi controls, Arabic, Hebrew, CJK, Indic and other scripts needing shaping remain rejected. Missing glyphs fail explicitly. No transliteration, replacement characters, system-font lookup or font substitution is introduced. The same source cmap advances still determine both measurement and writing.

`PdfTextStyle.languageTag` is optional, bounded ASCII metadata: 2–8 initial letters, optional 1–8 alphanumeric subtags, and at most 64 characters. It is retained in `/Span << /Lang (...) >> BDC ... EMC` scopes, without claiming tagged PDF or PDF/UA. It does not itself choose fonts, transform text or determine direction. The test DOCX reader maps `w:lang/@val` through defaults and paragraph/character/direct precedence. Unsupported extra language attributes reject. A future ODT adapter can map `xml:lang` into this same source-neutral property.

## Shaping and RTL remain in progress

The builder's explicit `--rtl-probe` mode creates an **unqualified research source**, including Arabic/Hebrew and an Arabic mark/lam-alef/number example. That source is not accepted by the current reader/renderer and is not the qualified Word reference above. A local Java 17 experiment with caller fonts can generate connected Arabic glyphs, but the tested glyph/Unicode/ActualText encodings did not yet produce reliable extraction/search across the tested readers. No Java AWT shaper, experimental custom font encoding or fallback was adopted in product sources. The prototype remains local research, not supported rendering.

The independent reader also exposes extraction differences in the genuine Word export. A contemporary [PDFium issue](https://issues.chromium.org/issues/575985355) reports lam-alef copying errors, but a reader bug is not a substitute for qualifying our own encoding. Next: prove bounded glyph/cluster encoding, logical Unicode, mixed letters/digits/punctuation and combining marks together with Word visual comparisons, before enabling RTL scripts. International part 5 stays in progress; CJK/Indic and the script/font/format matrix remain outstanding.

## Reproduction

```sh
python3 tools/pdf-evaluation/own-engine/build_international_fixture.py build/own-engine-international/source.docx
# Export the exact source with Word using export_word.ps1.
./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineInternational
python3 tools/pdf-evaluation/own-engine/audit_international.py build/own-engine-international/source.docx build/own-engine-international/word-reference-v2.pdf build/own-engine-international/own-engine.pdf build/own-engine-international/audit.json
```

Word remains an external QA oracle. Normal builds/tests require no Word installation and add no system fonts. This does not qualify arbitrary DOCX, all fonts/languages or any archival/accessibility profile. The separate page-size/section matrix is still roadmap part 6.

Delivery verification for this increment: the full repository build and publication checks passed with 615 JVM tests and zero failures/errors/skips. All five independent Java/Kotlin consumers passed. The final corpus covers thirteen Word documents / 42 page pairs / 210 reviewed PNGs, with unchanged image hashes and successful source/content/font/geometry/barcode audits. Part 5 remains in progress.
