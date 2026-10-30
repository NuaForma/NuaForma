# Runtime and dependency review — 2026-10-30

Issue [#51](https://github.com/NuaForma/NuaForma/issues/51). The inventory sections preserve the pre-upgrade observations used for the maintainer decision. The maintainer subsequently approved the compatible updates and official Java 17 archive packaging; local implementation and qualification results are recorded below; delivery checks remain pending. This report is not release clearance, and no artifact has been published. Java 17 libraries/validators and Java 25 server/Playground remain the required baselines.

## Immediate remediation route

Before this review, the Dockerfile used a Debian snapshot dated **2026-10-12**, so running `apt-get update` only refreshed indexes from that old snapshot. Change the authenticated immutable snapshot deliberately, then install/upgrade the affected packages in a newly built image. Do not mutate deployed containers or switch to floating security inputs. Updating an OCI base digest alone does not guarantee that packages installed from the old snapshot are patched.

The **2026-10-30T000000Z Debian security snapshot** contains `libexpat1` **2.9.0-1~deb13u1** for both AMD64 and ARM64. The retained native inventory has **2.8.3-1~deb13u1**. Debian marks all three named Expat findings (CVE-2026-76957, CVE-2026-77214, CVE-2026-93990) fixed at 2.9.0-1~deb13u1. A candidate should explicitly upgrade already-installed packages as well as installing runtime dependencies; `apt-get update` alone installs nothing. The same snapshot offers `libssl3t64` 3.5.7-1~deb13u3; the full resolved OS graph needs a new inventory and source review.

**FreeType is different:** the security snapshot still has `libfreetype6` **2.13.3+dfsg-1+deb13u1**, and Debian's Trixie tracker still marks CVE-2026-95512 open (`no-dsa`, minor issue; next point update noted). A new snapshot does not establish its remediation. Do not import unstable packages or silently change distributions. Investigate the CID font-loader trigger against the qualified TrueType-only input boundary and actual third-party call paths, or wait for a maintained compatible fix; record evidence before claiming non-applicability.

Both pinned Temurin runtimes also contain `/opt/java/{openjdk,server}/lib/libfreetype.so`. A read-only, networkless inspection of the locally retained native alpha.1 image found its font-manager dependencies resolve those bundled copies, whose retained legal notices identify FreeType 2.14.3. OS APT updates do not replace them. Version notices alone do not establish whether an upstream patch was backported. The source font gate rejects non-standalone-TrueType inputs, but this preliminary source check is not a complete reachability proof. No JVM/native finding is disposed of by this review.

Primary evidence: [Expat 76957](https://security-tracker.debian.org/tracker/CVE-2026-76957), [77214](https://security-tracker.debian.org/tracker/CVE-2026-77214), [93990](https://security-tracker.debian.org/tracker/CVE-2026-93990), [FreeType](https://security-tracker.debian.org/tracker/CVE-2026-95512), [AMD64 snapshot package index](https://snapshot.debian.org/archive/debian-security/20261030T000000Z/dists/trixie-security/main/binary-amd64/Packages.xz), [ARM64 index](https://snapshot.debian.org/archive/debian-security/20261030T000000Z/dists/trixie-security/main/binary-arm64/Packages.xz).

## Official container inputs

Registry indexes and both native architecture manifests/configs were read anonymously. The rows below are observations, not selected source pins.

| Input | Source before this change | Available observation | Disposition |
| --- | --- | --- | --- |
| Debian `trixie-slim` | `sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f` | `sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4` (2026-10-27 build; AMD64/ARM64) | Candidate with newer pinned package snapshot and explicit package upgrades. |
| Temurin 25 JRE Noble | 25.0.4.1+1, index `sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab` | **25.0.5+8**, index `sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec` (both architectures) | Prefer this patch after provenance/license/native qualification; old 25 sources do not qualify a new runtime. |
| Temurin 17 JRE Noble | 17.0.20.1+1, index `sha256:900322f4cc1b9da9d730ae4bb56d8a1dabbaa9cfa32809fc8b542b600370a045` | Moving index `sha256:b3e1d0486f09293b2455aed02d396d4b6b5ea9b0e8c7c065c297cfef453b0116` still contains **17.0.20.1+1** on both architectures | Changing only this digest does not update the Java patch. Official standalone **17.0.21+9** JRE binaries/signatures exist for both architectures; using those directly would be a separately reviewed packaging choice. |
| Dockerfile frontend | `sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e` | Same index | Keep. |

The Temurin parent Ubuntu OS packages are not the application's Debian package baseline: the Dockerfile copies only the JRE trees into Debian. Consult [Temurin 17 release](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.21%2B9), [25 release](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5%2B8), and the official [container recipes](https://github.com/adoptium/containers). The Adoptium binary API returned HTTP 403 locally; GitHub's official release API and its native-architecture asset names supplied the fallback evidence. No JVM binary was substituted or downloaded for execution.

## JVM and build update candidates

The inventory includes all eight module lockfiles, compiler/private/ABI-tool graphs, the catalog's three external Gradle plugins and independent Maven-example plugin pins. It does not classify historical experiments or workstation utilities as shipped dependencies. All 192 unique group/artifact metadata requests completed after retrying three transient failures. Alternate Byte Buddy old-JDK builds and prereleases were excluded. ANTLR metadata contains historical date-like versions and omits selected 2.7.7: its apparent numeric maximum is not a valid update recommendation.

| Family | Selected | Available | Review before adoption |
| --- | --- | --- | --- |
| docx4j core/generated/copy/JAXB facade | 17.3.2 | 17.3.4 | Upgrade coherently; qualify serializer, fonts, layout and all golden cases. A newer development branch name is not Maven publication evidence. |
| Ktor | 3.6.0 | 3.6.1 | Actual CIO/HTTP tests, malformed-path/error behavior and cleanup. |
| Kotlinx Serialization | 1.11.0 | 1.12.0 | Release uses Kotlin 2.4.10, within the existing 2.4.21 build line; verify JSON exception sanitization and nesting boundaries rather than exposing new exception excerpts. |
| Jackson test BOM/family | 2.22.3 | 2.22.4 | Keep test-only alignment. |
| Byte Buddy / agent | 1.18.14 | 1.18.16 | Keep MockK/test-only; do not select `-jdk5` variants. |
| Error Prone annotations | 2.50.0 | 2.51.0 | Annotations only, no compiler processor. |
| XML Graphics Commons | 2.11 | 2.12 | Review after the docx4j graph resolves, including notices/transitives. |
| JaCoCo / ASM | 0.8.15 / 9.10.1 | 0.8.16 / 9.11.1 | Tooling-only; coverage and no runtime leakage. |
| Maven Surefire examples | 3.6.0 | 3.6.1 | Standalone Java 17 example execution. |
| Saxon HE (validator) | 12.8 | 12.10 in same major; 13.1 overall | Vendor validator integration/licensing and actual conformance validation first; do not force latest major. |
| JAXB impl (validator) | 2.3.2 | 2.3.9 in same namespace line; 4.0.9 overall | Preserve the validator's `javax` boundary; no blind Jakarta migration. |
| Rhino (docx4j transitive) | 1.7.15.1 | 1.9.2 | Upstream/API/license/Java baseline review and no newly exposed script execution. |
| Swagger test family | 2.2.55 | 3.0.0 overall | No drop-in-major claim; review parser 2.1.48 compatibility separately. |

Kotlin 2.4.21, PDFBox 3.0.8, veraPDF 1.30.3, JUnit 6.1.3, MockK 1.14.11, ArchUnit 1.5.1, image-comparison 5.0.0 and the selected Sonar/CycloneDX plugin releases matched the checked stable repository metadata. Do not upgrade compiler-private reflection/coroutine graphs or Kotlin ABI compatibility 2.4.0 merely because their isolated artifacts differ from application versions. QDox 1.x, StringTemplate 3.x and older Jakarta/`javax` namespaces require upstream/API integration, not forced major substitutions.

Gradle **9.8.2** is available, but [Kotlin 2.4.21's fully supported range](https://kotlinlang.org/docs/gradle-configure-project.html) ends at **9.7.0**. Retain the deliberate wrapper exception until that compatibility boundary changes or a reviewed supported alternative is approved. `actions/setup-java` has **6.1.0** versus pinned **6.0.1**; this is a CI-workflow change requiring review. Checkout 7.0.1 and upload-artifact 7.0.2 match their checked latest releases. Keep full commit pins rather than floating action tags.

Preliminary candidate POM declarations for docx4j, Ktor, Serialization, Jackson, Error Prone and XML Graphics remain Apache-2.0. Byte Buddy and JaCoCo inherit their declarations from parents; their existing Apache-2.0/EPL-2.0 classifications still need the exact new artifacts' original LICENSE/NOTICE/source review. This declaration check is not a complete license/transitive review or adoption approval. Native/JVM notices and corresponding-source requirements remain separate and mandatory.

Primary notes: [Ktor 3.6.1](https://github.com/ktorio/ktor/releases/tag/3.6.1), [Serialization 1.12](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.12.0), [Byte Buddy](https://github.com/raphw/byte-buddy/releases/tag/byte-buddy-1.18.16), [JaCoCo](https://www.jacoco.org/jacoco/trunk/doc/changes.html), [Gradle current API](https://services.gradle.org/versions/current), [setup-java](https://github.com/actions/setup-java/releases/tag/v6.1.0).

## Security inventory and limitations

The dated public-coordinate OSV batch query covered **203 selected Maven package/version coordinates from module locks and independent Maven-example tooling** and **61 Debian source package/version coordinates** from the retained native baseline. No Maven-coordinate matches were returned. Debian matching returned **84 distinct advisory IDs across 22 source packages**. These are source-package coordinate matches, not 84 demonstrated exploitable NuaForma vulnerabilities and not a zero-vulnerability clearance for the remaining graph.

The retained native AMD64/ARM64 inventories have identical source package/version sets. The baseline comes from the qualified alpha.1 native runtime whose OS/JVM inputs were retained for alpha.4; this audit does not claim to have freshly extracted the published alpha.4 image or qualified a new candidate. The earlier release-47 advisory inventory contains 171 package/advisory pairs: 92 refer to source packages absent from this retained native baseline, while 79 still name an installed source package. Absence of a source package is not a disposition of a separately bundled library. The remaining findings need individual version/operation evidence, including new matches and both JRE-native components. No old evaluation acceptance is carried forward automatically.

Source packages with OSV matches in this baseline follow. Counts may overlap advisory IDs across source packages. Updating and scanning a new exact candidate is necessary before claiming a count reduction.

| Source package | Matching advisory count |
| --- | --- |
| acl | 2 |
| apt | 1 |
| attr | 1 |
| brotli | 1 |
| bzip2 | 1 |
| coreutils | 4 |
| dash | 2 |
| diffutils | 1 |
| expat | 11 |
| freetype | 1 |
| gcc-14 | 2 |
| glibc | 21 |
| libpng1.6 | 2 |
| ncurses | 2 |
| pam | 2 |
| perl | 4 |
| shadow | 2 |
| sqlite3 | 5 |
| systemd | 7 |
| tar | 4 |
| util-linux | 6 |
| zlib | 2 |

## Agreed implementation and qualification sequence

1. Obtain maintainer approval for the bounded compatible-update set; this was explicitly granted before implementation, as required by #51. Keep Java 17/25 baselines and existing output semantics. No commit/push/PR/release authority is inferred from this audit.
2. Update the Debian digest and authenticated snapshot together, explicitly upgrade installed packages, and select the official Java 25 patch. Decide the Java 17 packaging prerequisite explicitly instead of pretending its moving tag is patched.
3. Resolve the coherent docx4j/Ktor/library and build/test updates in small reviewable groups. Review new transitive versions, original licenses/notices, Java-17-effective bytecode, lock diffs and fresh official SHA-256 receipts; retain strict verification.
4. Map every retained/current native finding, including JVM-bundled components, to the exact candidate and reachable operations. Prove any non-applicability; keep FreeType and other unresolved findings open until evidence exists.
5. Test Java 17 embedded/independent consumers, Java 25 service/Playground and HTTP contracts; check unchanged PDF goldens, fonts, all archival profiles, watermarks and decoded rotated barcodes. Do not replace goldens to hide drift. Run native AMD64/ARM64 qualification and actual Sonar on delivery heads under the existing workflow.
6. Recollect exact source/license/package/JVM inventories for changed inputs before any binary distribution. Publication and the matching website update remain separately authorized workflows.

The initial inventory phase was read-only: registry/repository/API reads, static source inspection, OSV/vendor/snapshot checks and one read-only networkless run of an already retained local image. Implementation and qualification after maintainer approval are recorded below. No tests, security gates or source-verification controls were relaxed.

## Full artifact availability inventory

Each row links primary repository metadata. Versions found in isolated compiler/ABI/validator configurations are listed rather than silently aligned. Availability is not a safe-upgrade recommendation. Full resolved graphs after upgrades require another check.

| Artifact | Selected version(s) | Checked stable release | Same-major observations |
| --- | --- | --- | --- |
| [`antlr:antlr`](https://repo.maven.apache.org/maven2/antlr/antlr/maven-metadata.xml) | 2.7.7 | 2.7.7 |  |
| [`com.fasterxml.jackson:jackson-bom`](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/jackson-bom/maven-metadata.xml) | 2.22.3 | 2.22.4 | 2.22.3 → 2.22.4 |
| [`com.fasterxml.jackson.core:jackson-annotations`](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/maven-metadata.xml) | 2.22 | 2.22 |  |
| [`com.fasterxml.jackson.core:jackson-core`](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/maven-metadata.xml) | 2.22.3 | 2.22.4 | 2.22.3 → 2.22.4 |
| [`com.fasterxml.jackson.core:jackson-databind`](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/maven-metadata.xml) | 2.22.3 | 2.22.4 | 2.22.3 → 2.22.4 |
| [`com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/dataformat/jackson-dataformat-yaml/maven-metadata.xml) | 2.22.3 | 2.22.4 | 2.22.3 → 2.22.4 |
| [`com.fasterxml.jackson.datatype:jackson-datatype-jsr310`](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/maven-metadata.xml) | 2.22.3 | 2.22.4 | 2.22.3 → 2.22.4 |
| [`com.google.errorprone:error_prone_annotations`](https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/maven-metadata.xml) | 2.50.0 | 2.51.0 | 2.50.0 → 2.51.0 |
| [`com.sun.istack:istack-commons-runtime`](https://repo.maven.apache.org/maven2/com/sun/istack/istack-commons-runtime/maven-metadata.xml) | 4.2.0 | 4.2.0 |  |
| [`com.sun.xml.bind:jaxb-core`](https://repo.maven.apache.org/maven2/com/sun/xml/bind/jaxb-core/maven-metadata.xml) | 2.3.0.1 | 4.0.9 |  |
| [`com.sun.xml.bind:jaxb-impl`](https://repo.maven.apache.org/maven2/com/sun/xml/bind/jaxb-impl/maven-metadata.xml) | 2.3.2 | 4.0.9 | 2.3.2 → 2.3.9 |
| [`com.thoughtworks.qdox:qdox`](https://repo.maven.apache.org/maven2/com/thoughtworks/qdox/qdox/maven-metadata.xml) | 1.12.1 | 2.2.0 |  |
| [`com.tngtech.archunit:archunit`](https://repo.maven.apache.org/maven2/com/tngtech/archunit/archunit/maven-metadata.xml) | 1.5.1 | 1.5.1 |  |
| [`com.typesafe:config`](https://repo.maven.apache.org/maven2/com/typesafe/config/maven-metadata.xml) | 1.4.9 | 1.4.9 |  |
| [`com.zaxxer:SparseBitSet`](https://repo.maven.apache.org/maven2/com/zaxxer/SparseBitSet/maven-metadata.xml) | 1.3 | 1.3 |  |
| [`commons-codec:commons-codec`](https://repo.maven.apache.org/maven2/commons-codec/commons-codec/maven-metadata.xml) | 1.23.0 | 1.23.0 |  |
| [`commons-io:commons-io`](https://repo.maven.apache.org/maven2/commons-io/commons-io/maven-metadata.xml) | 2.22.0 | 2.22.0 |  |
| [`commons-logging:commons-logging`](https://repo.maven.apache.org/maven2/commons-logging/commons-logging/maven-metadata.xml) | 1.4.0 | 1.4.0 |  |
| [`dev.nuaforma:image-comparison`](https://repo.maven.apache.org/maven2/dev/nuaforma/image-comparison/maven-metadata.xml) | 5.0.0 | 5.0.0 |  |
| [`dev.nuaforma:image-comparison-kotlin`](https://repo.maven.apache.org/maven2/dev/nuaforma/image-comparison-kotlin/maven-metadata.xml) | 5.0.0 | 5.0.0 |  |
| [`io.ktor:ktor-client-apache5`](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-apache5/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-client-apache5-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-apache5-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-client-cio`](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-cio/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-client-cio-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-cio-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-client-core`](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-core/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-client-core-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-core-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-events`](https://repo.maven.apache.org/maven2/io/ktor/ktor-events/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-events-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-events-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-http`](https://repo.maven.apache.org/maven2/io/ktor/ktor-http/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-http-cio`](https://repo.maven.apache.org/maven2/io/ktor/ktor-http-cio/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-http-cio-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-http-cio-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-http-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-http-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-io`](https://repo.maven.apache.org/maven2/io/ktor/ktor-io/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-io-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-io-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-network`](https://repo.maven.apache.org/maven2/io/ktor/ktor-network/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-network-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-network-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-network-tls`](https://repo.maven.apache.org/maven2/io/ktor/ktor-network-tls/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-network-tls-certificates`](https://repo.maven.apache.org/maven2/io/ktor/ktor-network-tls-certificates/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-network-tls-certificates-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-network-tls-certificates-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-network-tls-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-network-tls-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-serialization`](https://repo.maven.apache.org/maven2/io/ktor/ktor-serialization/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-serialization-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-serialization-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-server-body-limit-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-body-limit-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-server-call-logging`](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-call-logging/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-server-call-logging-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-call-logging-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-server-cio-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-cio-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-server-core`](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-core/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-server-core-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-core-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-server-status-pages-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-status-pages-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-server-test-host-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-test-host-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-server-websockets`](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-websockets/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-server-websockets-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-websockets-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-sse`](https://repo.maven.apache.org/maven2/io/ktor/ktor-sse/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-sse-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-sse-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-test-dispatcher`](https://repo.maven.apache.org/maven2/io/ktor/ktor-test-dispatcher/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-test-dispatcher-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-test-dispatcher-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-utils`](https://repo.maven.apache.org/maven2/io/ktor/ktor-utils/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-utils-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-utils-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-websocket-serialization`](https://repo.maven.apache.org/maven2/io/ktor/ktor-websocket-serialization/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-websocket-serialization-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-websocket-serialization-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-websockets`](https://repo.maven.apache.org/maven2/io/ktor/ktor-websockets/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.ktor:ktor-websockets-jvm`](https://repo.maven.apache.org/maven2/io/ktor/ktor-websockets-jvm/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`io.mockk:mockk`](https://repo.maven.apache.org/maven2/io/mockk/mockk/maven-metadata.xml) | 1.14.11 | 1.14.11 |  |
| [`io.mockk:mockk-agent`](https://repo.maven.apache.org/maven2/io/mockk/mockk-agent/maven-metadata.xml) | 1.14.11 | 1.14.11 |  |
| [`io.mockk:mockk-agent-api`](https://repo.maven.apache.org/maven2/io/mockk/mockk-agent-api/maven-metadata.xml) | 1.14.11 | 1.14.11 |  |
| [`io.mockk:mockk-agent-api-jvm`](https://repo.maven.apache.org/maven2/io/mockk/mockk-agent-api-jvm/maven-metadata.xml) | 1.14.11 | 1.14.11 |  |
| [`io.mockk:mockk-agent-jvm`](https://repo.maven.apache.org/maven2/io/mockk/mockk-agent-jvm/maven-metadata.xml) | 1.14.11 | 1.14.11 |  |
| [`io.mockk:mockk-core`](https://repo.maven.apache.org/maven2/io/mockk/mockk-core/maven-metadata.xml) | 1.14.11 | 1.14.11 |  |
| [`io.mockk:mockk-core-jvm`](https://repo.maven.apache.org/maven2/io/mockk/mockk-core-jvm/maven-metadata.xml) | 1.14.11 | 1.14.11 |  |
| [`io.mockk:mockk-dsl`](https://repo.maven.apache.org/maven2/io/mockk/mockk-dsl/maven-metadata.xml) | 1.14.11 | 1.14.11 |  |
| [`io.mockk:mockk-dsl-jvm`](https://repo.maven.apache.org/maven2/io/mockk/mockk-dsl-jvm/maven-metadata.xml) | 1.14.11 | 1.14.11 |  |
| [`io.mockk:mockk-jvm`](https://repo.maven.apache.org/maven2/io/mockk/mockk-jvm/maven-metadata.xml) | 1.14.11 | 1.14.11 |  |
| [`io.swagger.core.v3:swagger-annotations`](https://repo.maven.apache.org/maven2/io/swagger/core/v3/swagger-annotations/maven-metadata.xml) | 2.2.55 | 3.0.0 |  |
| [`io.swagger.core.v3:swagger-core`](https://repo.maven.apache.org/maven2/io/swagger/core/v3/swagger-core/maven-metadata.xml) | 2.2.55 | 3.0.0 |  |
| [`io.swagger.core.v3:swagger-models`](https://repo.maven.apache.org/maven2/io/swagger/core/v3/swagger-models/maven-metadata.xml) | 2.2.55 | 3.0.0 |  |
| [`io.swagger.parser.v3:swagger-parser-core`](https://repo.maven.apache.org/maven2/io/swagger/parser/v3/swagger-parser-core/maven-metadata.xml) | 2.1.48 | 2.1.48 |  |
| [`io.swagger.parser.v3:swagger-parser-safe-url-resolver`](https://repo.maven.apache.org/maven2/io/swagger/parser/v3/swagger-parser-safe-url-resolver/maven-metadata.xml) | 2.1.48 | 2.1.48 |  |
| [`io.swagger.parser.v3:swagger-parser-v3`](https://repo.maven.apache.org/maven2/io/swagger/parser/v3/swagger-parser-v3/maven-metadata.xml) | 2.1.48 | 2.1.48 |  |
| [`jakarta.activation:jakarta.activation-api`](https://repo.maven.apache.org/maven2/jakarta/activation/jakarta.activation-api/maven-metadata.xml) | 1.2.2, 2.1.4 | 2.1.4 |  |
| [`jakarta.validation:jakarta.validation-api`](https://repo.maven.apache.org/maven2/jakarta/validation/jakarta.validation-api/maven-metadata.xml) | 2.0.2 | 3.1.1 |  |
| [`jakarta.xml.bind:jakarta.xml.bind-api`](https://repo.maven.apache.org/maven2/jakarta/xml/bind/jakarta.xml.bind-api/maven-metadata.xml) | 2.3.3, 4.0.5 | 4.0.5 |  |
| [`javax.activation:javax.activation-api`](https://repo.maven.apache.org/maven2/javax/activation/javax.activation-api/maven-metadata.xml) | 1.2.0 | 1.2.0 |  |
| [`javax.xml.bind:jaxb-api`](https://repo.maven.apache.org/maven2/javax/xml/bind/jaxb-api/maven-metadata.xml) | 2.3.1 | 2.3.1 |  |
| [`net.bytebuddy:byte-buddy`](https://repo.maven.apache.org/maven2/net/bytebuddy/byte-buddy/maven-metadata.xml) | 1.18.14 | 1.18.16 | 1.18.14 → 1.18.16 |
| [`net.bytebuddy:byte-buddy-agent`](https://repo.maven.apache.org/maven2/net/bytebuddy/byte-buddy-agent/maven-metadata.xml) | 1.18.14 | 1.18.16 | 1.18.14 → 1.18.16 |
| [`net.engio:mbassador`](https://repo.maven.apache.org/maven2/net/engio/mbassador/maven-metadata.xml) | 1.3.2 | 1.3.2 |  |
| [`net.java.dev.jna:jna`](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/maven-metadata.xml) | 5.19.1 | 5.19.1 |  |
| [`net.java.dev.jna:jna-platform`](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna-platform/maven-metadata.xml) | 5.19.1 | 5.19.1 |  |
| [`net.sf.saxon:Saxon-HE`](https://repo.maven.apache.org/maven2/net/sf/saxon/Saxon-HE/maven-metadata.xml) | 12.8 | 13.1 | 12.8 → 12.10 |
| [`org.antlr:antlr-runtime`](https://repo.maven.apache.org/maven2/org/antlr/antlr-runtime/maven-metadata.xml) | 3.5.3 | 3.5.3 |  |
| [`org.antlr:stringtemplate`](https://repo.maven.apache.org/maven2/org/antlr/stringtemplate/maven-metadata.xml) | 3.2.1 | 4.0.2 |  |
| [`org.apache.commons:commons-collections4`](https://repo.maven.apache.org/maven2/org/apache/commons/commons-collections4/maven-metadata.xml) | 4.6.0 | 4.6.0 |  |
| [`org.apache.commons:commons-compress`](https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/maven-metadata.xml) | 1.28.0 | 1.28.0 |  |
| [`org.apache.commons:commons-lang3`](https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/maven-metadata.xml) | 3.21.0 | 3.21.0 |  |
| [`org.apache.httpcomponents.client5:httpclient5`](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/client5/httpclient5/maven-metadata.xml) | 5.6.5 | 5.6.5 |  |
| [`org.apache.httpcomponents.core5:httpcore5`](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/core5/httpcore5/maven-metadata.xml) | 5.4.4 | 5.4.4 |  |
| [`org.apache.httpcomponents.core5:httpcore5-h2`](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/core5/httpcore5-h2/maven-metadata.xml) | 5.4.4 | 5.4.4 |  |
| [`org.apache.logging.log4j:log4j-api`](https://repo.maven.apache.org/maven2/org/apache/logging/log4j/log4j-api/maven-metadata.xml) | 2.26.1 | 2.26.1 |  |
| [`org.apache.logging.log4j:log4j-core`](https://repo.maven.apache.org/maven2/org/apache/logging/log4j/log4j-core/maven-metadata.xml) | 2.26.1 | 2.26.1 |  |
| [`org.apache.logging.log4j:log4j-slf4j2-impl`](https://repo.maven.apache.org/maven2/org/apache/logging/log4j/log4j-slf4j2-impl/maven-metadata.xml) | 2.26.1 | 2.26.1 |  |
| [`org.apache.maven.plugins:maven-compiler-plugin`](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml) | 3.16.0 | 3.16.0 |  |
| [`org.apache.maven.plugins:maven-dependency-plugin`](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-dependency-plugin/maven-metadata.xml) | 3.11.0 | 3.11.0 |  |
| [`org.apache.maven.plugins:maven-install-plugin`](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-install-plugin/maven-metadata.xml) | 3.2.0 | 3.2.0 |  |
| [`org.apache.maven.plugins:maven-jar-plugin`](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-jar-plugin/maven-metadata.xml) | 3.5.1 | 3.5.1 |  |
| [`org.apache.maven.plugins:maven-resources-plugin`](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-resources-plugin/maven-metadata.xml) | 3.5.0 | 3.5.0 |  |
| [`org.apache.maven.plugins:maven-surefire-plugin`](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-surefire-plugin/maven-metadata.xml) | 3.6.0 | 3.6.1 | 3.6.0 → 3.6.1 |
| [`org.apache.pdfbox:fontbox`](https://repo.maven.apache.org/maven2/org/apache/pdfbox/fontbox/maven-metadata.xml) | 3.0.8 | 3.0.8 |  |
| [`org.apache.pdfbox:pdfbox`](https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox/maven-metadata.xml) | 3.0.8 | 3.0.8 |  |
| [`org.apache.pdfbox:pdfbox-io`](https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox-io/maven-metadata.xml) | 3.0.8 | 3.0.8 |  |
| [`org.apache.xmlgraphics:xmlgraphics-commons`](https://repo.maven.apache.org/maven2/org/apache/xmlgraphics/xmlgraphics-commons/maven-metadata.xml) | 2.11 | 2.12 | 2.11 → 2.12 |
| [`org.apiguardian:apiguardian-api`](https://repo.maven.apache.org/maven2/org/apiguardian/apiguardian-api/maven-metadata.xml) | 1.1.2 | 1.1.2 |  |
| [`org.checkerframework:checker-qual`](https://repo.maven.apache.org/maven2/org/checkerframework/checker-qual/maven-metadata.xml) | 4.3.1 | 4.3.1 |  |
| [`org.cyclonedx.bom:org.cyclonedx.bom.gradle.plugin`](https://plugins.gradle.org/m2/org/cyclonedx/bom/org.cyclonedx.bom.gradle.plugin/maven-metadata.xml) | 3.5.1 | 3.5.1 |  |
| [`org.docx4j:docx4j-JAXB-ReferenceImpl`](https://repo.maven.apache.org/maven2/org/docx4j/docx4j-JAXB-ReferenceImpl/maven-metadata.xml) | 17.3.2 | 17.3.4 | 17.3.2 → 17.3.4 |
| [`org.docx4j:docx4j-copy`](https://repo.maven.apache.org/maven2/org/docx4j/docx4j-copy/maven-metadata.xml) | 17.3.2 | 17.3.4 | 17.3.2 → 17.3.4 |
| [`org.docx4j:docx4j-core`](https://repo.maven.apache.org/maven2/org/docx4j/docx4j-core/maven-metadata.xml) | 17.3.2 | 17.3.4 | 17.3.2 → 17.3.4 |
| [`org.docx4j:docx4j-generated-objects`](https://repo.maven.apache.org/maven2/org/docx4j/docx4j-generated-objects/maven-metadata.xml) | 17.3.2 | 17.3.4 | 17.3.2 → 17.3.4 |
| [`org.docx4j.org.apache:xalan-interpretive`](https://repo.maven.apache.org/maven2/org/docx4j/org/apache/xalan-interpretive/maven-metadata.xml) | 11.0.0 | 11.0.0 |  |
| [`org.docx4j.org.apache:xalan-serializer`](https://repo.maven.apache.org/maven2/org/docx4j/org/apache/xalan-serializer/maven-metadata.xml) | 11.0.0 | 11.0.0 |  |
| [`org.eclipse.angus:angus-activation`](https://repo.maven.apache.org/maven2/org/eclipse/angus/angus-activation/maven-metadata.xml) | 2.0.3 | 2.0.3 |  |
| [`org.fusesource.jansi:jansi`](https://repo.maven.apache.org/maven2/org/fusesource/jansi/jansi/maven-metadata.xml) | 2.4.3 | 2.4.3 |  |
| [`org.glassfish.jaxb:jaxb-core`](https://repo.maven.apache.org/maven2/org/glassfish/jaxb/jaxb-core/maven-metadata.xml) | 4.0.9 | 4.0.9 |  |
| [`org.glassfish.jaxb:jaxb-runtime`](https://repo.maven.apache.org/maven2/org/glassfish/jaxb/jaxb-runtime/maven-metadata.xml) | 4.0.9 | 4.0.9 |  |
| [`org.glassfish.jaxb:txw2`](https://repo.maven.apache.org/maven2/org/glassfish/jaxb/txw2/maven-metadata.xml) | 4.0.9 | 4.0.9 |  |
| [`org.jacoco:org.jacoco.agent`](https://repo.maven.apache.org/maven2/org/jacoco/org.jacoco.agent/maven-metadata.xml) | 0.8.15 | 0.8.16 | 0.8.15 → 0.8.16 |
| [`org.jacoco:org.jacoco.ant`](https://repo.maven.apache.org/maven2/org/jacoco/org.jacoco.ant/maven-metadata.xml) | 0.8.15 | 0.8.16 | 0.8.15 → 0.8.16 |
| [`org.jacoco:org.jacoco.core`](https://repo.maven.apache.org/maven2/org/jacoco/org.jacoco.core/maven-metadata.xml) | 0.8.15 | 0.8.16 | 0.8.15 → 0.8.16 |
| [`org.jacoco:org.jacoco.report`](https://repo.maven.apache.org/maven2/org/jacoco/org.jacoco.report/maven-metadata.xml) | 0.8.15 | 0.8.16 | 0.8.15 → 0.8.16 |
| [`org.jetbrains:annotations`](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/maven-metadata.xml) | 13.0, 26.1.0 | 26.1.0 |  |
| [`org.jetbrains.kotlin:kotlin-bom`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-bom/maven-metadata.xml) | 2.4.21 | 2.4.21 |  |
| [`org.jetbrains.kotlin:kotlin-build-tools-api`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-build-tools-api/maven-metadata.xml) | 2.4.0, 2.4.21 | 2.4.21 | 2.4.0 → 2.4.21 |
| [`org.jetbrains.kotlin:kotlin-build-tools-compat`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-build-tools-compat/maven-metadata.xml) | 2.4.21 | 2.4.21 |  |
| [`org.jetbrains.kotlin:kotlin-build-tools-cri-impl`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-build-tools-cri-impl/maven-metadata.xml) | 2.4.0, 2.4.21 | 2.4.21 | 2.4.0 → 2.4.21 |
| [`org.jetbrains.kotlin:kotlin-build-tools-impl`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-build-tools-impl/maven-metadata.xml) | 2.4.0, 2.4.21 | 2.4.21 | 2.4.0 → 2.4.21 |
| [`org.jetbrains.kotlin:kotlin-compiler-embeddable`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/maven-metadata.xml) | 2.4.0, 2.4.21 | 2.4.21 | 2.4.0 → 2.4.21 |
| [`org.jetbrains.kotlin:kotlin-compiler-runner`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-runner/maven-metadata.xml) | 2.4.0, 2.4.21 | 2.4.21 | 2.4.0 → 2.4.21 |
| [`org.jetbrains.kotlin:kotlin-daemon-client`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-daemon-client/maven-metadata.xml) | 2.4.0, 2.4.21 | 2.4.21 | 2.4.0 → 2.4.21 |
| [`org.jetbrains.kotlin:kotlin-daemon-embeddable`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-daemon-embeddable/maven-metadata.xml) | 2.4.0, 2.4.21 | 2.4.21 | 2.4.0 → 2.4.21 |
| [`org.jetbrains.kotlin:kotlin-klib-commonizer-embeddable`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-klib-commonizer-embeddable/maven-metadata.xml) | 2.4.21 | 2.4.21 |  |
| [`org.jetbrains.kotlin:kotlin-reflect`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-reflect/maven-metadata.xml) | 1.6.10, 2.4.21 | 2.4.21 | 1.6.10 → 1.9.25 |
| [`org.jetbrains.kotlin:kotlin-script-runtime`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-script-runtime/maven-metadata.xml) | 2.4.0, 2.4.21 | 2.4.21 | 2.4.0 → 2.4.21 |
| [`org.jetbrains.kotlin:kotlin-scripting-common`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-scripting-common/maven-metadata.xml) | 2.4.21 | 2.4.21 |  |
| [`org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-scripting-compiler-embeddable/maven-metadata.xml) | 2.4.21 | 2.4.21 |  |
| [`org.jetbrains.kotlin:kotlin-scripting-compiler-impl-embeddable`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-scripting-compiler-impl-embeddable/maven-metadata.xml) | 2.4.21 | 2.4.21 |  |
| [`org.jetbrains.kotlin:kotlin-scripting-jvm`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-scripting-jvm/maven-metadata.xml) | 2.4.21 | 2.4.21 |  |
| [`org.jetbrains.kotlin:kotlin-stdlib`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/maven-metadata.xml) | 2.4.0, 2.4.21 | 2.4.21 | 2.4.0 → 2.4.21 |
| [`org.jetbrains.kotlin:kotlin-tooling-core`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-tooling-core/maven-metadata.xml) | 2.4.21 | 2.4.21 |  |
| [`org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/jvm/org.jetbrains.kotlin.jvm.gradle.plugin/maven-metadata.xml) | 2.4.21 | 2.4.21 |  |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-bom`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-bom/maven-metadata.xml) | 1.11.0 | 1.11.0 |  |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-core`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml) | 1.11.0 | 1.11.0 |  |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/maven-metadata.xml) | 1.11.0, 1.8.0 | 1.11.0 | 1.8.0 → 1.11.0 |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-debug`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-debug/maven-metadata.xml) | 1.11.0 | 1.11.0 |  |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-slf4j`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-slf4j/maven-metadata.xml) | 1.11.0 | 1.11.0 |  |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-test`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-test/maven-metadata.xml) | 1.11.0 | 1.11.0 |  |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-test-jvm/maven-metadata.xml) | 1.11.0 | 1.11.0 |  |
| [`org.jetbrains.kotlinx:kotlinx-io-bytestring`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-io-bytestring/maven-metadata.xml) | 0.9.1 | 0.9.1 |  |
| [`org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-io-bytestring-jvm/maven-metadata.xml) | 0.9.1 | 0.9.1 |  |
| [`org.jetbrains.kotlinx:kotlinx-io-core`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-io-core/maven-metadata.xml) | 0.9.1 | 0.9.1 |  |
| [`org.jetbrains.kotlinx:kotlinx-io-core-jvm`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-io-core-jvm/maven-metadata.xml) | 0.9.1 | 0.9.1 |  |
| [`org.jetbrains.kotlinx:kotlinx-serialization-bom`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-bom/maven-metadata.xml) | 1.11.0 | 1.12.0 | 1.11.0 → 1.12.0 |
| [`org.jetbrains.kotlinx:kotlinx-serialization-core`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core/maven-metadata.xml) | 1.11.0 | 1.12.0 | 1.11.0 → 1.12.0 |
| [`org.jetbrains.kotlinx:kotlinx-serialization-core-jvm`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/maven-metadata.xml) | 1.11.0 | 1.12.0 | 1.11.0 → 1.12.0 |
| [`org.jetbrains.kotlinx:kotlinx-serialization-json-jvm`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/maven-metadata.xml) | 1.11.0 | 1.12.0 | 1.11.0 → 1.12.0 |
| [`org.jspecify:jspecify`](https://repo.maven.apache.org/maven2/org/jspecify/jspecify/maven-metadata.xml) | 1.0.1 | 1.0.1 |  |
| [`org.junit:junit-bom`](https://repo.maven.apache.org/maven2/org/junit/junit-bom/maven-metadata.xml) | 6.1.3 | 6.1.3 |  |
| [`org.junit.jupiter:junit-jupiter`](https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/maven-metadata.xml) | 6.1.3 | 6.1.3 |  |
| [`org.junit.jupiter:junit-jupiter-api`](https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-api/maven-metadata.xml) | 6.1.3 | 6.1.3 |  |
| [`org.junit.jupiter:junit-jupiter-engine`](https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-engine/maven-metadata.xml) | 6.1.3 | 6.1.3 |  |
| [`org.junit.jupiter:junit-jupiter-params`](https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-params/maven-metadata.xml) | 6.1.3 | 6.1.3 |  |
| [`org.junit.platform:junit-platform-commons`](https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-commons/maven-metadata.xml) | 6.1.3 | 6.1.3 |  |
| [`org.junit.platform:junit-platform-engine`](https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-engine/maven-metadata.xml) | 6.1.3 | 6.1.3 |  |
| [`org.junit.platform:junit-platform-launcher`](https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-launcher/maven-metadata.xml) | 6.1.3 | 6.1.3 |  |
| [`org.jvnet.jaxb:jaxb-plugins-runtime`](https://repo.maven.apache.org/maven2/org/jvnet/jaxb/jaxb-plugins-runtime/maven-metadata.xml) | 4.0.16 | 4.0.16 |  |
| [`org.mozilla:rhino`](https://repo.maven.apache.org/maven2/org/mozilla/rhino/maven-metadata.xml) | 1.7.15.1 | 1.9.2 | 1.7.15.1 → 1.9.2 |
| [`org.objenesis:objenesis`](https://repo.maven.apache.org/maven2/org/objenesis/objenesis/maven-metadata.xml) | 3.6 | 3.6 |  |
| [`org.opentest4j:opentest4j`](https://repo.maven.apache.org/maven2/org/opentest4j/opentest4j/maven-metadata.xml) | 1.3.0 | 1.3.0 |  |
| [`org.ow2.asm:asm`](https://repo.maven.apache.org/maven2/org/ow2/asm/asm/maven-metadata.xml) | 9.10.1 | 9.11.1 | 9.10.1 → 9.11.1 |
| [`org.ow2.asm:asm-commons`](https://repo.maven.apache.org/maven2/org/ow2/asm/asm-commons/maven-metadata.xml) | 9.10.1 | 9.11.1 | 9.10.1 → 9.11.1 |
| [`org.ow2.asm:asm-tree`](https://repo.maven.apache.org/maven2/org/ow2/asm/asm-tree/maven-metadata.xml) | 9.10.1 | 9.11.1 | 9.10.1 → 9.11.1 |
| [`org.plutext:jaxb-svg11`](https://repo.maven.apache.org/maven2/org/plutext/jaxb-svg11/maven-metadata.xml) | 11.4.0 | 11.4.0 |  |
| [`org.slf4j:jcl-over-slf4j`](https://repo.maven.apache.org/maven2/org/slf4j/jcl-over-slf4j/maven-metadata.xml) | 2.0.20 | 2.0.20 |  |
| [`org.slf4j:slf4j-api`](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/maven-metadata.xml) | 2.0.20 | 2.0.20 |  |
| [`org.sonarqube:org.sonarqube.gradle.plugin`](https://plugins.gradle.org/m2/org/sonarqube/org.sonarqube.gradle.plugin/maven-metadata.xml) | 7.5.0.8588 | 7.5.0.8588 |  |
| [`org.verapdf:core`](https://repo.maven.apache.org/maven2/org/verapdf/core/maven-metadata.xml) | 1.30.3 | 1.30.3 |  |
| [`org.verapdf:feature-reporting`](https://repo.maven.apache.org/maven2/org/verapdf/feature-reporting/maven-metadata.xml) | 1.30.3 | 1.30.3 |  |
| [`org.verapdf:metadata-fixer`](https://repo.maven.apache.org/maven2/org/verapdf/metadata-fixer/maven-metadata.xml) | 1.30.3 | 1.30.3 |  |
| [`org.verapdf:parser`](https://repo.maven.apache.org/maven2/org/verapdf/parser/maven-metadata.xml) | 1.30.3 | 1.30.3 |  |
| [`org.verapdf:pdf-model`](https://repo.maven.apache.org/maven2/org/verapdf/pdf-model/maven-metadata.xml) | 1.30.3 | 1.30.3 |  |
| [`org.verapdf:validation-model`](https://repo.maven.apache.org/maven2/org/verapdf/validation-model/maven-metadata.xml) | 1.30.3 | 1.30.3 |  |
| [`org.verapdf:verapdf-xmp-core`](https://repo.maven.apache.org/maven2/org/verapdf/verapdf-xmp-core/maven-metadata.xml) | 1.30.3 | 1.30.3 |  |
| [`org.xmlresolver:xmlresolver`](https://repo.maven.apache.org/maven2/org/xmlresolver/xmlresolver/maven-metadata.xml) | 5.3.3 | 6.1.0 |  |
| [`org.yaml:snakeyaml`](https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/maven-metadata.xml) | 2.7 | 2.7 |  |

## Implementation progress after approval

The approved source changes select docx4j 17.3.4, Ktor 3.6.1, Serialization 1.12.0, Jackson 2.22.4, Byte Buddy 1.18.16, Error Prone 2.51.0, XML Graphics 2.12, Rhino 1.9.2, JaCoCo 0.8.16/ASM 9.11.1 and Maven Surefire 3.6.1. The independent-consumer verification also pins Maven Clean 3.5.0 and starts with clean generated targets so obsolete JARs cannot contaminate a repeated same-version source check; pre-existing local targets were preserved before the first clean. Setup-java is pinned to its reviewed 6.1.0 release commit. Saxon HE 12.10, JAXB implementation 2.3.9 and JAXB API 2.3.3 update the isolated validator graph without moving its `javax` namespace to Jakarta 4.x. JAXB API 2.3.3 still supplies `javax.xml.bind` despite its Jakarta artifact name; its original BSD-style notice is retained. The older standalone JAXB API/core artifacts are excluded because the new implementation includes the core classes. Material preparation now rejects duplicate class entries and wrong API namespaces, and both PDF suites track the actual external validator JARs as test inputs. The new legacy Activation 1.2.2 artifact retains its original EDL/BSD-style material; its combined API/implementation avoids a duplicate standalone Activation API JAR. The DOCX-only XML Graphics declaration preserves the existing SLF4J bridge by excluding its additional Commons Logging backend path.

New graph/artifact and SBOM POM inputs were captured with a fresh Gradle user home. **836 artifact verification receipts** now exactly match Gradle metadata; 163 new receipts were independently downloaded and SHA-256 checked against official repository bytes. No prior reviewed artifact hash was overwritten, and no trust exemption or weaker verification mode was introduced. The source Java 17/25 archives and both official Java 17 native binaries have valid Adoptium signatures under the documented fingerprint. The Java 17 native AMD64 extraction stage builds and reports 17.0.21+9.

The new native AMD64 `runtime-system` layer has been built from the selected Debian digest/snapshot with explicit installed-package upgrades. Its actual inventory confirms Expat 2.9.0-1~deb13u1, glibc 2.41-12+deb13u4, libpng 1.6.48-1+deb13u6 and OpenSSL 3.5.7-1~deb13u3. FreeType remains 2.13.3+dfsg-1+deb13u1.

A fresh OSV query of **199 updated locked Maven coordinates** returned no matches. The new OS layer's **61 source-package coordinates** returned **77 distinct advisory IDs across 21 source packages**, versus 84/22 in the retained baseline. This is a package-coordinate reduction, not proof that every remaining finding is exploitable or irrelevant. The five Expat matches remaining at 2.9.0 are different from the three named fixed Expat findings; current Debian evidence still marks them open. Source inspection of both selected OpenJDK source archives also lacks the published FreeType CID overlap guard. These residual findings must remain explicit under #51, with exact operation/version evidence and release-specific decisions; no zero-vulnerability claim or old acceptance is reused.

Local source qualification is complete: the full build and coverage report pass with **960 JVM tests, zero failures and zero skips** across the seven modules with tests. Playground composition is additionally covered by the actual packaged endpoint checks. The tooling suites pass **53 release, 27 container and 11 quality tests**. Strict receipt validation confirms all 836 reviewed artifacts. Five independent Maven/Gradle consumer records pass on Java 17; all staged executable library JARs are byte-identical after the isolated validator cleanup, so those consumer results still apply.

Both native AMD64 candidate images build. The actual server passes all 15 service-qualification groups, including standard PDF/PDF-A-1b/2b/3b, supported watermarks, mandatory validation, default non-root/read-only/capability/seccomp policies, cancellation, shutdown and private cleanup. Server and Playground both pass the complete shipped-inventory/endpoint/operator-policy qualification, including fonts, concurrency, identical page rasters and downstream profile/digest checks. Their extracted CycloneDX 1.7 SBOMs pass schema validation. The independent Maven PDF/A-3b example also passes inside the new Java 17 container runtime. A separate real-HTTP probe confirms concurrent Java 17 extension requests on the Java 25 server and private cleanup.

The standard combined Java-runtime/mounted-font script confirms the exact Java versions and automatic font embedding/conversion, but **does not pass completely locally**: its deliberately unreadable font is still mode 0555 and readable when the Windows-backed project directory is mounted through Docker Desktop. A read-only non-root probe reproduces that fixture mismatch. A Linux-filesystem probe cannot be mounted because this workstation's WSL Docker integration is unavailable. No product code, assertion, file-access check or security gate was weakened to bypass it. The complete standard test, native ARM64 execution, exact-head CI and actual Sonar remain delivery checks.

Local tooling runs in WSL with a workstation-only Docker Desktop CLI/loopback HTTP transport bridge; application and validator code executes in the actual Linux containers. The bridge changes no qualification assertions or shipped configuration. Existing golden images are unchanged. Original application/validator source and notice materials were staged for the changed graph, and official Java source/binary signatures were verified; a new complete candidate-specific OS/JVM corresponding-source and recipient-access audit is still required before binary distribution. No new website, binary publication or runtime-risk acceptance is implied.


### Updated OS-layer finding inventory

This is a dated source-package match inventory, not a suppression list or an exploitability assessment. Every linked advisory was checked against the current Debian tracker. Except for SQLite CVE-2026-39113 (vendor `fixed_version=0`, not affected), these source-package records remain open or undetermined. Exact final-image call-path qualification is outstanding; a package match does not prove that the affected utility, build option or operation is present or reachable.

| Source package | Installed source version | Matched advisories |
| --- | --- | --- |
| `acl` | `2.3.2-2` | [CVE-2026-54369](https://security-tracker.debian.org/tracker/CVE-2026-54369), [CVE-2026-54370](https://security-tracker.debian.org/tracker/CVE-2026-54370) |
| `apt` | `3.0.3` | [CVE-2011-3374](https://security-tracker.debian.org/tracker/CVE-2011-3374) |
| `attr` | `1:2.5.2-3` | [CVE-2026-54371](https://security-tracker.debian.org/tracker/CVE-2026-54371) |
| `bzip2` | `1.0.8-6` | [CVE-2026-42250](https://security-tracker.debian.org/tracker/CVE-2026-42250) |
| `coreutils` | `9.7-3` | [CVE-2017-18018](https://security-tracker.debian.org/tracker/CVE-2017-18018), [CVE-2025-5278](https://security-tracker.debian.org/tracker/CVE-2025-5278), [CVE-2026-56391](https://security-tracker.debian.org/tracker/CVE-2026-56391), [CVE-2026-56392](https://security-tracker.debian.org/tracker/CVE-2026-56392) |
| `dash` | `0.5.12-12` | [CVE-2026-102473](https://security-tracker.debian.org/tracker/CVE-2026-102473), [CVE-2026-102474](https://security-tracker.debian.org/tracker/CVE-2026-102474) |
| `diffutils` | `1:3.10-4` | [CVE-2026-53910](https://security-tracker.debian.org/tracker/CVE-2026-53910) |
| `expat` | `2.9.0-1~deb13u1` | [CVE-2025-66382](https://security-tracker.debian.org/tracker/CVE-2025-66382), [CVE-2026-113351](https://security-tracker.debian.org/tracker/CVE-2026-113351), [CVE-2026-115506](https://security-tracker.debian.org/tracker/CVE-2026-115506), [CVE-2026-117610](https://security-tracker.debian.org/tracker/CVE-2026-117610), [CVE-2026-118370](https://security-tracker.debian.org/tracker/CVE-2026-118370) |
| `freetype` | `2.13.3+dfsg-1+deb13u1` | [CVE-2026-95512](https://security-tracker.debian.org/tracker/CVE-2026-95512) |
| `gcc-14` | `14.2.0-19` | [CVE-2026-102010](https://security-tracker.debian.org/tracker/CVE-2026-102010), [CVE-2026-95619](https://security-tracker.debian.org/tracker/CVE-2026-95619) |
| `glibc` | `2.41-12+deb13u4` | [CVE-2010-4756](https://security-tracker.debian.org/tracker/CVE-2010-4756), [CVE-2018-20796](https://security-tracker.debian.org/tracker/CVE-2018-20796), [CVE-2019-1010022](https://security-tracker.debian.org/tracker/CVE-2019-1010022), [CVE-2019-1010023](https://security-tracker.debian.org/tracker/CVE-2019-1010023), [CVE-2019-1010024](https://security-tracker.debian.org/tracker/CVE-2019-1010024), [CVE-2019-1010025](https://security-tracker.debian.org/tracker/CVE-2019-1010025), [CVE-2019-9192](https://security-tracker.debian.org/tracker/CVE-2019-9192), [CVE-2026-18374](https://security-tracker.debian.org/tracker/CVE-2026-18374), [CVE-2026-19499](https://security-tracker.debian.org/tracker/CVE-2026-19499), [CVE-2026-19542](https://security-tracker.debian.org/tracker/CVE-2026-19542), [CVE-2026-5435](https://security-tracker.debian.org/tracker/CVE-2026-5435), [CVE-2026-6238](https://security-tracker.debian.org/tracker/CVE-2026-6238), [CVE-2026-6368](https://security-tracker.debian.org/tracker/CVE-2026-6368), [CVE-2026-6791](https://security-tracker.debian.org/tracker/CVE-2026-6791), [CVE-2026-77117](https://security-tracker.debian.org/tracker/CVE-2026-77117), [CVE-2026-80489](https://security-tracker.debian.org/tracker/CVE-2026-80489), [CVE-2026-8674](https://security-tracker.debian.org/tracker/CVE-2026-8674), [CVE-2026-86805](https://security-tracker.debian.org/tracker/CVE-2026-86805), [CVE-2026-89092](https://security-tracker.debian.org/tracker/CVE-2026-89092), [CVE-2026-95818](https://security-tracker.debian.org/tracker/CVE-2026-95818), [CVE-2026-97399](https://security-tracker.debian.org/tracker/CVE-2026-97399) |
| `libpng1.6` | `1.6.48-1+deb13u6` | [CVE-2021-4214](https://security-tracker.debian.org/tracker/CVE-2021-4214), [CVE-2026-3713](https://security-tracker.debian.org/tracker/CVE-2026-3713) |
| `ncurses` | `6.5+20250216-2` | [CVE-2025-6141](https://security-tracker.debian.org/tracker/CVE-2025-6141), [CVE-2025-69720](https://security-tracker.debian.org/tracker/CVE-2025-69720) |
| `pam` | `1.7.0-5` | [CVE-2025-8941](https://security-tracker.debian.org/tracker/CVE-2025-8941), [CVE-2026-54411](https://security-tracker.debian.org/tracker/CVE-2026-54411) |
| `perl` | `5.40.1-6+deb13u1` | [CVE-2011-4116](https://security-tracker.debian.org/tracker/CVE-2011-4116), [CVE-2026-15534](https://security-tracker.debian.org/tracker/CVE-2026-15534), [CVE-2026-82560](https://security-tracker.debian.org/tracker/CVE-2026-82560), [CVE-2026-9538](https://security-tracker.debian.org/tracker/CVE-2026-9538) |
| `shadow` | `1:4.17.4-2` | [CVE-2007-5686](https://security-tracker.debian.org/tracker/CVE-2007-5686), [CVE-2024-56433](https://security-tracker.debian.org/tracker/CVE-2024-56433) |
| `sqlite3` | `3.46.1-7+deb13u2` | [CVE-2021-45346](https://security-tracker.debian.org/tracker/CVE-2021-45346), [CVE-2025-70873](https://security-tracker.debian.org/tracker/CVE-2025-70873), [CVE-2026-39113](https://security-tracker.debian.org/tracker/CVE-2026-39113), [CVE-2026-50812](https://security-tracker.debian.org/tracker/CVE-2026-50812), [CVE-2026-50813](https://security-tracker.debian.org/tracker/CVE-2026-50813) |
| `systemd` | `257.13-1~deb13u1` | [CVE-2013-4392](https://security-tracker.debian.org/tracker/CVE-2013-4392), [CVE-2023-31437](https://security-tracker.debian.org/tracker/CVE-2023-31437), [CVE-2023-31438](https://security-tracker.debian.org/tracker/CVE-2023-31438), [CVE-2023-31439](https://security-tracker.debian.org/tracker/CVE-2023-31439), [CVE-2026-15059](https://security-tracker.debian.org/tracker/CVE-2026-15059), [CVE-2026-16742](https://security-tracker.debian.org/tracker/CVE-2026-16742), [CVE-2026-40228](https://security-tracker.debian.org/tracker/CVE-2026-40228) |
| `tar` | `1.35+dfsg-3.1` | [CVE-2005-2541](https://security-tracker.debian.org/tracker/CVE-2005-2541), [CVE-2026-18477](https://security-tracker.debian.org/tracker/CVE-2026-18477), [CVE-2026-18508](https://security-tracker.debian.org/tracker/CVE-2026-18508), [CVE-2026-5704](https://security-tracker.debian.org/tracker/CVE-2026-5704) |
| `util-linux` | `2.41.5-0+deb13u1` | [CVE-2022-0563](https://security-tracker.debian.org/tracker/CVE-2022-0563), [CVE-2026-3184](https://security-tracker.debian.org/tracker/CVE-2026-3184), [CVE-2026-76642](https://security-tracker.debian.org/tracker/CVE-2026-76642), [CVE-2026-78408](https://security-tracker.debian.org/tracker/CVE-2026-78408), [CVE-2026-78409](https://security-tracker.debian.org/tracker/CVE-2026-78409), [CVE-2026-78410](https://security-tracker.debian.org/tracker/CVE-2026-78410) |
| `zlib` | `1:1.3.dfsg+really1.3.1-1` | [CVE-2026-27171](https://security-tracker.debian.org/tracker/CVE-2026-27171), [CVE-2026-85091](https://security-tracker.debian.org/tracker/CVE-2026-85091) |

A read-only check of the rebuilt OS layer found no `apt-key`, `bzip2recover`, `getfattr`, `setfattr`, `nscd`, `systemd-journald`, `systemd-homed` or `systemd-oomd` executable at its standard path. It did find `diff3`, `infocmp`, Perl, `login`, `nsenter` and the required `prlimit`. These observations narrow named utility/service triggers only; they do not dispose of related shared-library findings, alternate paths, JRE-bundled copies or final-image reachability. No security exception is inferred from the observation.
