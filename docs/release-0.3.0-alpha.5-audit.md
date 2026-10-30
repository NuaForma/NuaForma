# Alpha.5 candidate: runtime and distribution review

The final protected approval must explicitly accept the remaining native-runtime findings below for **0.3.0-alpha.5 only**. This source record does not claim that approval has already been given. Keep [#51](https://github.com/NuaForma/NuaForma/issues/51) open for vendor fixes and individual reachability dispositions; older alpha exceptions do not transfer.

## Selected runtime inputs

- Validator: unmodified official Temurin **17.0.21+9**, using separately pinned vendor AMD64/ARM64 archives with reviewed SHA-256 and valid Adoptium signatures.
- Server and Playground: unmodified official Temurin **25.0.5+8**, index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec`, with both native child manifests pinned by the source Dockerfile.
- Debian base index `sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4`, immutable authenticated package snapshot `20261030T000000Z`, explicit installed-package upgrades.
- Java 17 remains the public library minimum, Java 25 the server/runtime baseline. The previous older-Java patch exception is not reused.

The dated [dependency/runtime review](runtime-dependency-review-2026-10-30.md) retains upstream provenance, 836 official-byte artifact receipts and compatible update decisions. The official 17.0.21+9 and 25.0.5+8 vendor releases were rechecked during this preparation. The approved Gradle compatibility exception remains documented there; no dependency-verification relaxation or golden-file update is part of this release.

## Current findings to review

The refreshed OS source-package query reports **77 advisory IDs across 21 source packages**. This is a coordinate-match inventory, not an exploitability count. The latest reviewed locked Maven query returned no matches for 199 selected coordinates; it is not a security guarantee.

Expat 2.9.0-1~deb13u1 fixes CVE-2026-76957, CVE-2026-77214 and CVE-2026-93990 according to Debian. Other recorded Expat matches remain open. Debian FreeType stays at 2.13.3+dfsg-1+deb13u1; the matching Java 17/25 trees also lack the specific FreeType CID-overlap guard for CVE-2026-95512. Updating Debian does not replace JRE-bundled FreeType. The qualified TrueType-only input boundary is not a complete reachability proof; no blanket non-applicability or new suppression is asserted.

Final approval accepts these documented remaining findings for this evaluation candidate while remediation continues under #51. No broad production-safety approval or zero-vulnerability statement is implied.

## Corresponding-source coverage

All 85 retained OS-package notice/source mappings are complete without gaps, covering 61 exact Debian source packages. Each Debian descriptor/archive checksum is checked. Three source-only archives contain 200 Debian inputs, eight Java source/signature/build-provenance inputs, and 392 original application/validator source/notice inputs. Archive hashes and every internal source-manifest entry were independently reread and verified. Private documents, operator fonts/extensions, credentials and runtime executable JARs are excluded.

Exact server and Playground inventories from successful native AMD64/ARM64 CI bind the selected packages, both complete JRE file inventories and every foreign runtime JAR to `release-materials/0.3.0-alpha.5.json` and its Playground companion. These records must be completed and independently matched before release preparation. Source-only publication under `sources-v0.3.0-alpha.5` supplies public immutable URLs; the release gate anonymously hashes all three archives before transferring runtime binaries. Original notices and legal directories remain in each image; generated source instructions include the actual source URLs and SHA-256 values.

The existing native CI verifies the supported PDF profiles, fonts, watermarks, extensions, concurrency, cleanup and default runtime policies on both architectures. Candidate preparation may reuse those results only when its runtime inputs match; it builds each release image once and checks actual inventory identity. It does not repeat the functional qualification. All 24 library artifacts and four container archives must be complete and hash-bound before the single final review.
