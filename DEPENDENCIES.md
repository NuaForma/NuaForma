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

Issue #10 uses a separate, unpublished Java 17 build under `tools/pdf-evaluation`, not a product dependency change. docx4j-export-fo and JAXB ReferenceImpl 17.1.0 resolve FOP 2.11 and the locked research graph described in [the evaluation](docs/pdf-backend-evaluation.md). That report records primary license/maintenance sources, bundled font notices, an OSV snapshot and observed rendering failures. LibreOffice 26.8.1.1 is an externally installed research executable, not bundled or approved as a production renderer. No ONLYOFFICE component is installed. Production dependency adoption remains subject to backend approval and complete redistribution review.

## GitHub Actions

The build workflow uses [actions/checkout v7.0.1](https://github.com/actions/checkout/tree/v7.0.1) and [actions/setup-java v6.0.1](https://github.com/actions/setup-java/tree/v6.0.1), both MIT-licensed, pinned to their full release commit hashes. Their upstream releases, runtime declarations and licenses were checked on 2026-10-11. They are CI tooling, not dependencies of published NuaForma libraries.

The current [gradle/actions v6.4.0 license](https://github.com/gradle/actions/blob/v6.4.0/LICENSE) identifies a vendor-included proprietary caching component. It is deliberately not adopted. The workflow uses the checked-in wrapper directly and validates its JAR checksum without that action.
