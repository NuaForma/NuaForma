# 0.5.0-alpha.2 candidate: runtime and distribution review

The single protected final approval must explicitly accept retained native-runtime findings for **0.5.0-alpha.2 only**. This preparation does not presume approval; keep #51 open.

## Candidate and qualification

This release adds the development-only missing-font diagnostic improvement from [PR #302](https://github.com/NuaForma/NuaForma/pull/302), merged as verified signed commit `d46619746715cdce7f4ece9144aa5d5042879cbf`. Font family/variant and mapped source coordinates are visible in the existing Playground Cause line and development logs. Production retains restricted diagnostics. Font selection, synthetic-bold fallback, PDF rendering, embedding and independent validation policies are unchanged. No dependencies, public contracts or golden files change.

Full qualification used signed source `0b3890093c44bd8d1250880de11b6307f7562bab`: Java 17/25 [run 43021025452](https://github.com/NuaForma/NuaForma/actions/runs/43021025452), native AMD64/ARM64 [run 43021025362](https://github.com/NuaForma/NuaForma/actions/runs/43021025362), and successful actual SonarCloud Code Analysis from `sonarqubecloud` on that exact source (87.2% new coverage, zero new duplication). Version/documentation-only merges may reuse these complete receipts; no new analysis of identical inputs is claimed.

Seventeen focused JVM tests, existing Playground JavaScript tests and actual standalone production/Playground HTTP probes passed. Only development responses/logs expose sanitized bounded font details; both ordinary real-HTTP controls generated independently validated PDFs. Mixed and unnamed diagnostic failures retain their original semantics. No private document/font is included. Existing qualified source-profile limits remain; a missing-font error is not proof that the rest of a document can render.

The matching site must explain missing-font diagnostics and bind the actual published version, source and image digests while preserving unchanged examples, runtime profiles and pipe starters. No full product tests are repeated as a release step.

## Unchanged runtime inputs and retained findings

Runtime dependencies, OS/JVM pins, validator boundaries and corresponding sources remain unchanged from the reviewed alpha.5 baseline. Official Adoptium release metadata was rechecked on 2026-11-11: latest selected Java lines remain **17.0.21+9** and **25.0.5+8**. No older-Java patch exception is introduced.

- Validators retain the unmodified official Java 17 archives with their reviewed native SHA-256/signatures.
- Server/Playground retain Java 25 index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec` and its pinned AMD64/ARM64 children.
- Debian retains index `sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4` and authenticated package snapshot `20261030T000000Z`.

The retained [2026-10-30 review](runtime-dependency-review-2026-10-30.md) reports 77 advisory IDs across 21 OS source packages and no matches for its 199 selected Maven coordinates. These are dated observations, not a new vulnerability scan or exploitability clearance. Debian and JVM-bundled FreeType remain distinct; reviewed OpenJDK trees lack the specific CID-overlap guard for CVE-2026-95512. Word compatibility changes do not dispose of the font/XML findings or establish non-reachability. Physical-font parsing still accepts caller-provided bytes under existing resource boundaries. The final approval must accept documented residual findings for this evaluation candidate only while remediation continues under #51; no general production-safety claim is made.

Reuse the complete unchanged [alpha.5 source baseline](release-0.3.0-alpha.5-audit.md), its application material records and public `sources-v0.3.0-alpha.5` archives. Preparation verifies unchanged public asset metadata, uploaded states, size bounds and exact SHA-256 digests without downloading large source archives again. Candidate foreign JARs, native inventories, source URLs and notices must match the baseline before distribution.

## Publication

All six libraries, 24 detached signatures and four native image archives must be bound to the candidate before the single protected final review. Publish to Nexus prereleases, both GHCR multiarch indexes and the GitHub prerelease; keep Maven Central and `latest` unchanged. Bind and publish the matching website only after public release artifacts and actual digests are verified. Existing sources, releases, runtime profiles, document fixtures and goldens are not overwritten.
