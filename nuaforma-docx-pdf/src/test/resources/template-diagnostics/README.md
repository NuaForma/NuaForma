# Diagnostic PDF golden pages

Initial references for issue #150, visually reviewed on 2026-10-28. `TemplateDiagnosticPdfTest` reproducibly inserts `Prefix {broken` before the retained synthetic `native-profile/word-letter.docx` letter, calls the development diagnostic provider with the retained Liberation Sans faces and independent standard-PDF validator, and renders at 96 DPI with the pinned PDFBox runtime on Java 17.

Page 1 preserves the logo, styled letter and original literal input, with a red `[ERROR 1]` label at the unmatched opening brace. Page 2 contains the failure explanation, actual parser excerpt/caret, part and paragraph. Both pages were inspected for wrapping, clipping and legibility. This is diagnostic-layout verification, not a new Word-fidelity claim.

Tests write candidate PDF/PNGs only under `build/150-diagnostic-golden` and compare these immutable references with image-comparison. They never create, update or delete resource baselines. Later replacement requires an explained old/new comparison and explicit maintainer approval under AGENTS.md. Missing references deliberately fail the test; initial creation is distinct from baseline replacement.
