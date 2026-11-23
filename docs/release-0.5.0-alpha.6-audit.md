# 0.5.0-alpha.6 candidate runtime and distribution review

## Qualification

[PR #316](https://github.com/NuaForma/NuaForma/pull/316) qualifies the native Word compatibility and CI changes. The product checks cover Java 17 library usage, the full module suite and coverage, the actual Sonar gate and both native architectures. SonarCloud reported 80.0% coverage on new code against the required 80%. See the [Build run](https://github.com/NuaForma/NuaForma/actions/runs/45084560381) and [native container checks](https://github.com/NuaForma/NuaForma/actions/runs/45084560332), which reused the [full qualification for the same inputs](https://github.com/NuaForma/NuaForma/actions/runs/45083628463).

The local test inventory recorded 2,222 JVM tests with zero failures or skips and 19,081 of 20,342 executable lines covered. After optimizing reference handling, ten targeted JVM tests and the request fixture and image input assembly passed. The additional 83 focused tests passed locally, covering supported image and text-frame geometry, paragraph and field behavior, and explicit source rejection boundaries. The corpus yielded 68 of 250 PDFs. The last qualifying round preserved 320 earlier pages and produced two additional independently validated PDF/A-3b documents.

## Runtime and corresponding sources

The Debian snapshot, shipped Temurin JRE pins and third-party dependency inputs use the [0.5.0-alpha.5 source baseline](release-0.5.0-alpha.5-audit.md). The AMD64 builds retain Temurin 17.0.21.1+2 and 25.0.5.1+2. The ARM64 builds retain the previously qualified runtimes. Adoptium scopes the November Base64 intrinsic issue to affected x86-64 systems. The newer ARM64 archives have become available. This candidate retains the verified ARM64 runtime pins and corresponding-source coverage. [Adoptium advisory](https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell/) [Runtime guide](java-runtime-compatibility.md)

Release preparation compares the actual native image inventories and all corresponding-source identities against the reviewed `sources-v0.5.0-alpha.5` release. The gate publishes only the exact signed candidate selected in the protected GitHub review.

## Remaining findings

The [runtime review](runtime-dependency-review-2026-10-30.md) and [issue #51](https://github.com/NuaForma/NuaForma/issues/51) track open findings, including the bundled FreeType CID issue. The protected final review covers those findings specifically for this evaluation alpha.

The candidate publishes the six signed libraries to Nexus prereleases, the two multiarch GHCR indexes and the GitHub prerelease. Publication evidence supplies the exact image digests for the matching website and installation kits.
