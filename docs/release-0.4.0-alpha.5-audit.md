# 0.4.0-alpha.5 candidate: runtime and distribution review

The single protected final approval must explicitly accept the retained native-runtime findings for **0.4.0-alpha.5 only**. This preparation record does not presume approval. Keep [#51](https://github.com/NuaForma/NuaForma/issues/51) open; historical evaluation-alpha acceptances do not transfer.

## Candidate and qualification

This release includes #208: validated style revision metadata is omitted only from the rendering view and retained in supported original template bytes. Image failures identify the rejected element/first child or attribute name at its actual source location. Playground cleanup can remove the focused unsupported construct while retaining supported logos, surrounding stories and notes. Required effectExtent attributes are not removed individually: the optional element is removed as a whole. Ordinary generation stays strict; no new visual effects, geometry or external retrieval are enabled.

The full qualification of PR #209 identifies source `3b4bc5eb9b4141b27992e88090933dd59c7015a3`: Java 17/25 run [42018699546](https://github.com/NuaForma/NuaForma/actions/runs/42018699546), native AMD64/ARM64 run [42018699466](https://github.com/NuaForma/NuaForma/actions/runs/42018699466), actual SonarCloud Code Analysis from `sonarqubecloud` and Quality Gate OK with 83.0% new-code coverage. All lanes passed. Signed develop merge `7ed213501b2becfdffb00ea5ffc1fee8b1b6eb1e` reused identical-input qualification and its automatic snapshot succeeded in [42021258536](https://github.com/NuaForma/NuaForma/actions/runs/42021258536). No new analysis of an identical tree is claimed.

The complete local WSL build passed 1,182 tests without failures/errors/skips. The 13 floating-band integration and six metadata tests cover malformed revisions, body/footer effects, attributes and geometry, source privacy and no partial output. Image-comparison at zero tolerance establishes unchanged supported A4/Letter pages after adding valid revision metadata or focused cleanup. Existing goldens and tolerances are unchanged. No new Word reference export was required for this passive-metadata/removal parity; existing source fixtures retain their earlier independent Word qualification. The unavailable private source and its actual remaining effects are not qualified by these synthetic tests.

## Unchanged runtime inputs and retained findings

Runtime dependencies, OS/JVM pins, validator boundaries and corresponding sources remain unchanged from the reviewed alpha.5 baseline. Official Adoptium release metadata was rechecked on 2026-11-06: latest selected Java lines remain **17.0.21+9** and **25.0.5+8**. No older-Java patch exception is introduced.

- Validators retain the unmodified official Java 17 archives with their reviewed native SHA-256/signatures.
- Server/Playground retain Java 25 index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec` and its pinned AMD64/ARM64 children.
- Debian retains index `sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4` and authenticated package snapshot `20261030T000000Z`.

The retained [2026-10-30 review](runtime-dependency-review-2026-10-30.md) reports 77 advisory IDs across 21 OS source packages and no matches for its 199 selected Maven coordinates. These are dated observations, not a new vulnerability scan or exploitability clearance. Debian and JVM-bundled FreeType remain distinct; reviewed OpenJDK trees lack the specific CID-overlap guard for CVE-2026-95512. Word compatibility changes do not dispose of the font/XML findings or establish non-reachability. Physical-font parsing still accepts caller-provided bytes under existing resource boundaries. The final approval must accept documented residual findings for this evaluation candidate only while remediation continues under #51; no general production-safety claim is made.

Reuse the complete unchanged [alpha.5 source baseline](release-0.3.0-alpha.5-audit.md), its application material records and public `sources-v0.3.0-alpha.5` archives. Preparation verifies unchanged public asset metadata, uploaded states, size bounds and exact SHA-256 digests without downloading large source archives again. Candidate foreign JARs, native inventories, source URLs and notices must match the baseline before distribution.

## Publication

All six libraries, 24 detached signatures and four native image archives must be bound to the candidate before the single protected final review. Publish to Nexus prereleases, both GHCR multiarch indexes and the GitHub prerelease; keep Maven Central and `latest` unchanged. Bind and publish the matching website only after public release artifacts and actual digests are verified. Existing sources, releases, runtime profiles, document fixtures and goldens are not overwritten.
