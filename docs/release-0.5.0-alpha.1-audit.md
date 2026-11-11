# 0.5.0-alpha.1 candidate: runtime and distribution review

The single protected final approval must explicitly accept retained native-runtime findings for **0.5.0-alpha.1 only**. This preparation does not presume approval; keep #51 open.

## Candidate and qualification

This release includes checkbox content #149, bounded EMF pen compatibility #223 and the completed native Word compatibility work delivered through PR #299. The latter merged as signed commit `339b66507da7ba6cded209f2d534902709f09a6f`. It closes 69 completed compatibility issues while leaving research #243/#272/#289/#295 open. Scope and limitations are recorded in the owning feature guides and release notes, rather than promising arbitrary DOCX support.

Full qualification used signed source `f0d5d6d994c90a80fa4e78e6b3061c05ec94e126`: Java 17/25 run [43000225875](https://github.com/NuaForma/NuaForma/actions/runs/43000225875), native AMD64/ARM64 run [43000225721](https://github.com/NuaForma/NuaForma/actions/runs/43000225721), and successful actual SonarCloud Code Analysis from `sonarqubecloud` on that source. Matching documentation/version-only merge/promotion checks may reuse these complete receipts; no new analysis of identical inputs is claimed.

The normal local build passed 1,781 JVM tests (852 in the production DOCX/PDF adapter), without failures/errors/skips. The latest group passed 34 real-HTTP PDFs / 52 exact native golden pages, six actual Playground cleanup/reuse cases / eight exact golden pages, and sixteen negative HTTP requests with no PDF bytes. Independent Word controls cover A4/Letter, complete text, fonts, geometry and specific supported profiles. Prior group evidence remains in its feature guides.

The unchanged private corpus produces eleven PDFs from 250 inputs; all fifteen previously successful pages retain exact pixels. A newly converted document has a trailing blank fourth page and no actual Word-export reference; this limitation remains explicit. Only two mixed-Latin golden pages were replaced under specific maintainer approval with documented improved Word geometry. Other protected goldens, authored sources and original Word exports remain intact. No private document/font is included.

The approved additive public-model changes remain in existing artifacts with no new runtime dependency. Java 17 libraries, Java 25 applications and bounded independent Java 17 validators retain their boundaries. All supported PDF profiles keep independent validation and embedding policy. The website update must document checkbox syntax, bounded compatibility and actual published image digests; unchanged document examples and pipe starters are retained.

## Unchanged runtime inputs and retained findings

Runtime dependencies, OS/JVM pins, validator boundaries and corresponding sources remain unchanged from the reviewed alpha.5 baseline. Official Adoptium release metadata was rechecked on 2026-11-11: latest selected Java lines remain **17.0.21+9** and **25.0.5+8**. No older-Java patch exception is introduced.

- Validators retain the unmodified official Java 17 archives with their reviewed native SHA-256/signatures.
- Server/Playground retain Java 25 index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec` and its pinned AMD64/ARM64 children.
- Debian retains index `sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4` and authenticated package snapshot `20261030T000000Z`.

The retained [2026-10-30 review](runtime-dependency-review-2026-10-30.md) reports 77 advisory IDs across 21 OS source packages and no matches for its 199 selected Maven coordinates. These are dated observations, not a new vulnerability scan or exploitability clearance. Debian and JVM-bundled FreeType remain distinct; reviewed OpenJDK trees lack the specific CID-overlap guard for CVE-2026-95512. Word compatibility changes do not dispose of the font/XML findings or establish non-reachability. Physical-font parsing still accepts caller-provided bytes under existing resource boundaries. The final approval must accept documented residual findings for this evaluation candidate only while remediation continues under #51; no general production-safety claim is made.

Reuse the complete unchanged [alpha.5 source baseline](release-0.3.0-alpha.5-audit.md), its application material records and public `sources-v0.3.0-alpha.5` archives. Preparation verifies unchanged public asset metadata, uploaded states, size bounds and exact SHA-256 digests without downloading large source archives again. Candidate foreign JARs, native inventories, source URLs and notices must match the baseline before distribution.

## Publication

All six libraries, 24 detached signatures and four native image archives must be bound to the candidate before the single protected final review. Publish to Nexus prereleases, both GHCR multiarch indexes and the GitHub prerelease; keep Maven Central and `latest` unchanged. Bind and publish the matching website only after public release artifacts and actual digests are verified. Existing sources, releases, runtime profiles, document fixtures and goldens are not overwritten.
