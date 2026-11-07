# Reviewed checkbox goldens

These synthetic fixtures were independently exported by Microsoft Word and
visually reviewed on 2026-11-07. See the [qualification report](../../../../../tools/pdf-evaluation/own-engine/CHECKBOXES.md).

`source.docx` is the filled synthetic DOCX whose hash is recorded by the adjacent
Word-export provenance JSON. `word-reference.pdf` is that real Word export.
`page-N.png` is the reviewed native output at 96 DPI. `SHA256SUMS` records the
reference bytes. Font licenses/provenance remain in the existing fixture-font
resources; no symbol font or additional font asset is introduced.

Tests generate candidates in ignored build output and never replace these files.
Changes to an accepted golden require a specific reason and explicit review.
No private document or customer data was used.

Before first delivery the maintainer requested larger bare marks and inherited bold
weight, then approved the revised preview. These checkbox references were refreshed
for that deliberate visual change, with fresh Word provenance. Unrelated existing
golden cases were not changed.
