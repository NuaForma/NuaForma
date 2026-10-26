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
