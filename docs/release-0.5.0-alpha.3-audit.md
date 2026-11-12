# 0.5.0-alpha.3 candidate: runtime and distribution review

The single protected final approval must explicitly accept retained native-runtime findings for **0.5.0-alpha.3 only**. This preparation does not presume approval; keep #51 open.

## Candidate and qualification

This release adds the development-only concrete render diagnostic from [PR #305](https://github.com/NuaForma/NuaForma/pull/305), merged as verified signed commit `a7515d3997f610443ece8b144b77526475ceb153`. The existing Playground Cause line and correlated development logs retain the native failure code and fixed allowlisted explanation. Unknown/custom messages retain the code with a fixed fallback. Production remains restricted; no source coordinates or font identities are invented. Font selection, rendering/embedding, independent validation, cancellation, limits and atomic output are unchanged. No dependencies, public contracts or golden files change.

Full qualification used signed source `cd4aa3b890f125e6b8971c3529b3db93e295cd7e`: Java 17/25 [run 43092436948](https://github.com/NuaForma/NuaForma/actions/runs/43092436948), native AMD64/ARM64 [run 43092436910](https://github.com/NuaForma/NuaForma/actions/runs/43092436910), both successful on attempt 2 after GitHub artifact-service recovery. Actual SonarCloud Code Analysis from `sonarqubecloud` passed on that exact source with 99.6% new coverage and zero new duplication. Version/documentation-only merges may reuse the complete receipts; no new analysis of identical inputs is claimed.

Nineteen focused JVM and twenty Playground JavaScript tests passed. Actual standalone production/Playground HTTP probes checked eight rejected renders with development-only correlated causes and four successful independently validated standard/PDF-A-3b controls. Missing glyphs, physical paragraph overflow, custom/private validator messages and preserved failure classifications are covered. No private document/font is included. Existing source-profile limits remain; a successful cleanup is not proof of renderability.

The matching site must explain concrete render failures and bind the actually published version, source and image digests while preserving existing examples, runtime profiles, benchmarks and pipe starters. No full product tests are repeated as a release step.

## Unchanged runtime inputs and retained findings

Runtime dependencies, OS/JVM pins, validator boundaries and corresponding sources remain unchanged from the reviewed alpha.5 baseline. Official Adoptium release metadata was rechecked on 2026-11-12: latest selected Java lines remain **17.0.21+9** and **25.0.5+8**. No older-Java patch exception is introduced.

- Validators retain the unmodified official Java 17 archives with their reviewed native SHA-256/signatures.
- Server/Playground retain Java 25 index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec` and its pinned AMD64/ARM64 children.
- Debian retains index `sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4` and authenticated package snapshot `20261030T000000Z`.

The retained [2026-10-30 review](runtime-dependency-review-2026-10-30.md) reports 77 advisory IDs across 21 OS source packages and no matches for its 199 selected Maven coordinates. These are dated observations, not a new vulnerability scan or exploitability clearance. Debian and JVM-bundled FreeType remain distinct; reviewed OpenJDK trees lack the specific CID-overlap guard for CVE-2026-95512. Word compatibility changes do not dispose of the font/XML findings or establish non-reachability. Physical-font parsing still accepts caller-provided bytes under existing resource boundaries. The final approval must accept documented residual findings for this evaluation candidate only while remediation continues under #51; no general production-safety claim is made.

Reuse the complete unchanged [alpha.5 source baseline](release-0.3.0-alpha.5-audit.md), its application material records and public `sources-v0.3.0-alpha.5` archives. Preparation verifies unchanged public asset metadata, uploaded states, size bounds and exact SHA-256 digests without downloading large source archives again. Candidate foreign JARs, native inventories, source URLs and notices must match the baseline before distribution.

## Publication

All six libraries, 24 detached signatures and four native image archives must be bound to the candidate before the single protected final review. Publish to Nexus prereleases, both GHCR multiarch indexes and the GitHub prerelease; keep Maven Central and `latest` unchanged. Bind and publish the matching website only after public release artifacts and actual digests are verified. Existing sources, releases, runtime profiles, document fixtures and goldens are not overwritten.
