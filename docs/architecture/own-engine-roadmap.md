# Own PDF engine roadmap

Working execution order, 2026-10-24. Parent issue: [#57](https://github.com/NuaForma/NuaForma/issues/57). This is the prioritized implementation backlog for the own-engine branch, supplementing the [target design](conversion-engine.md), not a release promise or authorization to implement all later parts at once.

## Delivery and current state

The maintainer requested incremental delivery: implement the next part, verify it, confirm the result and suggest the following part. Successfully verified parts may be committed and pushed using signed English Conventional Commits with `Refs #57`. Work continues on the existing work branch from and targeting `feature/own-pdf-engine`. A merge, release or production-provider switch still requires its own authorization. See [branch workflow](../branch-workflow.md#own-pdf-engine-integration-branch).

The existing foundation is completed and committed as `3f450ef`: one shared `nuaforma-pdf` artifact, immutable neutral model, actual fonts/images, bounded flow, tables, page bands, text frames/rotation, real Word-reference proofs and concurrent shared-engine tests. The recorded baseline has 555 passing JVM tests and five independent consumers. DOCX translation remains test-only; current production output still uses its existing provider.

The approved own-engine execution direction is now **in-process conversion within the hosting JVM**, with request-local state, bounded admission/queues and resource accounting. It does not require a fresh renderer process per document. Cancellation is cooperative; there is no per-thread hard kill or per-document OS memory boundary. Existing LibreOffice and validator execution are unchanged. This decision supersedes the earlier mandatory child-process proposal for the own renderer, not the current production runtime's qualified controls.

## Ordered parts

| Order | Part | Acceptance evidence | Status |
|---|---|---|---|
| 1 | Paragraph pagination: keep-with-next, keep-lines, widow/orphan control and explicit break-before | Boundary/unit/concurrency checks; actual Word-exported proof; no blank pages, lost/duplicated text or silent keep relaxation | Complete; [evidence](../../tools/pdf-evaluation/own-engine/PAGINATION.md) |
| 2 | Lists and numbering | Bullets, nested levels, restarts, styled labels, hanging indentation and chapter numbering; source/style inheritance and Word comparison | Complete; [evidence](../../tools/pdf-evaluation/own-engine/LISTS.md) |
| 3 | Tabs and leaders | Left/right/center/decimal stops, dotted leaders, mixed-font measurement and Word comparison | Complete; [evidence](../../tools/pdf-evaluation/own-engine/TABS.md) |
| 4 | Broader style resolution | Effective paragraph/run/table styles, theme fonts, overrides and automatic line/paragraph spacing | Qualified base profile complete; [evidence and remaining limits](../../tools/pdf-evaluation/own-engine/STYLES.md) |
| 5 | International text and writing systems | Cyrillic, Arabic, Hebrew and representative additional scripts; shaping, bidirectional text, mixed-direction numbers/punctuation, font availability and Word references | Planned; moved forward after international-scope clarification |
| 6 | International page geometry and sections | A4/A3, US Letter/Legal, portrait/landscape, margins, pagination and explicit section transitions; Word references for each qualified size | Planned |
| 7 | Page-master variants | First/even/odd headers and footers, section inheritance, reserved areas and images | Planned |
| 8 | Layout-dependent fields | Page X of Y, section page numbers and bounded relayout with stable results | Planned |
| 9 | Advanced table combinations | Automatic widths, per-cell borders, vertical alignment and combined nested/merged/fragmented cases | Planned |
| 10 | Anchored images and broader wrapping | Paragraph-relative repositioning, crop/aspect policies and square/side wrapping without collisions | Planned |
| 11 | Links, bookmarks and named destinations | Working external links and internal navigation with inert source resources | Planned |
| 12 | Contents, captions and cross-references | Heading/caption numbering, correct page references, clickable TOC and bounded field iteration | Planned |
| 13 | Footnotes and endnotes | Reserve note area, paginate notes, preserve numbering and avoid circular layout | Planned |
| 14 | Vector drawings and charts | Source geometry, sharp output, text/font preservation and explicit unsupported constructs | Planned |
| 15 | Review/content visibility policy | Defined handling of tracked changes, comments, hidden text and content controls | Planned |
| 16 | Production DOCX integration and compatibility corpus | Existing provider boundary, realistic variable documents, error/limit/concurrency/load evidence and API/Playground assessment | Planned |
| 17 | Archival and accessibility output qualification | Exact-byte PDF/A profile validation; tagged output/PDF/UA with semantic and human review | Planned |
| 18 | ODT adapter over the shared model | Format-specific styles/masters/anchors mapped without DOCX types or duplicate layout/writing logic; related [#18](https://github.com/NuaForma/NuaForma/issues/18) | Planned |

Accessibility is cross-cutting rather than deferred to part 17: preserve list/heading roles, reading order, table-header relationships and image alternatives as those source constructs enter the model. Existing template conditions, repetitions, value formatting and resource pipes remain in their current modules; this roadmap does not introduce another template engine or a package per feature.

Part 1 passed the full repository build, local publication checks and five independent Java/Kotlin consumers: 569 JVM tests, zero failures/errors/skips. Its ten-page Word trial preserves all text and page boundaries; the earlier 18 page pairs remain unchanged. All 140 comparison PNGs match their visually reviewed hashes. Part 2 also passed: 585 JVM tests with zero failures/errors/skips, five independent consumers, eight additional Word-matched pages and 180 unchanged reviewed comparison PNGs in total. Part 3 passed 597 JVM tests with zero failures/errors/skips, five independent consumers and two additional Word-matched pages; all 190 reviewed comparison PNGs remain unchanged. Part 4 passed the full build/publication checks with 607 JVM tests and zero failures/errors/skips, plus five independent consumers. Its two-page style proof verifies 1,324 visible body characters against Word for font weight/slant, color and size, alongside identical text and line breaks. All twelve documents now cover 40 page pairs and 200 reviewed comparison PNGs. Part 4's bounded profile and remaining mixed-metric/tight-exact-line limits are documented with its evidence. The two authorized follow-up parts are complete; part 5 and later remain planned.

## Automated visual regression direction

During part 2, the maintainer agreed that each new feature should receive an initial Word-reference review, followed by automated regression against deliberately reviewed, versioned references. Keep content/structure assertions alongside visual comparisons. Fix variable input values where practical, or declare narrow excluded rectangles for fields such as dates. Reference updates remain explicit review actions. Pin the rendering/font/test profile so existing tests can stay green without manually reviewing every unchanged page on every code change.

The maintainer will confirm the comparison library used elsewhere. PdfCompare (`de.redsix:pdfcompare`) matches the described pixel/rectangle capabilities but has not been identified as an existing NuaForma dependency or added to this delivery. Tool selection, dependency review and build integration follow after that confirmation; the existing Word/content/raster checks remain the current evidence.

## International acceptance requirements

The maintainer explicitly requested worldwide applicability during part 1. A4-only success and Latin-only font coverage are insufficient for broad production claims. The current paragraph proof is A4/Latin; it does not establish the matrix below. Source page geometry must be preserved rather than converted to a regional default.

- Qualify A4 (210 × 297 mm), A3 (297 × 420 mm), US Letter (8.5 × 11 inches) and Legal (8.5 × 14 inches), in portrait and landscape. Check MediaBox dimensions, margins, logo/band positions, text wrapping and page boundaries against independently exported Word references. Include mixed-size sections when section support is implemented. Keep shared layout independent of A4; each fixture asserts its own expected source geometry instead of applying one A4 expectation to every document.
- Exercise extended Latin, Cyrillic (including a Ukrainian example), Arabic and Hebrew, then representative Greek, CJK and an Indic script. Support claims are per qualified script/font combination rather than an untested promise for every language. Arabic needs contextual shaping/joining and combining-mark positioning; RTL is more than right alignment or reversing strings.
- Test pure RTL and mixed LTR/RTL paragraphs, embedded Latin names, digits, currency, parentheses and punctuation, as well as lists, tables and page bands. Verify actual glyph placement visually and logical Unicode extraction/copy/search/reading order separately. Keep source-language/direction information in the neutral representation as it becomes relevant; do not put DOCX-specific semantics in shared layout.
- Use appropriately licensed caller fonts with known coverage and embedding rights. Check missing glyphs and unavailable shaping explicitly; never claim support by silently substituting fonts, emitting replacement characters or painting text in the wrong order. Reuse the same fonts in Word/engine reference comparisons.

International scripts are promoted to part 5, before production integration. The existing Latin-only engine still rejects unsupported scripts; this roadmap change does not itself implement shaping, bidi, broader page-master import or the international test matrix. Add those executable fixtures with their corresponding increments and preserve the current regression corpus throughout.

Each part needs the relevant normal/boundary/invalid-input tests, source provenance and page-level Word comparison, independent output checks and existing-proof regressions. Run the full project build and relevant local library/consumer checks before delivery. Report supported combinations and remaining gaps explicitly. Add no dependency, profile claim, publisher, tag or deployment merely to complete an item. Keep the existing issue open until its broader acceptance criteria are actually satisfied.

The maintainer separately authorized parts 3 and 4 for sequential follow-up after part 2 on 2026-10-24, subject to the agreed execution limits checked before each new part. Both follow-ups are now completed within their documented qualification profiles. This does not authorize starting part 5 or later, merging, publishing or changing the production provider.
