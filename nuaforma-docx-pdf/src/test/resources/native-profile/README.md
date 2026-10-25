# Native adapter fixtures

`word-letter.docx` is a real Word-saved variant of the synthetic letter produced by `tools/pdf-evaluation/own-engine/build_fixture.py`. Microsoft Word 16.0.20527.20088 opened that source with macros disabled, exported the reference PDF and saved a new DOCX through `tools/pdf-evaluation/word_geometry.ps1 -CanonicalCopy`. The original synthetic source was unchanged. The private QA directory also retains the Word reference PDF; this DOCX is not a generated-PDF golden image.

The checked-in package replaces only the core-properties creator/last-modifier with `NuaForma QA` and creation/modification dates with `2026-10-25T00:00:00Z`. Preserve `cp`, `dc`, `dcterms` and `xsi` namespace bindings when serializing core properties: `xsi:type` values refer to `dcterms:W3CDTF`. Other part bytes are copied from the Word output. Repack sorted parts with ZIP timestamps `2026-10-25 00:00:00`, ZIP_DEFLATED and create_system 0. No display content or print properties are edited to obtain a test pass.

- Synthetic source SHA-256: `35579a7316b6e37363294a9335aabae2ee57825ed4a287be0d76c61c78c46246`.
- Sanitized Word package SHA-256: `06c572c9c0b04576b27a03542761630789d432e3679842f1704d103d82c31e8b`.

The tests cover the Word package and the normal DOCX generator round trip. Known editing-only properties are interpreted by the native adapter; effective unsupported properties still reject. Existing approved PDF-engine golden files remain unchanged and require separate maintainer approval before replacement.

`image-jpeg.base64` is copied at test-resource processing time from the existing synthetic container fixture; it is not duplicated or distributed in production artifacts.
