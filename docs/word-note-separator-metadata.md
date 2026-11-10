# Word note separator metadata

Unreleased source #292 qualifies bounded nonprinting run properties in ordinary footnote and endnote separator definitions. The private DOCX adapter retains the existing shared separator geometry; it adds no public model, package, dependency or permissive mode.

## Qualified structures

The separator control may have empty run properties or an explicit 12- or 18-point control size. One matching special-note reference run may precede the control, with an optional bounded character-style identifier. These special definitions do not introduce another owning note call, visible label or font requirement. Original namespace, attribute, duplicate, hierarchy and on/off checks remain authoritative before typed binding. Changed paragraph spacing, custom visible separator contents, extra/reordered references and other unqualified control formatting remain explicit failures.

The original and cleaned template bytes remain intact when otherwise qualified. Template expressions are not evaluated by Playground cleanup. Conversion and cleanup use the same source checks; the Playground adds no separate interpretation.

## Independent evidence and remaining limits

Twenty synthetic exact-source Word pairs cover A4/Letter, ordinary and continued footnotes and document-end endnotes. Word variant/control pages are pixel-identical at 144 dpi. Twelve initial native control PNGs were visually reviewed; protected existing goldens were not replaced. Standard PDF and representative PDF/A-1b/2b/3b cases are exercised separately.

This metadata support preserves existing pagination and separator positioning. In the long A4 control, native output places the final body paragraph on page two while Word retains it on page one; all 38 note-line markers remain exactly once on the same two-page document. The automatic endnote control retains a 0.7-point baseline difference from Word. Those cases use exact native goldens, complete content inventories and independent Word counterfactuals; they do not lower the strict Word-geometry checks for ordinary cases or claim to solve these existing limits.

The combined source candidate passed the normal full build (1,759 tests), including provider regressions, plus real HTTP and Playground cleanup/reuse checks. Reproduce source parts with `python tools/pdf-evaluation/own-engine/build_note_separator_fixtures.py --base nuaforma-docx-pdf/src/test/resources/note-separator-formatting --output build/note-separator-sources`; the immutable control inputs remain the authoring base. Export new exact-source Word references separately. Existing sources, exports and golden files require their normal review before replacement.
