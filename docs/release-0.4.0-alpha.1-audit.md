# 0.4.0-alpha.1 candidate: runtime and distribution review

The single protected final approval must explicitly accept the retained native-runtime findings for **0.4.0-alpha.1 only**. This preparation record does not presume approval. Keep [#51](https://github.com/NuaForma/NuaForma/issues/51) open; historical alpha risk acceptances do not transfer.

## Current candidate and unchanged runtime inputs

This release includes passive Word package/settings compatibility and the Playground favicon (#182/#183/#185), and qualified effective formatting (#184/#186): automatic spacing, mirrored indents, conditional table regions and requested kerning/standard ligatures. Runtime dependencies, OS/JVM pins and validator boundaries remain unchanged from the reviewed alpha.5 baseline. The new licensed Noto Serif asset is test-only and is not shipped in production artifacts. Official Adoptium release metadata was rechecked on 2026-11-01: the latest selected Java 17 and 25 releases remain **17.0.21+9** and **25.0.5+8**.

- Validators use the unmodified official Java 17 archives and their previously reviewed AMD64/ARM64 SHA-256/signatures.
- Server/Playground retain the official Java 25 index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec` and pinned native children.
- Debian retains base index `sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4` and authenticated package snapshot `20261030T000000Z`.
- Libraries/validators retain Java 17; server and Playground retain Java 25. No older-Java patch exception is introduced.

The successful full qualification of PR #186 identifies source `bef979c8ade39316242f144d79abf2fdda8ccc0d`: JVM Java 17/25 and five independent consumers run [41344285469](https://github.com/NuaForma/NuaForma/actions/runs/41344285469), native AMD64/ARM64 production/Playground run [41344285600](https://github.com/NuaForma/NuaForma/actions/runs/41344285600), and actual `sonarqubecloud` Code Analysis/Quality Gate OK with 88.8% new-code coverage and no open issues. The formatting proof contains 33 public synthetic DOCX cases and 60 genuine Word-reference pages; existing golden files are unchanged. The signed develop merge `f07402e242994427e4892d566c1327b3b1ea0ccd` has the identical qualified source tree and its checks/snapshot pass in run [41345708606](https://github.com/NuaForma/NuaForma/actions/runs/41345708606). This is reuse of the original analyzed revision, not a claim of a new Sonar scan. Release preparation must match complete qualification fingerprints and independently bind the selected signed tag, inventories and source materials.

## Retained findings and source provision

The retained 2026-10-30 review reports 77 advisory IDs across 21 OS source packages, and no matches for its 199 selected Maven coordinates. These are dated coordinate observations, not a new 2026-11-01 vulnerability scan or exploitability clearance. Named Expat fixes remain in the selected package, while other findings require individual review. Debian and JVM-bundled FreeType remain distinct; the reviewed OpenJDK trees lack the specific CID-overlap guard for CVE-2026-95512. The TrueType-only boundary is not a complete reachability proof or blanket non-applicability disposition.

The new shaping path uses physical-font APIs with caller-provided font bytes. This does not dispose of the JVM font/XML findings or establish non-reachability; retain the same untrusted-input caution and #51 follow-up.

The final approval accepts these documented residual findings for this evaluation candidate while remediation continues under #51. It does not authorize a general production-safety claim.

Reuse the complete, unchanged [alpha.5 source baseline](release-0.3.0-alpha.5-audit.md), its server/Playground material records and public `sources-v0.3.0-alpha.5` archives. Public GitHub asset metadata retains the recorded uploaded states, bounded sizes and SHA-256 digests for the Debian, Java and JVM source/notice archives. Original source inputs were fully reviewed for alpha.5; the approved release procedure verifies their unchanged public identities without downloading hundreds of megabytes again. Every candidate foreign JAR, native package/JRE inventory and source URL must match this baseline before any binary distribution.

All six libraries, 24 detached signatures and four native image archives must be complete and bound to the candidate before the one protected final review. Publication targets Nexus prereleases, both GHCR indexes and the GitHub prerelease. The matching website follows that same approval after actual artifacts/digests are public. Existing sources, releases, runtime profiles, document fixtures and golden files are not overwritten.
