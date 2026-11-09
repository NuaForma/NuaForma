# Physical OOXML trash items

Unreleased source work for #247 recognizes discarded ZIP items named exactly `[trash]/hhhh.dat`, where each `h` is a hexadecimal digit. These are physical archive items, not logical document parts. The OOXML package rules explicitly allow them without a content type; ordinary document parts still require one.

Discarded bytes never participate in template parsing, style resolution or PDF layout. They are read as opaque data and still count toward archive entry, expanded-byte and integrity limits. Cancellation and CRC checks remain active. Internal document relationships cannot target these items. Associating a discarded item with a content type is invalid, including a `dat` default; malformed names receive no exception.

The original input is never modified. DOCX serialization may omit discarded items because they are outside the logical document. Playground cleanup is a structural qualification operation, not a promise to remove every unused physical byte: an already qualified package can be returned unchanged. Review cleaned templates before adopting them.

Regression coverage uses deterministic, in-memory additions to the existing synthetic A4 and Letter theme-color fixtures: opaque binary bytes and text resembling an unfinished template marker. All four PDF profiles must retain the existing golden pages, per-document text, page sizes and embedded fonts. The Word references belong to the unchanged logical fixtures; no new Word export or golden replacement is claimed for an archive-only change. Tests also reject typed trash, relationship targets and lookalike names, and enforce byte/entry limits, cancellation and CRC integrity.

The package rule is documented in Microsoft's [Office Open XML Part 1 — Fundamentals](https://download.microsoft.com/download/e/1/4/e14fb96f-83b8-4a2a-84db-7fa8acbe061a/Office%20Open%20XML%20Part%201%20-%20Fundamentals.pdf), under “Trash Items”. No dependency or public API is added. Both embedded generation and the Playground use the same validation rules.
