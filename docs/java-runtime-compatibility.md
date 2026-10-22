# Java runtime compatibility

Issue [#71](https://github.com/NuaForma/NuaForma/issues/71) splits the source build without changing the embedded API or previously published artifacts.

| Component | Compile/API target | Execution |
| --- | --- | --- |
| contracts, core, template, DOCX, DOCX/PDF libraries | Java 17 | Java 17 minimum |
| Standalone REST server | Java 25 | Java 25 |
| Separate development Playground application | Java 25 | Java 25; not a production server dependency |
| Sandboxed PDFBox and veraPDF validators | Java 17 | Pinned Temurin 17.0.20.1+1 |
| LibreOffice | Native executable | Unchanged native AMD64/ARM64 runtime |

Install both JDKs for the complete build. Gradle's explicit module toolchains, Java `--release` and Kotlin `-Xjdk-release` prevent a Java 25 build daemon from exposing newer JDK APIs to libraries. Set `JDK17` and `JDK25` if installations are not discovered automatically. Library tests run on the Java 17 toolchain and server/architecture/REST tests on Java 25, independently of the daemon JVM. CI runs the full build with Java 17 and Java 25 daemons, preserving the required `Build (Java 17)` check, and runs independently compiled Java/Maven and Kotlin/Gradle consumers on Java 17. Publication checks inspect the Java-17-effective bytecode of all five libraries and their resolved runtime dependencies. No library coordinates, contract version or dependency versions change.

## Why validators retain Java 17

Only the outer REST service JVM is upgraded. PDFBox and veraPDF parse untrusted renderer output in separate request-private child processes. Their existing SerialGC, 256 MiB heap, 128 MiB metaspace, 64 MiB compressed class/code caches, two active processors, 512 KiB stacks and 1.5 GiB address-space ceiling were qualified on Java 17, including procfs-free operation. A newer collector/ergonomic or native-loading implementation is not assumed to fit those bounds. Retaining this runtime keeps the deliberate PDF security/resource boundary intact while modernizing the server.

The image stores validator Java at `/opt/java/openjdk` and server Java at `/opt/java/server`. Only the former is mounted at `/validator/java` inside Bubblewrap; no server application, extensions, credentials or neighboring request is added. The private namespaces, device allowlist, both seccomp policies, network denial, timeouts, single conversion slot and fail-closed readiness remain unchanged. The server keeps its explicit heap cap and container CPU/memory/process limits; upgrading Java is not permission to increase budgets or suppress JVM warnings. Java 25 runtime defaults apply to the outer server; no preview features or permissive native-access flags are enabled.

## Extensions

Java 17 providers remain usable on the Java 25 server. Operators may deliberately compile server-only extensions for Java 25, but those binaries cannot be advertised as usable by Java 17 embedded applications. Startup checks the running JVM's effective multi-release class view, rejects conflicting classes, invalid headers, newer unsupported bytecode and preview classes before provider discovery, and cleans the private snapshot on failure. Contract/version checks remain separate from class-file compatibility. Installed extensions remain trusted executable code, not sandboxed templates.

## Runtime inputs and distribution

The [stable-component policy](../ARCHITECTURE.md#stable-components-and-upgrade-debt) keeps approved major/API baselines while preferring current stable security patches. For evaluation release `0.2.0-alpha.2`, the maintainer explicitly approved retaining the pins below on 2026-10-22 rather than waiting for complete upstream Temurin 25.0.5 AMD64/ARM64 inputs. Both application architectures retain the same 25.0.4.1+1 runtime; validators separately retain 17.0.20.1+1. This is not a claim that the newer patch is implemented, qualified or unnecessary for security. See the [release-specific risk and reassessment condition](release-0.2.0-alpha.2.md). Track exact sources, native qualification and the final inventory review in [issue #105](https://github.com/NuaForma/NuaForma/issues/105); reassess the exception before the next release/runtime change rather than carrying it forward silently.

The Java 25 server runtime is unmodified Eclipse Temurin 25.0.4.1+1, pinned by OCI index `sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab`:

- AMD64 child: `sha256:78498f30dd330b06755c1b134039dcbf8324bedb385136819dd519b842a3816a`.
- ARM64 child: `sha256:c31e783e090e5bcf4ddee2bfbce5bf78b5d832daae66767cb9a2315b8d4802b5`.
- OpenJDK source identity: `520406d87195`; Temurin build scripts: `e6ba7dec3d07654074559310376a3ae89da5f4ac`.
- Official container recipe revision: `511f9356dc4d50932a0a5f8cfb0f87ed1aef4f07`.

The 2026-10-15 upstream review selected the latest GA patch in the Java 25 line, not the original 25 GA build. Adoptium identifies 25.0.4.1+1 and the retained 17.0.20.1+1 as its [security-only CSPU releases](https://adoptium.net/news/2026/09/eclipse-temurin-8u504-110321-170201-210121-25041-26021-available). This is a dated patch-selection disposition, not a claim of zero vulnerabilities. Existing Debian/native findings tracked in issue #51 are not removed or automatically accepted for a future release by this JVM change; re-evaluate the exact combined image and subsequent vendor advisories at the distribution gate.

Both Java installations retain their complete upstream `legal` directories and `release` metadata. The image inventories every regular runtime file in `java-sha256.txt`; source review must bind that inventory before any binary transfer. Source collection retains matching OpenJDK 17 and 25 sources, signatures/metadata, exact Temurin build scripts and container recipe. These remain GPLv2 with the applicable Classpath/assembly exceptions and component-specific notices, not Apache-2.0 NuaForma code. There is no new proprietary runtime dependency. Collecting materials is not legal approval or recipient source provision: the existing exact-image source/license/security and protected publication gates still apply to both architectures.

Native CI must qualify the actual combined image independently on AMD64 and ARM64. Local AMD64 qualification is not ARM64 evidence, and neither native hosted architecture alone proves a fresh Apple Silicon/Podman integration. Check DOCX, all PDF profiles, caller/mounted fonts, extensions, independent layout/barcodes where approved, limits, concurrency, cancellation and cleanup before release. Track exact commands/results in the issue/PR; do not describe pending checks as passed.

Release `0.2.0-alpha.1` uses Java 25 for the production service and separate Playground, Java 17 for embedded libraries, and the pinned Java 17 child validators. Historical `0.1` images remain immutable Java 17 releases. The matching website kits follow the mandatory release synchronization checklist. REST/OpenAPI consumers are independent of server JVM versions.

References: [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html), [Gradle toolchains](https://docs.gradle.org/current/userguide/toolchains.html), [Kotlin compiler options](https://kotlinlang.org/docs/compiler-reference.html), [Temurin release](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1).
