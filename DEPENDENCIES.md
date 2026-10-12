# Dependency baseline

This review accompanies the build scaffold for issue #1, dated 2026-10-11. It is not a claim that document processing or PDF conversion is implemented. Direct versions are pinned in `gradle/libs.versions.toml`; the wrapper pins Gradle and its distribution checksum. Per-module lockfiles record resolved dependencies.

| Component | Version | Scope | License and primary reference |
| --- | --- | --- | --- |
| Gradle | 9.7.0 | Build tool and wrapper | [Apache-2.0](https://github.com/gradle/gradle/blob/v9.7.0/LICENSE) |
| Kotlin | 2.4.21 | Compiler and JVM standard library | [Apache-2.0 and bundled third-party notices](https://github.com/JetBrains/kotlin/blob/v2.4.21/license/LICENSE.txt) |
| JUnit Jupiter | 6.1.3 | Tests only | [EPL-2.0](https://github.com/junit-team/junit-framework/blob/r6.1.3/LICENSE.md) |
| MockK | 1.14.11 | Tests only | [Apache-2.0](https://github.com/mockk/mockk/blob/v1.14.11/LICENSE) |
| ArchUnit | 1.5.1 | Tests only | [Apache-2.0 with bundled dependency notices](https://github.com/TNG/ArchUnit/blob/v1.5.1/LICENSE) |
| docx4j core + JAXB ReferenceImpl | 17.1.0 | DOCX implementation/runtime | [Apache-2.0 and bundled notices](https://github.com/plutext/docx4j/blob/VERSION_17_1_0/legals/NOTICE); reference JAXB/Activation use permissive EDL/BSD terms |
| Apache PDFBox | 3.0.8 | Optional PDF module implementation and isolated output-validation JVM only | [Apache-2.0 and bundled third-party terms](https://github.com/apache/pdfbox/blob/3.0.8/LICENSE.txt), [upstream notice](https://github.com/apache/pdfbox/blob/3.0.8/NOTICE.txt) |

## Compatibility and maintenance

Java 17 is both the compilation target and test runtime. Kotlin's [compatibility matrix](https://kotlinlang.org/docs/gradle-configure-project.html) explicitly supports Gradle through 9.7.0 for Kotlin 2.4.21, so the scaffold deliberately does not adopt the newer Gradle 9.8 line. [JUnit 6 requires Java 17](https://junit.org/); the current stable release was checked against the [JUnit releases](https://github.com/junit-team/junit-framework/releases).

The [Kotlin release history](https://kotlinlang.org/docs/releases.html), [MockK releases](https://github.com/mockk/mockk/releases), [ArchUnit releases](https://github.com/TNG/ArchUnit/releases) and repository activity were reviewed. None of these upstream repositories is archived. A recent release alone does not establish security or compatibility: rerun the actual scaffold tests and review upstream changes when updating versions.

## Distribution boundaries

The repository architecture tests explicitly use Kotlin reflection at test compile/runtime scope to read Kotlin visibility without initializing production classes. This is the same pinned Kotlin component already present transitively through MockK at test runtime, not a new version or production dependency.

The initial production module graph contains NuaForma project dependencies, Kotlin's standard library and its transitive JetBrains annotations. The Kotlin BOM aligns runtime Kotlin components, but does not override the compiler's private tool dependencies. Test libraries, their mocking agents and ArchUnit's bundled analysis libraries must not appear in published library runtime dependencies. `verifyBuildStructure` checks this boundary during the build; generated Maven POMs make dependency scopes reviewable.

JUnit's EPL-2.0 license applies to the test tooling, not to NuaForma's production code. It is not bundled into production JARs. Any later redistribution of test tools must carry their own required licenses and notices. Likewise, Gradle, Kotlin and other dependencies retain their own licensing obligations; Apache-2.0 on NuaForma does not replace them. Library JAR tasks include NuaForma's LICENSE without shading third-party classes.

Review transitive dependencies and upstream security advisories when adding or upgrading dependencies. Lockfiles fix selected versions but are not a vulnerability scan or a security guarantee. No expression engine, HTTP framework or PDF renderer is added; their dependency and license reviews belong to their own issues.

## DOCX integration review

The DOCX watermark adapter's VML type-136 geometry is adapted from docx4j's Apache-2.0 [tagged Watermark sample](https://github.com/plutext/docx4j/blob/VERSION_17_1_0/docx4j-samples-docx4j/src/main/java/org/docx4j/samples/Watermark.java). Section inheritance, content-preserving anchors, bounds and request isolation are NuaForma's implementation. This adds no dependency or copied renderer; docx4j's existing license and notice obligations still apply.

For issue #5, docx4j core 17.1.0 is an implementation dependency and `docx4j-JAXB-ReferenceImpl` 17.1.0 supplies JAXB at runtime. The [upstream release history](https://www.docx4java.org/trac/docx4j) reports its September 2026 release; the repository is not archived and has current development activity. The [tagged parent POM](https://github.com/plutext/docx4j/blob/VERSION_17_1_0/pom.xml) targets Java 11; NuaForma compiles and executes the integration on Java 17. No JAXB implementation classes enter facade signatures. No global logger implementation is added.

The resolved production graph includes JAXB runtime/core/txw2 4.0.9, Jakarta JAXB API 4.0.5, Activation API 2.1.4, Angus Activation 2.0.3, istack 4.1.2, Commons Lang 3.20.0, Compress 1.28.0, IO 2.22.0, Codec 1.19.0, Collections 4.5.0, FontBox/PDFBox IO 3.0.8, XML Graphics 2.11, SLF4J 2.0.18 and docx4j's generated/copy/SVG/Xalan support artifacts. Upstream POM exclusions avoid optional native compressors, JUEL expression implementations and extra logging implementations. These are docx4j runtime dependencies, not adoption of a PDF conversion backend or an expression language.

Several upstream transitives are legacy components, notably ANTLR 2.7.7, StringTemplate 3.2.1, QDox 1.12.1 and MBassador 1.3.2. They are retained by the current docx4j distribution rather than selected as new NuaForma APIs. Do not expose their evaluators or assume that every transitive has docx4j's release cadence. Reassess upstream removals/upgrades and security advisories on each integration upgrade instead of overriding versions or pruning classes without compatibility tests.

Pinned Maven POMs, inherited license metadata and artifact license material were reviewed. The [third-party notice guide](THIRD_PARTY.md) records permissive Apache/MIT/BSD/EDL families and docx4j's additional bundled terms; redistribution must preserve original artifact notices. An OSV batch query on 2026-10-11 for all 36 resolved DOCX Maven runtime coordinates returned no known matches. This is an advisory-database snapshot, not proof of absence of vulnerabilities or approval for exposing untrusted templates without further hosted isolation.

## PDF research tooling

Issue #10 uses a separate, unpublished Java 17 build under `tools/pdf-evaluation`, not a product dependency change. docx4j-export-fo and JAXB ReferenceImpl 17.1.0 resolve FOP 2.11 and the locked research graph described in [the evaluation](docs/pdf-backend-evaluation.md). That report records primary license/maintenance sources, bundled font notices, an OSV snapshot and observed rendering failures. LibreOffice 26.8.1.1 is the externally installed Windows research baseline; the user selected LibreOffice for production implementation on 2026-10-12 and approved its additional redistribution obligations. No renderer is bundled yet. Qualify and pin the actual production package independently, including maintenance/security review, Linux rendering and the [distribution checklist](docs/libreoffice-licensing.md); approval of a backend is not approval of every package/version. Euro-Office was evaluated only in unpublished research; neither it nor ONLYOFFICE is a product dependency.

## PDF output-validation review

PDFBox 3.0.8 is the approved structural output-checking library, not a DOCX renderer or PDF/A validator. Its [release downloads](https://pdfbox.apache.org/download.html), [Java compatibility and file-backed reading documentation](https://pdfbox.apache.org/3.0/migration.html), tagged license/notice and [security policy](https://pdfbox.apache.org/security.html) were reviewed on 2026-10-12. The production graph adds `pdfbox` and Apache-2.0 Commons Logging 1.4.0; FontBox and PDFBox IO 3.0.8 were already locked through docx4j. No optional cryptography or image-codec dependencies are selected. The isolated validator distribution contains the PDF module, contracts, Kotlin runtime, PDFBox/FontBox/IO, Commons Logging and compiler annotations; it does not include core, server, DOCX dependencies or test libraries.

PDFBox explicitly excludes denial-of-service through excessive CPU, recursion or memory from its security guarantees. Consequently renderer-produced PDF parsing belongs in a bounded child JVM inside the filesystem/network sandbox, not in the hosting engine JVM. Strict parsing and application checks reject forms, attachments, executable actions and missing font embedding for the initial static profile. This does not establish PDF/A, accessibility, signature validity, glyph coverage or visual fidelity. Preserve upstream artifact license and notice materials when redistributing the validator.

An OSV batch query on 2026-10-12 for PDFBox, FontBox, PDFBox IO 3.0.8 and Commons Logging 1.4.0 returned no known matches. This is a dated advisory snapshot, not proof of absence of vulnerabilities. Bubblewrap's [0.13.0 release](https://github.com/containers/bubblewrap/releases/tag/v0.13.0), original source checksum and LGPL license files were checked; its separately compiled executable and source archive are confined to the optional runtime image, not library artifacts. Debian and Java/LibreOffice build inputs are pinned in the runtime Dockerfile and snapshot configuration; public redistribution still requires the release-specific source/license audit.

## REST integration review

Issue #12 uses the approved Ktor 3.6.0 framework with its CIO server engine, request-body limits and status-page error mapping. The [tagged license](https://github.com/ktorio/ktor/blob/3.6.0/LICENSE), [release history](https://github.com/ktorio/ktor/releases), pinned Maven POMs and current source were reviewed on 2026-10-12. Ktor remains actively developed and Apache-2.0 licensed. CIO avoids introducing Netty/native transport into the service. Ktor and its coroutine/IO APIs remain server-only implementation dependencies behind the HTTP adapter, not library contracts.

The new resolved server production graph contains Ktor 3.6.0, Kotlinx Coroutines 1.11.0, Kotlinx Serialization 1.11.0, Kotlinx IO 0.9.1, Typesafe Config 1.4.9 and SLF4J API 2.0.19. Kotlinx and Config are Apache-2.0; SLF4J remains MIT. The Kotlin BOM retains the project's Kotlin 2.4.21 alignment. JSON uses a tree adapter with bounded UTF-8, nesting, token and number handling; parser exceptions and raw input are never returned or logged. Duplicate keys are rejected before tree construction. No serialization compiler plugin or domain DTO is introduced into contracts.

The Ktor test host and its client, coroutine test/debug, JUnit, Apache HTTP client, Byte Buddy and logging components are test-only; they must not enter the service runtime. Per-module lockfiles and the build's test-tool isolation check record/enforce scopes. Java 17 compile/runtime tests are required, not inferred from POMs. An OSV batch query on 2026-10-12 for all 72 resolved server runtime coordinates returned no known matches; this is a dated advisory snapshot, not a security guarantee. Redistribution must retain actual upstream license/copyright/notice material; final service/container packaging remains subject to its own inventory and distribution audit.

## OpenAPI test-tool review

For issue #13 the user approved Swagger Parser strictly as test tooling. The pinned `swagger-parser-v3` 2.1.48 [release](https://github.com/swagger-api/swagger-parser/releases/tag/v2.1.48), [Apache-2.0 license](https://github.com/swagger-api/swagger-parser/blob/v2.1.48/LICENSE), Maven POMs, activity and local parsing options were reviewed on 2026-10-12. The v3 artifact avoids the aggregate parser's legacy Swagger 2 modules. The parent targets Java 8; the actual integration is compiled/executed on Java 17. No annotations, vendor models, parser or remote-reference resolver enter production source or runtime.

New test transitives are Swagger Core/models/annotations 2.2.53, parser core/safe URL resolver 2.1.48, Jackson annotations 2.22 and core/databind/YAML/JSR310 2.22.3, SnakeYAML 2.6 and Jakarta Validation API 2.0.2 (Apache-2.0 families). Existing Commons IO/Lang, SLF4J and JAXB dependencies retain their previously reviewed terms. Original artifacts' licenses/notices must be preserved if test tools are ever redistributed; they are not part of the service/application distribution. Upstream Maven test dependencies such as JMockit, TestNG and WireMock are not adopted.

The initial transitive Jackson 2.22.1 selection matched two core and five databind advisories. The reviewed fixes include [core 2.22.3](https://github.com/FasterXML/jackson-core/releases/tag/jackson-core-2.22.3) and [databind 2.22.3](https://github.com/FasterXML/jackson-databind/releases/tag/jackson-databind-2.22.3), so a test-only Jackson BOM deliberately selects patched/aligned 2.22.3 components. An OSV batch query on 2026-10-12 for the fourteen final new test coordinates returned no known matches. This is a dated snapshot, not a guarantee. Keep this review/lock update explicit on upgrades; do not silently rely on Swagger's older transitive baseline. Production runtime coordinates are unchanged.

Production JSON/YAML specification output is derived from one NuaForma-owned JSON-compatible model. The YAML writer emits only quoted JSON scalars, mappings and sequences, not a general YAML parser. Tests use independent Jackson/SnakeYAML tree parsing and Swagger OpenAPI parsing. References are checked as local before parsing, with external reference validation/resolution disabled. Behavioral assertions test the used schema keyword subset against real API responses/requests and malformed/drift fixtures. No arbitrary uploaded specification is parsed, and no production HTTP/rendering dependency is added for documentation.

## Version freshness audit — 2026-10-12

A read-only check compared all 165 distinct group/artifact/version coordinates in the six product-module lockfiles (including test and compiler-tool configurations) against primary Maven Central metadata. 126 already match the checked stable version; the 39 remaining coordinates are listed below. Counts include distinct versions of the same artifact on isolated configurations, not 165 independent libraries. The root settings lock contains only an empty catalog marker. Release candidates, snapshots, alpha/beta/milestone/EAP builds and Byte Buddy's alternative old-JDK classifier releases were excluded. Old metadata can be incomplete: ANTLR's metadata omits the selected 2.7.7, whose actual published POM was checked; it must not be downgraded to the last listed 2.7.6. This is a dated release inventory, not compatibility qualification or a complete security scan.

The explicit catalog pins Kotlin 2.4.21, JUnit 6.1.3, MockK 1.14.11, ArchUnit 1.5.1, PDFBox 3.0.8, Ktor 3.6.0, Kotlinx Serialization 1.11.0, Swagger Parser 2.1.48 and the Jackson test BOM 2.22.3 at the checked newest stable releases. Serialization 1.12.0-RC is not a stable upgrade. docx4j is the direct exception: 17.3.2 is published and advertised on the [upstream downloads page](https://www.docx4java.org/downloads.html), while our qualified integration remains 17.1.0. Upgrade it as a coherent family with license/security review, Java 17 integration, package-preservation tests and actual DOCX/PDF visual/barcode qualification; availability of the new release does not establish unchanged output.

| Maven coordinate and primary metadata | Locked version | Checked newest stable |
| --- | --- | --- |
| [`com.google.errorprone:error_prone_annotations`](https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/maven-metadata.xml) | 2.3.3 | 2.50.0 |
| [`com.sun.istack:istack-commons-runtime`](https://repo.maven.apache.org/maven2/com/sun/istack/istack-commons-runtime/maven-metadata.xml) | 4.1.2 | 4.2.0 |
| [`com.thoughtworks.qdox:qdox`](https://repo.maven.apache.org/maven2/com/thoughtworks/qdox/qdox/maven-metadata.xml) | 1.12.1 | 2.2.0 |
| [`commons-codec:commons-codec`](https://repo.maven.apache.org/maven2/commons-codec/commons-codec/maven-metadata.xml) | 1.19.0 | 1.23.0 |
| [`io.swagger.core.v3:swagger-annotations`](https://repo.maven.apache.org/maven2/io/swagger/core/v3/swagger-annotations/maven-metadata.xml) | 2.2.53 | 2.2.55 |
| [`io.swagger.core.v3:swagger-core`](https://repo.maven.apache.org/maven2/io/swagger/core/v3/swagger-core/maven-metadata.xml) | 2.2.53 | 2.2.55 |
| [`io.swagger.core.v3:swagger-models`](https://repo.maven.apache.org/maven2/io/swagger/core/v3/swagger-models/maven-metadata.xml) | 2.2.53 | 2.2.55 |
| [`jakarta.activation:jakarta.activation-api`](https://repo.maven.apache.org/maven2/jakarta/activation/jakarta.activation-api/maven-metadata.xml) | 1.2.2 | 2.1.4 |
| [`jakarta.validation:jakarta.validation-api`](https://repo.maven.apache.org/maven2/jakarta/validation/jakarta.validation-api/maven-metadata.xml) | 2.0.2 | 3.1.1 |
| [`jakarta.xml.bind:jakarta.xml.bind-api`](https://repo.maven.apache.org/maven2/jakarta/xml/bind/jakarta.xml.bind-api/maven-metadata.xml) | 2.3.3 | 4.0.5 |
| [`net.bytebuddy:byte-buddy-agent`](https://repo.maven.apache.org/maven2/net/bytebuddy/byte-buddy-agent/maven-metadata.xml) | 1.18.2 | 1.18.14 |
| [`net.bytebuddy:byte-buddy`](https://repo.maven.apache.org/maven2/net/bytebuddy/byte-buddy/maven-metadata.xml) | 1.18.2 | 1.18.14 |
| [`net.java.dev.jna:jna-platform`](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna-platform/maven-metadata.xml) | 5.9.0 | 5.19.1 |
| [`net.java.dev.jna:jna`](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/maven-metadata.xml) | 5.9.0 | 5.19.1 |
| [`org.antlr:stringtemplate`](https://repo.maven.apache.org/maven2/org/antlr/stringtemplate/maven-metadata.xml) | 3.2.1 | 4.0.2 |
| [`org.apache.commons:commons-collections4`](https://repo.maven.apache.org/maven2/org/apache/commons/commons-collections4/maven-metadata.xml) | 4.5.0 | 4.6.0 |
| [`org.apache.commons:commons-lang3`](https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/maven-metadata.xml) | 3.20.0 | 3.21.0 |
| [`org.apache.httpcomponents.client5:httpclient5`](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/client5/httpclient5/maven-metadata.xml) | 5.6.4 | 5.6.5 |
| [`org.apache.httpcomponents.core5:httpcore5-h2`](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/core5/httpcore5-h2/maven-metadata.xml) | 5.4.3 | 5.4.4 |
| [`org.apache.httpcomponents.core5:httpcore5`](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/core5/httpcore5/maven-metadata.xml) | 5.4.3 | 5.4.4 |
| [`org.checkerframework:checker-qual`](https://repo.maven.apache.org/maven2/org/checkerframework/checker-qual/maven-metadata.xml) | 3.54.0 | 4.3.1 |
| [`org.docx4j:docx4j-copy`](https://repo.maven.apache.org/maven2/org/docx4j/docx4j-copy/maven-metadata.xml) | 17.1.0 | 17.3.2 |
| [`org.docx4j:docx4j-core`](https://repo.maven.apache.org/maven2/org/docx4j/docx4j-core/maven-metadata.xml) | 17.1.0 | 17.3.2 |
| [`org.docx4j:docx4j-generated-objects`](https://repo.maven.apache.org/maven2/org/docx4j/docx4j-generated-objects/maven-metadata.xml) | 17.1.0 | 17.3.2 |
| [`org.docx4j:docx4j-JAXB-ReferenceImpl`](https://repo.maven.apache.org/maven2/org/docx4j/docx4j-JAXB-ReferenceImpl/maven-metadata.xml) | 17.1.0 | 17.3.2 |
| [`org.jetbrains:annotations`](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/maven-metadata.xml) | 13.0 | 26.1.0 |
| [`org.jetbrains:annotations`](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/maven-metadata.xml) | 23.0.0 | 26.1.0 |
| [`org.jetbrains.kotlin:kotlin-reflect`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-reflect/maven-metadata.xml) | 1.6.10 | 2.4.21 |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-bom`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-bom/maven-metadata.xml) | 1.10.2 | 1.11.0 |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/maven-metadata.xml) | 1.10.2 | 1.11.0 |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/maven-metadata.xml) | 1.8.0 | 1.11.0 |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-core`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml) | 1.10.2 | 1.11.0 |
| [`org.jspecify:jspecify`](https://repo.maven.apache.org/maven2/org/jspecify/jspecify/maven-metadata.xml) | 1.0.0 | 1.0.1 |
| [`org.jvnet.jaxb:jaxb-plugins-runtime`](https://repo.maven.apache.org/maven2/org/jvnet/jaxb/jaxb-plugins-runtime/maven-metadata.xml) | 4.0.13 | 4.0.16 |
| [`org.objenesis:objenesis`](https://repo.maven.apache.org/maven2/org/objenesis/objenesis/maven-metadata.xml) | 3.4 | 3.6 |
| [`org.slf4j:jcl-over-slf4j`](https://repo.maven.apache.org/maven2/org/slf4j/jcl-over-slf4j/maven-metadata.xml) | 2.0.18 | 2.0.20 |
| [`org.slf4j:slf4j-api`](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/maven-metadata.xml) | 2.0.18 | 2.0.20 |
| [`org.slf4j:slf4j-api`](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/maven-metadata.xml) | 2.0.19 | 2.0.20 |
| [`org.yaml:snakeyaml`](https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/maven-metadata.xml) | 2.6 | 2.7 |

Scope matters before upgrading: docx4j, Commons Codec/Collections/Lang, istack, JAXB plugins, legacy QDox/StringTemplate, Checker annotations and logging enter the document runtime. Byte Buddy/Objenesis are MockK test transitives; JNA and Apache HTTP components come through the Ktor test stack. Swagger Core/SnakeYAML/Validation are test-only. Kotlin reflection 1.6.10 and Coroutines 1.8.0 belong to the compiler's private build-tools configuration, not the engine runtime; Coroutines 1.10.2 occurs in other modules' test runtimes, while the server uses 1.11.0. JetBrains/JSpecify annotations also differ by configuration. Do not override these globally.

Jakarta Activation 1.2.2, Validation 2.0.2 and JAXB API 2.3.3 are older namespace/API generations in the parser test graph, not drop-in replacements for their newer majors; the document runtime already uses Activation 2.1.4 and JAXB API 4.0.5. Likewise QDox 2.x and StringTemplate 4.x require an upstream integration change, not just a forced version. The table deliberately distinguishes an available newer release from a safe compatible update. No existing dependency was upgraded as a side effect of this audit.

[Gradle's current release API](https://services.gradle.org/versions/current) reports 9.8.1, but the [Kotlin 2.4.21 compatibility matrix](https://kotlinlang.org/docs/gradle-configure-project.html) ends at 9.7.0, our deliberately retained fully supported wrapper. GitHub's latest-release APIs confirm checkout 7.0.1, setup-java 6.0.1 and [Bubblewrap 0.13.0](https://github.com/containers/bubblewrap/releases). The [LibreOffice downloads](https://www.libreoffice.org/download/) confirm 26.8.1; [Adoptium's Java 17 Linux JRE API](https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jre&os=linux) confirms 17.0.20.1+1, matching the qualified runtime. Java 17 remains the baseline rather than being replaced with a newer Java major.

The separate unpublished FOP evaluation lockfile was also inspected: its docx4j/export/font family is still 17.1.0 versus 17.3.2; FOP 2.11 and Batik 1.19 match checked stable releases. This is research tooling, not the selected production renderer. Historical proof-of-concept files and workstation-provided Python/rendering utilities are not product dependencies. OS/base-image packages follow the immutable authenticated Debian snapshot and distribution/security branch, not necessarily the latest upstream version of each system library. Their inventories, image-digest freshness and branch security updates need a release-specific image audit under #14; this Maven audit does not claim every bundled OS package or image digest is globally latest. Keep the required source/license distribution audit as a separate release gate.

## GitHub Actions

The build workflow uses [actions/checkout v7.0.1](https://github.com/actions/checkout/tree/v7.0.1) and [actions/setup-java v6.0.1](https://github.com/actions/setup-java/tree/v6.0.1), both MIT-licensed, pinned to their full release commit hashes. Their upstream releases, runtime declarations and licenses were checked on 2026-10-11. They are CI tooling, not dependencies of published NuaForma libraries.

The current [gradle/actions v6.4.0 license](https://github.com/gradle/actions/blob/v6.4.0/LICENSE) identifies a vendor-included proprietary caching component. It is deliberately not adopted. The workflow uses the checked-in wrapper directly and validates its JAR checksum without that action.
