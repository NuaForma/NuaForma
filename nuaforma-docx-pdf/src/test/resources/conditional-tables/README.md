# Conditional-table golden files

Fourteen synthetic one-page cases retain genuine Word PDF exports, their source/PDF
hash receipts, and initially reviewed native 96-DPI page PNGs. They cover regional
precedence, horizontal/vertical bands, missing band sizes, repeated headers,
modern/legacy Normal-style exceptions, direct overrides, faces, colors and fills.
`SHA256SUMS` records all source/reference/golden bytes.

Word can occlude thin grid segments beneath conditional cell shading. The native
goldens retain the complete specified grid; the Word PDFs remain unchanged.
See the [formatting proof](../../../../../tools/pdf-evaluation/own-engine/FORMATTING.md)
for this visible difference, font provenance and reproduction. Golden replacement
requires a reason, visual review and explicit approval. Tests write only candidates
under the ignored build directory.
