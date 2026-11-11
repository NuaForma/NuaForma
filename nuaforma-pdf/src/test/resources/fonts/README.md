# Foundation test fonts

These four unmodified Liberation Sans 2.1.5 fonts are test-only fixtures from Debian package `fonts-liberation` version `1:2.1.5-3`, installed from the Debian package repository. Upstream: https://github.com/liberationfonts/liberation-fonts, release 2.1.5. Retain the complete adjacent `COPYRIGHT`, including copyright statements and SIL Open Font License 1.1, when redistributing these fonts or the proof PDF. They are not installed by the engine or included in production/source library JARs. Engine users supply their own font bytes.

| File | SHA-256 |
|---|---|
| LiberationSans-Regular.ttf | `4659bc0c58c5028dd488ec928d41d9265db43d9b669fc14ca8b0832daca7b144` |
| LiberationSans-Bold.ttf | `3973aa5054fb467dd5627245d3dc82e37bf16fe075756156a570455871351582` |
| LiberationSans-BoldItalic.ttf | `c80fa7f2ffa0e01d4d8dcd6a6d1e43eda665222d1b4db597dde1174c456006cf` |
| LiberationSans-Italic.ttf | `830c5fa600505fb4c1a271b4271c53c44bae43f492b2a240d0e98a3a7a380121` |

Permission-flag mutations in tests are in-memory rejection cases only. No modified font is distributed or used to produce a successful reference document. The letter fixture uses the existing approved NuaForma icon from `assets/branding`, copied into test resources during the build rather than duplicated in source.

Reproduce these assets by obtaining the exact Debian package above, extracting its four `usr/share/fonts/truetype/liberation/LiberationSans-{Regular,Bold,Italic,BoldItalic}.ttf` files unchanged, and retaining the package's `usr/share/doc/fonts-liberation/copyright`. Verify each SHA-256 before replacing a fixture. This package provenance is not an operating-system requirement for building or using the engine.

## OpenType qualification font

`NotoSerif-Regular.ttf` is the unmodified hinted static TrueType face from the official [Noto Serif 2.015 release](https://github.com/notofonts/latin-greek-cyrillic/releases/tag/NotoSerif-v2.015), reviewed on 2026-11-01. The source commit is `c4a321e123e4d4ff315f57f4e0adf294fe3a95be`; the annotated release tag is `1eee5de7230d240118f8ad8d1e5fe4c91acae943`. Retain the complete adjacent `NotoSerif-OFL.txt`, including the Noto Project Authors' copyright and SIL Open Font License 1.1. This is a test fixture, with no new Java dependency or production font installation.

Extract `NotoSerif/hinted/ttf/NotoSerif-Regular.ttf` and `OFL.txt` from `NotoSerif-v2.015.zip`. The reviewed archive passed its ZIP CRC checks. Verify these SHA-256 values:

| Asset | SHA-256 |
|---|---|
| Official release ZIP | `0e9a43c8a4b94ac76f55069ed1d7385bbcaf6b99527a94deb5619e032b7e76c1` |
| NotoSerif-Regular.ttf | `19e72cd8d595fae5bd74a5206f5d938512e1183d4fed7abb1ec1be1d7efa5f88` |
| NotoSerif-OFL.txt | `cee9892f9f0cc8fe882c9e9537ee6a89621d86ee7ceaf70b02e2b2b1c25c061a` |

This face supplies real standard ligatures and OpenType kerning. The Word oracle activates this exact font temporarily through `export_word_fonts.ps1`, records its hash and deactivates it in `finally`. Portable tests read only retained font bytes. Existing Liberation Sans assets and raster references remain unchanged.

## Synthetic-bold qualification faces (#234)

The unmodified hinted `NotoSerif-Light.ttf` and `NotoSerif-LightItalic.ttf` faces come from the same reviewed release ZIP and source/tag above, under the same adjacent `NotoSerif-OFL.txt`. Extract the matching files under `NotoSerif/hinted/ttf/`. Their actual families include `Noto Serif Light`; they supply only regular/italic faces, allowing Word to demonstrate synthetic bold without proprietary font fixtures. They remain test-only, outside published library JARs and production images.

| File | SHA-256 |
|---|---|
| NotoSerif-Light.ttf | `0f3960036f3f9c3c88f813ac9c51681645c21df60aadaf4a34a4767a2e8aadbd` |
| NotoSerif-LightItalic.ttf | `320bf4124bb3f702602265aa543ceada4e9de54b4dfd00e84382c1febae91e94` |


## Private-glyph qualification font (#258)

`FontAwesomeFree-Solid.ttf` is an unmodified Font Awesome Free 6.7.2 standalone TrueType fixture from official source commit `af620534bfc3c2d4cbefcfeec29603bbe7809e64`, path `webfonts/fa-solid-900.ttf`. Its exact source, Git blob, size and SHA-256 are retained in `FontAwesomeFree-provenance.json`. Retain the full adjacent `FontAwesomeFree-LICENSE.txt`; the font program is under SIL Open Font License 1.1. Its internal family/face, outlines, coverage and embedding flags are unchanged. The different local filename does not alter the font program.

The reviewed immutable TTF supplies real private-use glyphs with assigned Unicode aliases pointing to the same physical glyphs. It is test data, not a production font installation or Java dependency. Production/source library JARs and container font packages do not acquire this fixture. Font permissions and missing-glyph checks remain active.

The selected format is deliberately the retained 6.7.2 standalone TTF. The newer reviewed 7.3.1 source supplies webfont formats outside this input profile; no conversion, metadata forgery or new decoder is introduced. Track replacement under #258 when an unmodified supported-format asset can be independently qualified; golden references are not automatically upgraded.

| Asset | SHA-256 |
| --- | --- |
| FontAwesomeFree-Solid.ttf | `af19d135d3a935b3ebfbd80320716ffe1202052c5f68dc2c5f1abc57005ac605` |
| FontAwesomeFree-LICENSE.txt | `9b914eae88817d63b576eab5aafde7068c7a1abae125d7cdfb034f1da43a9afc` |

Official source: [font](https://github.com/FortAwesome/Font-Awesome/blob/af620534bfc3c2d4cbefcfeec29603bbe7809e64/webfonts/fa-solid-900.ttf), [complete license](https://github.com/FortAwesome/Font-Awesome/blob/af620534bfc3c2d4cbefcfeec29603bbe7809e64/LICENSE.txt). Download those exact bytes and verify the hashes to reproduce the asset. Ordinary builds and tests use checked-in bytes and do not download fonts.
