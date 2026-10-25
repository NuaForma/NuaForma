# Bounded Hebrew direction proof

Date: 2026-10-25. Parent: [#57](https://github.com/NuaForma/NuaForma/issues/57). This is a bounded increment within international roadmap part 5, not completion of bidi or complex-script support.

## Qualified profile

The two-page synthetic report exercises unpointed Hebrew paragraphs, automatic wrapping, bold/italic faces, decimal numbers, fixed table cells and repeated Hebrew header/footer text beside the existing logo. The footer resolves PAGE in the same RTL paragraph. Separate Latin table identifiers and Latin numbered paragraphs test adjacent, independent LTR content. Existing caller-supplied Liberation Sans faces are reused; there is no new font asset, library or system-font lookup.

`PdfParagraphFormat.textDirection` selects an explicit bidi base direction, independently of physical `PdfAlignment`. Its null default retains the previous simple-text route and continues to reject Hebrew. The implementation uses request-local Java 17 `java.text.Bidi` paragraph analysis and `createLineBidi` for each wrapped line, preserving source style boundaries and Unicode cmap glyphs. It introduces neither AWT shaping nor Unicode replacement text or hidden source overlays. Shared-engine concurrency and the Java API are exercised.

Hebrew is accepted only in the explicit RIGHT_TO_LEFT profile, without any strong LTR characters in the same paragraph. Hebrew/Latin, Hebrew/Greek and Hebrew/Cyrillic mixtures reject, including mixtures across separate runs. Combining marks/niqqud, Hebrew presentation forms, bidi controls, Arabic and other unqualified scripts still reject. Mirrored punctuation on an odd embedding level, RTL numbered lists, first-line/hanging indentation, tabs and inline images are also unqualified and reject. These boundaries deliberately keep the failed mixed-direction research cases outside the accepted route.

The test DOCX reader maps inherited `w:bidi`, including explicit false overrides, into the neutral direction. For this Word profile, RTL source `w:jc` left/right values are reversed when resolving physical alignment; the neutral model keeps physical coordinates. This behavior was observed in the actual Word export and is described in Microsoft's [paragraph alignment implementation notes](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oe376/26ecf09a-0f0b-4574-9907-ebd1ddf3015f). Hebrew source runs require explicit `w:rtl` and explicitly pinned matching complex-script font, size, bold and italic properties. Separate complex-script faces/sizes/styles, forced complex-script overrides on page fields or neutral-only runs, and RTL source indentation reject. This proof does not implement arbitrary run overrides or a general Word compatibility-mode interpreter.

## Verification and limits

The genuine Word export is `build/own-engine-hebrew/word-reference-v6.pdf`, with a receipt binding its SHA-256 to the exact generated DOCX. Prior mixed-direction and compatibility-mode experiments are retained separately as unqualified local research. They are not accepted by the current reader and do not form the reference for this proof.

| Artifact | SHA-256 |
|---|---|
| Source DOCX | `71b7b400b8d1bd1923ee017e94b737bdc353b43b624c7e1738e6d5dfa58476b2` |
| Word PDF | `35fa7101fd8696e91a0b25593369ecb8294a9d958049d1011081c33b40e600d0` |

The JVM test compares the complete candidate body against the imported source, in both normal and position-sorted PDFBox extraction modes. It also checks numbering, page fields, once-only table/list identifiers, page count and explicit failures. The independent `audit_hebrew.py` checks all source words and numbers, their page assignment, six successful Hebrew/number searches in both PDFs, and physical visual glyph order, font faces/sizes/colors, bounds and line positions against Word. It does not assert universal PDFium logical copy order: the earlier mixed-source Word export itself demonstrates reader-dependent ordering. Pixel comparison is retained without alignment, masks or rescaling; parser-inferred extra whitespace is recorded separately from the physical glyph audit.

| Page | Coverage | Lines including bands | Max line-start difference | Max baseline difference |
|---:|---|---:|---:|---:|
| 1 | Hebrew paragraphs, wrapping, numbers and mixed font faces | 9 | 0.384 pt | 0.140 pt |
| 2 | Fixed Hebrew table, separate Latin numbering and page fields | 8 | 0.186 pt | 0.150 pt |

Both page pairs were rasterized independently at 144 dpi and visually reviewed. Full-page pixel fractions differing by more than 24/255 are 0.356% and 0.168%, respectively. All ten resulting Word/candidate/overlay/difference/side-by-side PNGs have reviewed hashes. The comparison checks 485 visible glyphs including the page bands; these numbers describe this source/font profile, not arbitrary Hebrew documents.

Mixed strong directions remain a required next international increment. The initial trial could place those glyphs, but full logical text extraction failed for several Latin/digit combinations. Those cases must not be advertised as supported based only on their appearance. Arabic joining/marks/cluster encoding, CJK/Indic and the wider script/font matrix remain open. No archival/accessibility profile, production adapter, provider registration, publication or Playground behavior changes in this increment.

## Reproduction

```sh
python3 tools/pdf-evaluation/own-engine/build_hebrew_fixture.py build/own-engine-hebrew/source.docx
# Export these exact source bytes through export_word.ps1, retaining its provenance receipt.
./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineHebrew
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-hebrew/word-reference-v6.pdf build/own-engine-hebrew/own-engine.pdf build/own-engine-hebrew/comparison --source build/own-engine-hebrew/source.docx --font-dir build/own-engine-proof/fonts --word-bounds --allow-content-differences
python3 tools/pdf-evaluation/own-engine/audit_hebrew.py build/own-engine-hebrew/source.docx build/own-engine-hebrew/word-reference-v6.pdf build/own-engine-hebrew/own-engine.pdf build/own-engine-hebrew/audit.json
```

Word is an external QA oracle; normal JVM builds do not require it. The comparison's raw-text relaxation is valid only alongside the strict source-body JVM test and independent word/glyph audit above. It does not permit missing text, altered digits, different visual glyph order or different pagination.

Delivery verification: the final full build and local publication checks passed with 624 JVM tests and zero failures/errors/skips. All five independent Java/Kotlin consumers passed against the unchanged published-library set. The final corpus covers fourteen Word-reference documents / 44 page pairs / 220 reviewed PNGs; all image hashes remain unchanged, and the source/content/font/geometry/barcode audits passed on the final outputs. International part 5 remains in progress.
