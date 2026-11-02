# 0.4.0-alpha.3 candidate: runtime and distribution review

The single protected final approval must explicitly accept the retained native-runtime findings for **0.4.0-alpha.3 only**. This preparation record does not presume approval. Keep [#51](https://github.com/NuaForma/NuaForma/issues/51) open; historical alpha risk acceptances do not transfer.

## Current candidate and unchanged runtime inputs

This release preserves qualified Word paragraph-mark metrics, physical-page indents, measured header/footer bands and a bounded EMF GDI subset (#196/#197). Synthetic A4/Letter documents retain a large header logo, smaller footer logo and actual footnotes. Unknown/unsafe EMF constructs still reject explicitly. Runtime dependencies, OS/JVM pins and validator boundaries remain unchanged from the reviewed alpha.5 baseline. Official Adoptium release metadata was rechecked on 2026-11-02: the latest selected Java 17 and 25 releases remain **17.0.21+9** and **25.0.5+8**.

- Validators use the unmodified official Java 17 archives and their previously reviewed AMD64/ARM64 SHA-256/signatures.
- Server/Playground retain the official Java 25 index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec` and pinned native children.
- Debian retains base index `sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4` and authenticated package snapshot `20261030T000000Z`.
- Libraries/validators retain Java 17; server and Playground retain Java 25. No older-Java patch exception is introduced.

The full qualification of PR #197 identifies source `db470fce1f20dcb93002ae7fb9899a2644fb5145`: Java 17/25 and consumers run [41486735377](https://github.com/NuaForma/NuaForma/actions/runs/41486735377), native AMD64/ARM64 run [41486734985](https://github.com/NuaForma/NuaForma/actions/runs/41486734985), and actual `sonarqubecloud` Code Analysis/Quality Gate OK with 83.8% new-code coverage and no open issues. Java 25's first attempt stopped before tests on Maven Central HTTP 429; only that failed job was rerun successfully. Original qualification, not a new analysis of an identical release tree, is reused.

Signed develop merge `45d38b601d1c59b04220a8d8d1796013b28c77cf` retains the qualified tracked inputs; its checks and automatic snapshot passed in [41491816738](https://github.com/NuaForma/NuaForma/actions/runs/41491816738). Release version/documentation changes must match complete qualification fingerprints and bind the selected signed source/tag, inventories and public materials.

The synthetic A4 and Letter comparisons each have two pages and identical position-sorted words per page against real Word exports. The maximum word-box top difference is 0.649 pt (about 0.23 mm). All four comparison pages were visually reviewed. These are bounded synthetic proofs, not qualification of the unavailable private original or a universal pixel-perfect claim. Existing golden files are unchanged. See the [Word compatibility profile](../tools/pdf-evaluation/own-engine/WORD_COMPATIBILITY.md).

## Retained findings and source provision

The retained 2026-10-30 review reports 77 advisory IDs across 21 OS source packages, and no matches for its 199 selected Maven coordinates. These are dated coordinate observations, not a new 2026-11-02 vulnerability scan or exploitability clearance. Named Expat fixes remain in the selected package, while other findings require individual review. Debian and JVM-bundled FreeType remain distinct; the reviewed OpenJDK trees lack the specific CID-overlap guard for CVE-2026-95512. The TrueType-only boundary is not a complete reachability proof or blanket non-applicability disposition.

Word/EMF compatibility does not dispose of the JVM font/XML findings or establish non-reachability. The retained shaping path still uses physical-font APIs with caller-provided bytes; keep the same untrusted-input caution and #51 follow-up.

The final approval accepts these documented residual findings for this evaluation candidate while remediation continues under #51. It does not authorize a general production-safety claim.

Reuse the complete, unchanged [alpha.5 source baseline](release-0.3.0-alpha.5-audit.md), its server/Playground material records and public `sources-v0.3.0-alpha.5` archives. Public GitHub asset metadata retains the recorded uploaded states, bounded sizes and SHA-256 digests for the Debian, Java and JVM source/notice archives. Original source inputs were fully reviewed for alpha.5; the approved release procedure verifies their unchanged public identities without downloading hundreds of megabytes again. Every candidate foreign JAR, native package/JRE inventory and source URL must match this baseline before any binary distribution.

All six libraries, 24 detached signatures and four native image archives must be complete and bound to the candidate before the one protected final review. Publication targets Nexus prereleases, both GHCR indexes and the GitHub prerelease. The matching website follows that same approval after actual artifacts/digests are public. Existing sources, releases, runtime profiles, document fixtures and golden files are not overwritten.
