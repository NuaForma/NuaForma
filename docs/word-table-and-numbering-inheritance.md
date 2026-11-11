# Word table placement and numbering inheritance

Unreleased work #279–#282 extends the existing DOCX/PDF adapter. It adds no public API, dependency, package or fallback converter. The original template is never overwritten. The Playground uses the same provider, including strict source diagnostics and its separate cleanup operation.

## Signed physical table indentation

Direct and inherited `tblInd` in `dxa` units can be negative. Decimal spellings are bounded before truncation toward zero, matching the observed Word parser: `-100.8` becomes `-100` twips, and `-0.8` becomes an explicitly declared zero. Zero must remain a declaration because legacy layout distinguishes it from omission. Direct zero overrides an inherited negative indent.

The source bound is −288,000 through +288,000 twips; the shared renderer independently checks actual physical placement and content bounds. Unsupported units, malformed numbers, foreign or duplicate properties remain errors. This does not allow arbitrary off-page tables. Cleanup reports numeric canonicalization while preserving unrelated part bytes and produces a reusable template.

Twenty-eight independent fixed-width Word controls cover compatibility modes 12 and 15, direct/inherited placement, explicit zero, negative fractions and integer controls on A4 and Letter. The recorded Word parser observations accompany the fixtures. Additional automatic-width and nested-table probes are research evidence, not a universal fidelity claim. In particular, legacy 100%-wide tables that Word allows into the right margin require a separate shared-model decision. Combining splitting rows with nested tables remains outside the existing profile.

## Previous table grids

A bounded `tblGridChange` describes a previous grid, not the current visible columns. It is validated before binding and omitted only from the request-local print view. The closed profile has one previous grid with 1–16 positive, bounded column widths, a nonnegative identifier and optional bounded author/date metadata. Active descendants, nested history, malformed numbers and unknown properties remain errors.

Cleanup preserves the historical structure, canonicalizing qualified fractional widths when needed. Inactive author text containing a brace never becomes a template instruction. Four actual Word controls confirm that changing historical column counts or widths does not change the current table. Native tests reuse the existing preferred-width golden images without replacing them. This does not add general revision rendering or tracked-change acceptance.

## Numbering definitions and paragraph styles

A valid abstract numbering `styleLink` advertises the definition to a named numbering style. It does not redirect the concrete definition. Missing or non-numbering named styles do not activate unused fonts or change the selected rules. The qualified association is omitted only from the private print view; cleanup preserves it. Active `numStyleLink` redirection remains explicitly unsupported.

For the qualified paragraph-style cases, instance identifiers and levels inherit independently. A directly declared level wins over the most specific inherited level; if neither is present, the level is zero. An abstract level's paragraph-style association or a heading's outline level does not replace that rule. Direct `numId=0` disables numbering. Actual instances, available levels, source-ordered counters, restarts and existing content limits remain checked.

Sixty-six actual Word exports on A4 and Letter cover base/derived styles, independent identifier/level overrides, missing levels, explicit zero, disabled numbering, changed instances, built-in headings, custom outlines and missing definition-style targets. The controls without a styles-effects cache also exercise the original source rather than relying on a duplicated part. Counter checks run concurrently with independent readers.

Malformed paragraph numbering is checked before binding can discard it: unknown attributes, duplicate properties, nested/text payloads and invalid numbers identify their original part and element. Cleanup can remove a specific unsupported attribute without deleting valid sibling values. Property text is not visible document text and does not become a template expression.

Microsoft documents the distinction between a definition's [style association](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.stylelink) and [Word's handling of style numbering properties](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/6a8f14ff-df90-4154-9bc9-e0e88ae5e641). The independently exported controls define the qualified scope; there is no claim of complete Word numbering compatibility.

## Verification status

The maintained builders take the checked-in `implicit-table-indent` controls for signed indentation, and `preferred-table-widths` for grid/numbering controls. Generate into a new directory; each builder refuses existing fixture directories. Compare uncompressed ZIP entries with the checked-in sources. Word exports are made read-only from those exact synthetic sources, with source/PDF hashes in the adjacent provenance receipt. Native golden images are initially generated from the qualified provider, then visually reviewed alongside the independent Word checks. Tests only read these files and never regenerate or replace them.

All 98 new synthetic standard-PDF routes pass independent Word text, marker-position, paper-size and embedded-font checks. Sixteen initial native golden pages were reviewed; no protected golden was replaced. Representative fixtures also run standard PDF and PDF/A-1b/2b/3b, with independent output validation. Additional original-source, invalid-input, cleanup/idempotence and concurrent-counter tests cover the corresponding boundaries.

The normal full build passes all 1,682 tests, including 763 in the DOCX/PDF adapter, with no failures, errors or skips. The corrected affected matrix also passes all 13 tests. Libraries and independent PDF validation run on Java 17; server integration and the separate Playground run on Java 25.

The final server qualification produces 116 PDFs across standard PDF and the three B-level archival profiles. All 116 native pages match their protected or initially reviewed golden images exactly. Word text and paper geometry agree; 429 marker positions differ by at most 0.10 point. All 24 invalid HTTP requests reject with their original part/element and no published PDF. Nine actual Playground cleanup/download/idempotence/reuse routes retain identical native pixels, text and embedded fonts. Inherited numeric canonicalization updates both matching style parts when necessary.

The unchanged 250-source corpus retains ten PDFs and fifteen exact native pages against the preceding run. Forty-two first diagnoses differ from the earlier baseline: 39 shifts were already present in the feature prototype; three additional changes identify invalid original numbering levels earlier. These are first-barrier changes, not 42 newly converted documents. Corpus results are regression checks rather than Word-fidelity assertions for private original documents. No private samples, fonts or generated corpus documents are distributed with this work.
