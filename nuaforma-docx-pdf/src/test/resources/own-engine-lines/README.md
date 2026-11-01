# Protected line regression references

Initial source and four 96-DPI PDFBox raster references for #172, reviewed on 2026-11-01. `build_line_fixture.py` reproduces `source.docx`; `NativeLinesTest` renders it using the retained Liberation Sans faces and compares every page at zero tolerance using image-comparison. The same references apply to standard PDF and independently validated PDF/A-1b/2b/3b. Tests write candidates only beneath `build/172-lines`, never into this directory.

The same source was exported read-only by Microsoft Word 16.0 build 16.0.20527 using `export_word.ps1`. All four Word/native page pairs were visually reviewed with the same PDFium renderer: rules, connected frames, intervening lines, header/footer repetition, position/rotation, double strokes, cell border and pagination remain intact. Text/control identifiers occur on identical pages, with identical extracted words; maximum control displacement is 0.036 pt horizontally and 0.1474 pt vertically. Word's page-size quantization differs by 0.02 pt. These are qualified fixture observations, not a universal pixel-perfect claim.

Source SHA-256: `ce0768b75b42b630230a23d35cabb341ad874fa3efc32a5a3f141ad99e9a41cf`.
Word PDF SHA-256: `bbf99184dbbd5c2297bd43bcc959f4eadd034979aedb06cde9564cf80f87bc98`.
The `inputs.sha256` manifest identifies the initial source/rasters. The qualification report is in [LINES.md](../../../../../tools/pdf-evaluation/own-engine/LINES.md).

These are golden files. Do not regenerate or replace them merely because a comparison fails. A replacement requires an explained intended rendering change, separate visual review and explicit maintainer approval. Existing golden files elsewhere in the project are unchanged.

The builder produces identical XML/media and fixed ZIP metadata on Windows and Linux; compressed ZIP bytes may differ with the zlib version. The SHA-256 identifies the retained source archive, not every equivalent rebuilt container. Rebuilding is never an automatic reference update.
