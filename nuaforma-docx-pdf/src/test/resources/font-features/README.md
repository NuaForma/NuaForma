# Font-feature golden files

These synthetic DOCX cases have independently exported Word reference PDFs and
initial visually reviewed native 96-DPI page PNGs. Modern and legacy compatibility,
feature switches, effective-size kerning thresholds, standard ligatures, inherited
and direct formatting, passive run boundaries, Greek/Cyrillic and Letter are covered.

The Noto Serif 2.015 test font's exact hash is recorded in each Word receipt. See
the [formatting proof](../../../../../tools/pdf-evaluation/own-engine/FORMATTING.md)
and the PDF module's font README for provenance and license. Word export is not a
build or runtime requirement. `SHA256SUMS` covers the frozen source, Word receipt,
Word PDF and native golden PNGs.

Golden files must not be replaced merely because a regression fails. Any replacement
requires a documented reason, visual review and explicit maintainer approval.
Ordinary tests write candidate outputs only under the ignored build directory.
