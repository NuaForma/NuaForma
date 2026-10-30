# Java runtime compatibility

Issue [#71](https://github.com/NuaForma/NuaForma/issues/71) splits the source build without changing the embedded API or previously published artifacts.

| Component | Compile/API target | Execution |
| --- | --- | --- |
| contracts, core, template, DOCX, PDF, DOCX/PDF libraries | Java 17 | Java 17 minimum |
| Standalone REST server | Java 25 | Java 25 |
| Separate development Playground application | Java 25 | Java 25; not a production server dependency |
| Separate PDFBox and veraPDF validators | Java 17 | Pinned Temurin 17.0.21+9 |

Install both JDKs for the complete build. Gradle's explicit module toolchains, Java `--release` and Kotlin `-Xjdk-release` prevent a Java 25 build daemon from exposing newer JDK APIs to libraries. Set `JDK17` and `JDK25` if installations are not discovered automatically. Library tests run on the Java 17 toolchain and server/architecture/REST tests on Java 25, independently of the daemon JVM. CI runs the full build with Java 17 and Java 25 daemons, preserving the required `Build (Java 17)` check, and runs independently compiled Java/Maven and Kotlin/Gradle consumers on Java 17. Publication checks inspect the Java-17-effective bytecode of all six libraries and their resolved runtime dependencies. The JVM split does not change library coordinates or contract version; dated dependency updates are documented separately below.

## Why validators retain Java 17

PDFBox and veraPDF parse the native engine's final PDF bytes in separate request-private child JVMs. SerialGC, 256 MiB heap, 128 MiB metaspace, 64 MiB compressed class/code caches, two active processors, 512 KiB stacks, 1.5 GiB address space, CPU/file limits, bounded output, deadlines and reaping remain on Java 17. The maintainer's subsequent approval removes Bubblewrap and its nested namespaces, not the independent JVM/resource boundary. A newer runtime is not automatically assumed to fit these bounds.

The image stores validator Java at `/opt/java/openjdk` and server Java at `/opt/java/server`. The validator launcher invokes the former directly with a fixed validator classpath and request-private home/temp directory; inherited environment is cleared by the host. Both processes share the ordinary container filesystem/network boundary and runtime default policies. The server retains its explicit heap cap and container CPU/memory/PID limits. Java 25 runtime defaults apply to the application; no preview features or permissive native-access flags are enabled.

## Extensions

Java 17 providers remain usable on the Java 25 server. Operators may deliberately compile server-only extensions for Java 25, but those binaries cannot be advertised as usable by Java 17 embedded applications. Startup checks the running JVM's effective multi-release class view, rejects conflicting classes, invalid headers, newer unsupported bytecode and preview classes before provider discovery, and cleans the private snapshot on failure. Contract/version checks remain separate from class-file compatibility. Installed extensions remain trusted executable code, not sandboxed templates.

## Runtime inputs and distribution

The [stable-component policy](../ARCHITECTURE.md#stable-components-and-upgrade-debt) retains Java 17 for embedded libraries/validators and Java 25 for server/Playground, while requiring current stable security-patch review. The pinned source inputs are below. Reassess any approved patch exception, exact sources and both native platforms before each release through [issue #105](https://github.com/NuaForma/NuaForma/issues/105); the existing pin is not approval to skip the next review.

The source server runtime is unmodified Eclipse Temurin **25.0.5+8**, pinned by OCI index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec`:

- AMD64 child: `sha256:41a70a160a5a157126929c1901e8dcf17ea4fd88eca1e7b9abf3cb1bb04df6c2`.
- ARM64 child: `sha256:49fa0d811061f2b684eb8d32048c6697890e2b169e2cd6bd329d01f03848a606`.
- OpenJDK source identity: `7ec19fb1eda7`; Temurin build scripts: `1cf43d7a3e77886bfea7d550a902e151a87d724d`.
- Official container recipe revision: `86e89ce20ba391b588e243844d0c0d996071b3ed`.

Validator Java uses official unmodified Temurin **17.0.21+9** JRE archives because the official Java 17 moving image still contains the preceding patch. The maintainer approved this packaging choice under #51. Two scratch download stages pin the official AMD64/ARM64 archive SHA-256 values, and a Debian extraction stage copies the complete JRE tree without patching vendor files. Only the selected architecture is fetched for a build.

| Archive | SHA-256 |
| --- | --- |
| `OpenJDK17U-jre_x64_linux_hotspot_17.0.21_9.tar.gz` | `9f41bf88c7900c7097444a1bdeb84146ae692bc08842490ab098063c74bf9a8a` |
| `OpenJDK17U-jre_aarch64_linux_hotspot_17.0.21_9.tar.gz` | `1114f09d6174f54270c3117410b5e4022b43ebc0b43b751af08b927c55ed7bb5` |

Both official Java 17 binary archives and the Java 17/25 source archives were independently SHA-256 checked and their signatures verified against the Adoptium public fingerprint `3B04D753C9050D9A5D343F39843C48A565F8F04B`, using the [vendor verification instructions](https://adoptium.net/installation/gpg-verification). The Java 17 source identity is `551f220d8884`; both runtimes use build scripts `1cf43d7a3e77886bfea7d550a902e151a87d724d`. Signature validation is provenance evidence, not security clearance. Previously published alpha.4 images and their accepted older runtime inputs remain immutable.

The [2026-10-30 review](runtime-dependency-review-2026-10-30.md) separates maintained JVM patches, Debian security packages and the JVM-bundled FreeType copies. Updating the OS does not patch those bundled copies. Source inspection of both selected OpenJDK trees still lacks the specific FreeType CID overlap guard for CVE-2026-95512. No non-applicability or new evaluation-risk acceptance is inferred; retain the #51 disposition and exact-candidate review before distribution.

Both Java installations retain their complete upstream `legal` directories and `release` metadata. The image inventories every regular runtime file in `java-sha256.txt`; source review must bind that inventory before any binary transfer. Source collection retains matching OpenJDK 17 and 25 sources, signatures/metadata, exact Temurin build scripts and container recipe. These remain GPLv2 with the applicable Classpath/assembly exceptions and component-specific notices, not Apache-2.0 NuaForma code. There is no new proprietary runtime dependency. Collecting materials is not legal approval or recipient source provision: the existing exact-image source/license/security and protected publication gates still apply to both architectures.

Native CI must qualify the actual combined image independently on AMD64 and ARM64. The [release procedure](release-process.md) may reuse that green CI when runtime inputs are unchanged; it does not repeat full qualification as a release step. Local AMD64 qualification is not ARM64 evidence, and neither native hosted architecture alone proves a fresh Apple Silicon/Podman integration. Check DOCX, all PDF profiles, caller/mounted fonts, extensions, independent layout/barcodes where approved, limits, concurrency, cancellation and cleanup before release. Track exact commands/results in the issue/PR; do not describe pending checks as passed.

The source service and Playground use Java 25, embedded libraries use Java 17, and independent validators retain their separately pinned Java 17 runtime. REST/OpenAPI consumers are independent of server JVM versions. Match release downloads to the exact qualified candidate.

References: [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html), [Gradle toolchains](https://docs.gradle.org/current/userguide/toolchains.html), [Kotlin compiler options](https://kotlinlang.org/docs/compiler-reference.html), [Temurin release](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5%2B8).

## Source update qualification

The source inputs now select current patches in the approved Java 17/25 lines and the authenticated Debian snapshot `20261030T000000Z`. Each changed source/JVM/package inventory needs native AMD64/ARM64 verification, the existing resource-bound validator checks, PDF profile/font/watermark/barcode qualification and corresponding-source coverage. Source changes are not a new publication or a reuse of alpha.4's patch exception. See [#51](https://github.com/NuaForma/NuaForma/issues/51) and the dated review for current evidence and unresolved findings.
