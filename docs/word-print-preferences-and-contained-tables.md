# Word print preferences and contained nested tables

Unreleased source work #283–#287 and the #279 follow-up remain within the existing DOCX adapter and shared PDF engine. The approved additive `PdfTable.rightOverflow` / `withRightOverflow(points)` model extension retains existing constructors and defaults to zero. No package, dependency, font substitution or fallback converter is added. The following scope is locally qualified against genuine exact-source Word references and the normal full build; it is not a published release claim.

## Numbering display names

A bounded abstract-numbering `name` is a display identifier. A qualified name occurs at most once per abstract definition, is nonblank and at most 253 characters, and contains only the expected value attribute with no nested/text payload. They are checked before typed binding and omitted only from private print interpretation. Concrete numbering definitions and counters remain unchanged. Literal braces and non-Latin metadata do not become instructions or require fonts. Active LISTNUM fields and `numStyleLink` redirection retain their separate restrictions.

## Image save resolution and theme editing

The closed `w14:defaultImageDpi` preference has a required `w14:val` positive integer up to 2,147,483,647. The adapter validates its original namespace, attributes and uniqueness, then excludes this save preference from the print copy. It does not change explicit picture extents, embedded image bytes, rendering resolution, compression policy or image budgets. Source controls cover multiple save-DPI values with body/header/footer pictures and both enabled and disabled automatic picture compression.

The `w:styleLockTheme` on/off flag controls theme editing. Its original leaf structure and namespace remain validated. Printed theme colors and actual font selection continue through their existing resolvers; the editing lock is omitted only from the print copy. Cleanup and the original template retain these settings unchanged.

Microsoft describes [default image DPI](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.office2010.word.defaultimagedpi) as a save preference, with separate `useLocalDpi` and `doNotAutoCompressPictures` precedence. The [theme lock](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.stylelockthemespart) prevents theme-part modification.

## Gutter-position preferences with zero width

Validated `sectPr/rtlGutter` and `settings/gutterAtTop` flags may be retained in a source with an absent or explicitly zero gutter width. Their position has no physical effect in this profile, so the print interpretation omits the flags. The existing page-margin guard still rejects every nonzero gutter width. The flags do not select paragraph text direction or alter bidi handling.

The source checks reject duplicates, unknown attributes, foreign names and nested payloads before binding. Independent controls cover ordinary A4/Letter pages and four-page mirrored sections, including odd/even placement and numbering restarts. Microsoft's definitions describe [right-side gutters](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.gutteronright) and [top-positioned gutters](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.gutterattop). General binding margins remain outside the qualified profile.

## Signed nested indentation

Word's qualified nested-table layout clamps a negative table indent to zero while retaining positive indentation. The adapter validates the original signed value and applies that observed clamp only to nested print placement. Top-level signed indentation remains unchanged. Containing-cell and physical page bounds remain enforced; the shared renderer's existing restrictions are retained.

Only the existing supported nesting depth, row-splitting and border combinations apply. In particular, this change does not make a splitting parent row with a nested table supported. The synthetic Word controls use a nonsplitting parent row and retain both negative and explicit-zero child indentation in compatibility modes 12 and 15 on A4/Letter.

## Legacy percentage-table right margin

The shared model optionally permits a bounded amount of right-margin use through `PdfTable.withRightOverflow(points)`. The value must be finite and between zero and 14,400 points. Existing constructors retain zero, so callers must explicitly opt in. Width resolution, document normalization and header/footer field replacement preserve the value. The additional room cannot bypass the physical page edge or a nested table's containing-cell edge; the opted-in outer stroke must also fit.

For root-level legacy Word percentage tables with a declared effective indent, the adapter retains the established percentage reference including cell padding and allows the qualified right padding to extend into the page margin. This does not enable arbitrary page overflow, general nested-table overflow, right/center table alignment or an unqualified modern-width rounding policy. All sixteen exact-source legacy Word probes pass for negative and zero indentation with physical automatic, natural, 50% and 100% preferences on A4/Letter. Marker offsets are at most 0.09 point horizontally and 0.081 point vertically. The combined final qualification is recorded below.

## Verification

The normal full build passes 1,715 tests: 788 in the DOCX/PDF adapter and 331 in the shared PDF module, with zero failures, errors or skipped tests. The final candidate freezes 330 changed/new source and fixture files and 91 runtime JARs. The compiled inputs and installed/frozen runtime JARs agree.

Seventy synthetic source/Word-reference pairs span 94 Word pages. The maintained builders reproduce every uncompressed ZIP entry and all Word provenance hashes are checked. Forty-two metadata sources spanning 66 pages match both independent counterfactuals and existing Word baselines exactly in pixels and text. Existing native goldens are borrowed by path, including both theme pages and all four mirrored pages. Twenty new initial native pages, eight for nested tables and twelve for legacy widths, were visually reviewed. No existing protected golden was replaced.

Final real HTTP qualification produces 97 PDFs and 133 pages that match the protected/initial native images exactly. All source text, paper geometry and embedded-font checks pass; 273 Word marker positions differ by at most 0.09 point horizontally and 0.12 point vertically. Seven invalid HTTP requests reject with the original part/element and no PDF. Nine actual Playground cleanup/download/idempotence/reuse routes retain the complete original template bytes and produce thirteen exact native pages with independent Word checks.

All 250 local corpus inputs remain unchanged. Ten PDFs retain fifteen pixel-exact earlier pages; no additional complete conversion is claimed. Eight first diagnoses change: six feature-related movements and two exact per-request public-font availability movements. These corpus checks compare earlier native output, not Word fidelity. Private source documents, fonts and intermediates remain local.

The optional allowance defaults to zero and preserves every earlier Java 17 public table signature; only the getter and immutable option method are added. The existing A4/Letter, nesting, splitting, font and output-profile restrictions remain authoritative. CI/Sonar, a PR, merge, artifact release and site publication are outside this local qualification.
