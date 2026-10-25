# Lists, numbering and hanging indentation

Issue [#57](https://github.com/NuaForma/NuaForma/issues/57), 2026-10-24. Part 2 of the [own-engine roadmap](../../../docs/architecture/own-engine-roadmap.md). This source-only increment adds a neutral, bounded numbering resolver and separate semantic list labels to `nuaforma-pdf`, with strict numbering translation in the test-only DOCX reader. No production provider/profile, Playground UI, runtime, publisher, release or website change is introduced.

## Independent evidence

`build_lists_fixture.py` creates an A4 practice document using real `numbering.xml`, instance references and paragraph styles. It reuses the earlier report's logo and page bands. It includes three bullet levels, italic body text, bold/color labels, decimal and leading-zero numbers, letter rollover, upper/lower Roman numbers, left/center/right label alignment, all three suffixes, shared/independent sequences, start values 0/7/98, automatic nested restarts and a never-restarting level. Chapter styles preserve real outline levels, inherit numbering and exercise an explicit restart at chapter 4. Ordinary paragraphs demonstrate hanging and first-line indentation independently of lists. A long list crosses pages and the 99/100 boundary; one item continues onto the next page without repeating its label.

Microsoft Word `ExportAsFixedFormat`, version `16.0`, build `16.0.20430`, exported the final reference `word-reference-v5.pdf`. The source explicitly declares a 720-twip default tab grid. Without an explicit setting, the local Word installation used a slightly different grid for long labels that overran their explicit numbering tab. The source setting, not a fitted renderer offset, now determines that fallback. Earlier reference versions are superseded and must not be compared with the final source.

| Evidence | SHA-256 |
|---|---|
| Source DOCX | `22ba89ed73347035fdd5180d9ee871296793ef1948fe1a7ee924d849deee32ac` |
| Word reference PDF | `000f7300c504c7a563b9df735f9185633956c0579a5e09bb2cdcdb864bfceedd` |

Generated documents, source/export receipts, candidate hashes, independent renders, overlays and detailed geometric reports remain under ignored `build/own-engine-lists/`. Comparison verifies the exact source/reference hashes and records caller font hashes. Word is a QA reference, not a conversion dependency. No new font or dependency was installed.

## Page comparison

All eight page pairs have identical normalized text, geometric word order and pagination. All visible glyphs in Word and engine use embedded caller-compatible Liberation Sans faces. Word additionally embeds Arial faces for generated whitespace only; these are not supplied to or substituted by the engine. Every page was independently rendered with Poppler 26.07.0 at 144 dpi and visually inspected, including page continuations, label gaps and hanging indents. The largest word-start difference is below 0.29 pt; baseline differences stay within 0.17 pt. These results establish this controlled profile, not arbitrary DOCX or universal pixel identity.

| Page | Coverage | Lines, Word / engine | Max baseline difference, pt | Max word-start difference, pt | Changed pixels above 24/255 |
|---:|---|---:|---:|---:|---:|
| 1 | Nested bullets, italic runs, alternate hanging indent, ordinary hanging/first-line paragraphs | 29 / 29 | 0.14 | 0.188 | 2.666% |
| 2 | Right-aligned numbers, plain interruption, restart at 7, resumed shared sequence, leading zeros and zero start | 25 / 25 | 0.14 | 0.252 | 1.271% |
| 3 | y/z/aa/bb, centered Roman labels, bold/color labels, suffix variants, nested/never restarts and legal labels | 29 / 29 | 0.14 | 0.230 | 1.813% |
| 4 | Style-linked chapters 1/1.1/1.1.1, descendant reset and an inherited chapter style | 30 / 30 | 0.17 | 0.219 | 2.640% |
| 5 | Numbering cancellation and chapter restart at 4 / 4.1 | 6 / 6 | 0.14 | 0.289 | 0.499% |
| 6 | Flowing list labels 98–105, including transition to three digits | 35 / 35 | 0.14 | 0.162 | 3.553% |
| 7 | Labels 106–114 and the start of a split item | 36 / 36 | 0.14 | 0.137 | 3.718% |
| 8 | Continuation of item 114 without a duplicate label, then 115–121 | 32 / 32 | 0.14 | 0.137 | 2.976% |

Pixel percentages measure thresholded text-edge/raster differences over complete pages. They are not document similarity scores; dense pages naturally have more differing text-edge pixels. The preserved text, page boundaries, geometry and visual review are separate checks.

## Model, behavior and limits

`PdfNumbering.resolve` processes neutral definitions and source-ordered steps in one pure call. Each call owns its counters. Definitions snapshot their level lists; returned values are unmodifiable. A `sequenceId` separates sequence ownership from list-instance identity. The DOCX adapter maps abstract definitions to shared sequences and applies an instance's start override on its first use at that level. The D14 case proves that resuming an earlier instance after restarting another instance sharing the same abstract definition advances from 9 to 10, as Word does. Independent sequences remain independent. A future ODT reader can express continuation/restart semantics without using DOCX types in shared layout.

Resolved `PdfListLabel` values carry label text, list identity, level and ordinal separately from paragraph `runs`. Labels have real fonts and left/center/right alignment; body continuation indentation is independent of the marker anchor. Tab, space and no suffix are measured, and wide labels advance to the declared default grid. The renderer paints a label only on the first line; pagination and repeated measurement never advance numbering state. Outline levels survive in the neutral paragraph format, without claiming tagged PDF or generated bookmarks.

Qualified source combinations use body paragraphs, exact line spacing, explicit fonts and physical indentation. The batch model supports up to nine levels, while this Word trial qualifies three; source levels must be contiguous. Decimal, decimalZero, repeated Latin letters, Roman and literal bullet formats are supported. Alphabetic values follow Word's `z → aa → bb` behavior, not spreadsheet column naming. Roman values are limited to 1–3999. Legal numbering converts referenced ancestors to decimal. Skipped numeric ancestors, unresolved placeholders and contradictory linked-style levels reject explicitly.

Only `startOverride` is qualified inside `lvlOverride`; full level overrides reject. Numbering-style links, picture bullets, arbitrary counter formats/scripts, conflicting direct levels for linked styles and source lists in tables/frames/page bands are outside this profile and reject. A numbering cancellation (`numId=0`) remains unnumbered. General inline text tabs and leaders are still the next increment: this work implements only the tabs needed to separate a list label from its text. No ToC, cross-reference, multicolumn or arbitrary document-profile claim is made.

Bounds: 128 definitions/instances, at most nine levels each, 2,000 steps, counters up to 1,000,000, patterns up to 128 characters and labels up to 256 characters, within the existing aggregate text budget. Font/embedding/image/page/output limits remain active. Missing marker fonts/glyphs, unsupported scripts, overflow and cancellation fail before successful output; interruption preserves the flag. Shared-engine concurrent requests retain distinct text and reset numbering independently.

## Reproduction and delivery

```sh
python3 tools/pdf-evaluation/own-engine/build_lists_fixture.py build/own-engine-lists/source.docx
# Export this exact source with Microsoft Word using export_word.ps1.
# Reuse the earlier caller font directory.
./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:renderOwnEngineLists
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-lists/word-reference-v5.pdf build/own-engine-lists/own-engine.pdf build/own-engine-lists/comparison --source build/own-engine-lists/source.docx --font-dir build/own-engine-proof/fonts --word-bounds --poppler-dir <poppler-directory>
./gradlew build verifyEmbeddedConsumers verifyLibraryPublication
```

The Word exporter refuses to overwrite an existing reference. If source bytes change, export a new reference name and update the comparison path. Normal builds generate test DOCX resources without Office. Java callers resolve and actually render labels through the vendor-independent facade. Unit and integration checks cover formatting, source inheritance/overrides, cancellation, budgets, malformed references, concurrent state, no label duplication on page continuation, and explicit failure instead of silent label loss outside the qualified body profile.

The delivery gate rerenders the nine earlier proofs (28 page pairs) and checks all 140 comparison PNGs against their reviewed hashes, then checks these 40 new PNGs against their reviewed versions. The full repository build, local publication checks, all ten proof renders and five independent Java/Kotlin consumers passed. Final reports contain **585 JVM tests, zero failures/errors/skips**: eleven new numbering/layout tests, four DOCX/PDF tests and one actual Java API render are included. The full verification command took 16m 54s; the final fixture/publication/build refresh took 1m 58s, with existing server checks up to date. All three generated copies of the final source match the recorded source SHA-256. All 180 PNGs match their reviewed hashes, and advanced-table/frame/rotation audits pass on final bytes, including full-page barcode decoding. This is verification duration, not conversion throughput. The current worktree remains until an authorized merge and evidence-preserving cleanup. The next proposed increment is **general tabs and leaders**.

Source semantics are informed by Microsoft's [numbering-level association](https://learn.microsoft.com/en-gb/dotnet/api/documentformat.openxml.wordprocessing.paragraphstyleidinlevel?view=openxml-2.13.1) and [restart rules](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.levelrestart?view=openxml-3.0.1). The actual Word export establishes the qualified behavior recorded here.
