# 0.5.0-alpha.4 candidate: runtime and distribution review

The single protected final approval must explicitly accept retained native-runtime findings for **0.5.0-alpha.4 only**. This preparation does not presume approval; keep #51 open.

## Candidate and qualification

[PR #309](https://github.com/NuaForma/NuaForma/pull/309), merged as verified signed `72518b0951d680fb7a1502209bfd25fb3638d57c`, adds styled soft breaks, physical ink bounds, contained wide fixed tables, bounded long-word wrapping and shared exact font-family qualification. Existing constructors/copy entry points remain; exhaustive Kotlin `PdfInline` handling must add `PdfLineBreak`. No dependency, module, resource limit or independent validation boundary changes.

Full qualification used signed `b90c1f9b66650144acd04036038b1229c98bc3e0`: Java 17/25 [run 43741472519](https://github.com/NuaForma/NuaForma/actions/runs/43741472519) and native AMD64/ARM64 [run 43741472526](https://github.com/NuaForma/NuaForma/actions/runs/43741472526), both successful. Actual SonarCloud Code Analysis from `sonarqubecloud` passed on that exact source: Quality Gate OK, zero bugs, vulnerabilities or code smells. Develop [run 43745197537](https://github.com/NuaForma/NuaForma/actions/runs/43745197537) correctly reused the matching Java qualification and published an anonymously verified signed snapshot. Reuse does not claim a new analysis of the merge tree.

The normal local build passes 1,862 JVM tests. Independent Word controls, real production/Playground standard/PDF-A output, cleanup/reuse, physical boundary negatives and retained production redaction are qualified. New initial golden fixtures are included; existing golden images and private corpus/font inputs are not replaced or distributed. The local 250-document corpus yields fourteen conversions with unchanged source hashes; see [the release notes](../release-notes/0.5.0-alpha.4.md) for the bounded fidelity evidence and limitations.

The website must explain the qualified soft breaks, cell wrapping and precise font names, bind the actual published source and image digests, and retain its protected examples, benchmarks and pipe starters. No full product tests are repeated as a release step.

## Unchanged runtime inputs and retained findings

Runtime dependencies, OS/JVM pins, validator boundaries and corresponding sources remain unchanged from the reviewed alpha.5 baseline. Official Adoptium GitHub release metadata was rechecked on 2026-11-15: latest selected Java lines remain **17.0.21+9** and **25.0.5+8**. No older-Java patch exception is introduced.

- Validators retain the unmodified official Java 17 archives with their reviewed native SHA-256/signatures.
- Server/Playground retain Java 25 index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec` and its pinned AMD64/ARM64 children.
- Debian retains index `sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4` and authenticated package snapshot `20261030T000000Z`.

The retained [2026-10-30 review](runtime-dependency-review-2026-10-30.md) reports 77 advisory IDs across 21 OS source packages and no matches for its 199 selected Maven coordinates. These are dated observations, not a new vulnerability scan or exploitability clearance. Debian and JVM-bundled FreeType remain distinct; reviewed OpenJDK trees lack the specific CID-overlap guard for CVE-2026-95512. Word compatibility changes do not dispose of the font/XML findings or establish non-reachability. Physical-font parsing still accepts caller-provided bytes under existing resource boundaries. The final approval must accept documented residual findings for this evaluation candidate only while remediation continues under #51; no general production-safety claim is made.

Reuse the complete unchanged [alpha.5 source baseline](release-0.3.0-alpha.5-audit.md), its application material records and public `sources-v0.3.0-alpha.5` archives. Preparation verifies unchanged public asset metadata, uploaded states, size bounds and exact SHA-256 digests without downloading large source archives again. Candidate foreign JARs, native inventories, source URLs and notices must match the baseline before distribution.

## Publication

All six libraries, 24 detached signatures and four native image archives must be bound to the candidate before the single protected final review. Publish to Nexus prereleases, both GHCR multiarch indexes and the GitHub prerelease; keep Maven Central and `latest` unchanged. Bind and publish the matching website only after public release artifacts and actual digests are verified. Existing sources, releases, runtime profiles, document fixtures and goldens are not overwritten.
