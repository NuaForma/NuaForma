# 0.4.0-alpha.2 candidate: runtime and distribution review

The single protected final approval must explicitly accept the retained native-runtime findings for **0.4.0-alpha.2 only**. This preparation record does not presume approval. Keep [#51](https://github.com/NuaForma/NuaForma/issues/51) open; historical alpha risk acceptances do not transfer.

## Current candidate and unchanged runtime inputs

This release adds precise development package-part diagnostics (#190/#192) and Playground-only template cleanup with a separate removal report (#191/#193). Remaining template instructions stay literal; the caller alone judges content/appearance. Production generation stays strict. Contract 0.9 adds the optional cleanup facet while retaining compatible older providers. Runtime dependencies, OS/JVM pins and validator boundaries remain unchanged from the reviewed alpha.5 baseline. Official Adoptium release metadata was rechecked on 2026-11-02: the latest selected Java 17 and 25 releases remain **17.0.21+9** and **25.0.5+8**.

- Validators use the unmodified official Java 17 archives and their previously reviewed AMD64/ARM64 SHA-256/signatures.
- Server/Playground retain the official Java 25 index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec` and pinned native children.
- Debian retains base index `sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4` and authenticated package snapshot `20261030T000000Z`.
- Libraries/validators retain Java 17; server and Playground retain Java 25. No older-Java patch exception is introduced.

The successful full qualification of PR #193 identifies source `f951285726e9003859aa7b519657128c043aa45b`: Java 17/25 and independent consumers run [41446391973](https://github.com/NuaForma/NuaForma/actions/runs/41446391973), native AMD64/ARM64 production/Playground run [41446392276](https://github.com/NuaForma/NuaForma/actions/runs/41446392276), and actual `sonarqubecloud` Code Analysis/Quality Gate OK with 80.8% new-code coverage, zero duplication and no new issues. The full local build contains 1,129 passing JVM tests; all 16 existing Playground JavaScript checks pass. A cleaned public logo-letter template produces an independently validated PDF with identical text and rendered pixels; the page was visually inspected. This does not impose automatic visual acceptance on users. No private template or existing golden was changed or uploaded.

The signed develop merge `b67f038440e4ebf42d7e118cf086f6873d3b57d2` has the identical qualified tracked tree and its checks/snapshot pass in run [41448930181](https://github.com/NuaForma/NuaForma/actions/runs/41448930181). This reuses the original analyzed source, not a new Sonar scan. Release preparation must match complete qualification fingerprints and bind the selected signed tag, inventories and public source materials.

## Retained findings and source provision

The retained 2026-10-30 review reports 77 advisory IDs across 21 OS source packages, and no matches for its 199 selected Maven coordinates. These are dated coordinate observations, not a new 2026-11-02 vulnerability scan or exploitability clearance. Named Expat fixes remain in the selected package, while other findings require individual review. Debian and JVM-bundled FreeType remain distinct; the reviewed OpenJDK trees lack the specific CID-overlap guard for CVE-2026-95512. The TrueType-only boundary is not a complete reachability proof or blanket non-applicability disposition.

Template cleanup does not dispose of the JVM font/XML findings or establish non-reachability. The retained shaping path still uses physical-font APIs with caller-provided bytes; keep the same untrusted-input caution and #51 follow-up.

The final approval accepts these documented residual findings for this evaluation candidate while remediation continues under #51. It does not authorize a general production-safety claim.

Reuse the complete, unchanged [alpha.5 source baseline](release-0.3.0-alpha.5-audit.md), its server/Playground material records and public `sources-v0.3.0-alpha.5` archives. Public GitHub asset metadata retains the recorded uploaded states, bounded sizes and SHA-256 digests for the Debian, Java and JVM source/notice archives. Original source inputs were fully reviewed for alpha.5; the approved release procedure verifies their unchanged public identities without downloading hundreds of megabytes again. Every candidate foreign JAR, native package/JRE inventory and source URL must match this baseline before any binary distribution.

All six libraries, 24 detached signatures and four native image archives must be complete and bound to the candidate before the one protected final review. Publication targets Nexus prereleases, both GHCR indexes and the GitHub prerelease. The matching website follows that same approval after actual artifacts/digests are public. Existing sources, releases, runtime profiles, document fixtures and golden files are not overwritten.
