# 0.4.0-alpha.6 candidate: runtime and distribution review

The single protected final approval must explicitly accept the retained native-runtime findings for **0.4.0-alpha.6 only**. This preparation record does not presume approval. Keep [#51](https://github.com/NuaForma/NuaForma/issues/51) open; historical evaluation-alpha acceptances do not transfer.

## Candidate and qualification

This release includes #213 / PR #214: bounded passive picture metadata, corrected described-EMF header classification and local Playground cleanup results. It does not add general shapes, OpenGL, visible line effects or the deferred #149 checkbox pipe. Supported source bytes are retained and normal generation remains strict.

Full qualification used signed source `0efd30d9336684500c99eea3072b77be6c60d19b`: Java 17/25 run [42247097053](https://github.com/NuaForma/NuaForma/actions/runs/42247097053), native AMD64/ARM64 run [42247096929](https://github.com/NuaForma/NuaForma/actions/runs/42247096929), actual SonarCloud Code Analysis from sonarqubecloud and Quality Gate OK (90.7% new-code coverage). Signed develop merge `ed7ffba51a4531296343fa970d0a6e64a1e7f49c` reused the unchanged qualification and published its signed snapshot in [42254291117](https://github.com/NuaForma/NuaForma/actions/runs/42254291117). No new analysis of an identical tree is claimed.

The full stable-head local WSL build passed 1,189 JVM tests without failures/errors/skips; all 19 Playground JavaScript tests passed. Synthetic A4/Letter fixtures qualify both logo positions, unchanged cleanup bytes and zero-tolerance PDF comparisons. Negative tests retain strict metadata/header/resource checks. An earlier local run saw a changing-HEAD build-identity mismatch; a stable-head build resolved it without changing code or tests. Existing goldens are unchanged. The private original was unavailable; this proof is bounded to public synthetic variants. No new Word export was required for passive metadata/header parity against existing independently qualified supported fixtures.

## Unchanged runtime inputs and retained findings

Runtime dependencies, OS/JVM pins, validator boundaries and corresponding sources remain unchanged from the reviewed alpha.5 baseline. Official Adoptium release metadata was rechecked on 2026-11-06: latest selected Java lines remain **17.0.21+9** and **25.0.5+8**. No older-Java patch exception is introduced.

- Validators retain the unmodified official Java 17 archives with their reviewed native SHA-256/signatures.
- Server/Playground retain Java 25 index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec` and its pinned AMD64/ARM64 children.
- Debian retains index `sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4` and authenticated package snapshot `20261030T000000Z`.

The retained [2026-10-30 review](runtime-dependency-review-2026-10-30.md) reports 77 advisory IDs across 21 OS source packages and no matches for its 199 selected Maven coordinates. These are dated observations, not a new vulnerability scan or exploitability clearance. Debian and JVM-bundled FreeType remain distinct; reviewed OpenJDK trees lack the specific CID-overlap guard for CVE-2026-95512. Word compatibility changes do not dispose of the font/XML findings or establish non-reachability. Physical-font parsing still accepts caller-provided bytes under existing resource boundaries. The final approval must accept documented residual findings for this evaluation candidate only while remediation continues under #51; no general production-safety claim is made.

Reuse the complete unchanged [alpha.5 source baseline](release-0.3.0-alpha.5-audit.md), its application material records and public `sources-v0.3.0-alpha.5` archives. Preparation verifies unchanged public asset metadata, uploaded states, size bounds and exact SHA-256 digests without downloading large source archives again. Candidate foreign JARs, native inventories, source URLs and notices must match the baseline before distribution.

## Publication

All six libraries, 24 detached signatures and four native image archives must be bound to the candidate before the single protected final review. Publish to Nexus prereleases, both GHCR multiarch indexes and the GitHub prerelease; keep Maven Central and `latest` unchanged. Bind and publish the matching website only after public release artifacts and actual digests are verified. Existing sources, releases, runtime profiles, document fixtures and goldens are not overwritten.
