# Contents, captions and cross-references

Issue [#57](https://github.com/NuaForma/NuaForma/issues/57), roadmap part 12. This increment stays in the existing `nuaforma-pdf` artifact and production DOCX adapter; it introduces no dependency, output profile, external renderer or release. The complete local build/publication checks pass 832 JVM tests with zero failures/errors/skips, plus five independent Java/Kotlin consumers. A focused coverage preflight exercises 98.1% of changed executable production lines; this is not a substitute for the full CI run or actual Sonar Quality Gate. CI and integration-branch delivery remain pending.

## Qualified candidate

- Source-ordered decimal `SEQ` counters, next/repeat, explicit 0–9999 restarts and heading-level resets; numbered heading titles feed the contents list.
- Text `REF` from actual same-paragraph bookmark contents, `PAGEREF` with section display numbering, optional internal hyperlinks, and body `PAGE`/`NUMPAGES`/`SECTIONPAGES`.
- Explicit `TOC \o "first-last"` levels 1–9, optional `\h`, print-inert `\z`, and directly assigned levels with `\u`. Explicit TOC paragraph styles retain fonts, indentation, spacing and tabs. General selectors, caption lists and switches outside this grammar reject.
- Simple fields and complete complex fields with split instruction runs. A standalone, possibly multi-paragraph TOC cache is replaced as one unit; only the identified built-in Table of Contents content control is unwrapped. Other content controls remain unsupported. Cached results are never treated as reference or sequence values.
- Missing/duplicate targets, executable/unknown instructions, unsupported formatting, partial field-result bookmarks, unresolved/repeated page fields, recursive text references and resource limits fail explicitly. No field opens a URL or reads an external file.

All counters, mappings, identities and iteration state belong to one render job. Shared-engine concurrency tests check independent results. Limits include 1,000 source fields, 1,000 reference ranges, 100,000 expanded text characters, 12,000 logical nodes, 32 reference nesting levels and eight pagination passes, alongside existing page/source/font/image budgets.

## Word comparison

`build_reference_fixtures.py` creates A4, US Letter, complex-field and wrapped-title documents with deliberately stale caches. The complex case references a whole caption: Word removes a bookmark limited to a complex SEQ result when updating that result, so an earlier exploratory number-only complex-field fixture is not an accepted Word reference. The simple-field trials still independently qualify number-only references. Production conversion does not update or rewrite source DOCX.

Four genuine Word exports cover twelve page pairs. The optional `export_word.ps1 -UpdateFields` updates source fields and generated TOC content in the live read-only document, repaginates three times, exports and closes without saving the source. Its receipt records the source/PDF hashes, Word version/build and field-update choice. Qualification evidence is kept outside tracked resources under `57-references-reviewed-20261026`.

`audit_references.py` requires exact per-page words and glyph order after removing only generated leader dots from the contents page. Source literals cannot contain conflicting dot runs. All other punctuation remains. Font faces agree; Word exports the fixture's 11 pt text at 11.04 pt, recorded explicitly rather than called exact font-size equality. No raster pixels are masked or aligned. Raw comparison differences remain visible in `compare.py --allow-content-differences` reports; that option alone is not a content assertion.

The contents-page text/page-number anchors differ by at most 0.444 pt horizontally; body text by at most 0.303 pt. Pages 1–2 differ by at most 0.093 pt vertically. On page 3, Word suppresses six points of heading space-before at the explicit page transition while the engine retains it: the full body is approximately 6.1 pt lower, with identical content, wrapping and pagination. This is a recorded remaining paragraph-spacing difference, not pixel-identical Word output. Leader glyph counts also differ. All twelve page pairs were visually reviewed; 89 evidence files have frozen hashes, and all Word/engine source package entries are byte-identical despite ZIP-container differences.

## Semantics and regression

Heading levels, contents/entry roles, caption labels and known figure/table associations, logical reference targets and stable fragment ownership survive field iteration and page breaks. Reading order is retained independently of paint order; an absent source association is not guessed. Tests cover semantic hierarchy, stable bounded identities, immutable snapshots, fragment offsets, table/header/frame ownership and reference relationships. These are prerequisites for later PDF/A-1a/2a/3a work, not emitted tags. Structure trees, marked-content/annotation associations, language/alternative inputs, independent level-A and separate PDF/UA qualification remain part 15b.

The engine-only golden suite adds one two-page contents/reference fixture, visually reviewed using PDFBox and independent Poppler. All twenty existing pages, their inventory rows and font/logo inputs are byte-identical. The new tests retain actual-PDF text, links, target pages and embedded-font assertions, and registered-provider tests validate all four current PDF profiles independently (sixteen PDFs / forty-eight pages, including twelve archival files).

Playground and REST use the existing provider without new controls or contract changes; no transport, publication, release, site or deployment change is needed. Field grammar follows Microsoft's primary documentation for [SEQ](https://support.microsoft.com/en-us/word/field-codes-seq-sequence-field), [REF](https://support.microsoft.com/en-us/word/field-codes-ref-field), [PAGEREF](https://support.microsoft.com/en-us/word/field-codes-pageref-field) and [TOC](https://support.microsoft.com/en-us/word/field-codes-toc-table-of-contents-field), within the explicitly qualified subset above.
