# Dependency baseline

This review accompanies the build scaffold for issue #1, dated 2026-10-11. It is not a claim that document processing or PDF conversion is implemented. Direct versions are pinned in `gradle/libs.versions.toml`; the wrapper pins Gradle and its distribution checksum. Per-module lockfiles record resolved dependencies.

| Component | Version | Scope | License and primary reference |
| --- | --- | --- | --- |
| Gradle | 9.7.0 | Build tool and wrapper | [Apache-2.0](https://github.com/gradle/gradle/blob/v9.7.0/LICENSE) |
| Kotlin | 2.4.21 | Compiler and JVM standard library | [Apache-2.0 and bundled third-party notices](https://github.com/JetBrains/kotlin/blob/v2.4.21/license/LICENSE.txt) |
| JUnit Jupiter | 6.1.3 | Tests only | [EPL-2.0](https://github.com/junit-team/junit-framework/blob/r6.1.3/LICENSE.md) |
| MockK | 1.14.11 | Tests only | [Apache-2.0](https://github.com/mockk/mockk/blob/v1.14.11/LICENSE) |
| ArchUnit | 1.5.1 | Tests only | [Apache-2.0 with bundled dependency notices](https://github.com/TNG/ArchUnit/blob/v1.5.1/LICENSE) |

## Compatibility and maintenance

Java 17 is both the compilation target and test runtime. Kotlin's [compatibility matrix](https://kotlinlang.org/docs/gradle-configure-project.html) explicitly supports Gradle through 9.7.0 for Kotlin 2.4.21, so the scaffold deliberately does not adopt the newer Gradle 9.8 line. [JUnit 6 requires Java 17](https://junit.org/); the current stable release was checked against the [JUnit releases](https://github.com/junit-team/junit-framework/releases).

The [Kotlin release history](https://kotlinlang.org/docs/releases.html), [MockK releases](https://github.com/mockk/mockk/releases), [ArchUnit releases](https://github.com/TNG/ArchUnit/releases) and repository activity were reviewed. None of these upstream repositories is archived. A recent release alone does not establish security or compatibility: rerun the actual scaffold tests and review upstream changes when updating versions.

## Distribution boundaries

The initial production module graph contains NuaForma project dependencies, Kotlin's standard library and its transitive JetBrains annotations. The Kotlin BOM aligns runtime Kotlin components, but does not override the compiler's private tool dependencies. Test libraries, their mocking agents and ArchUnit's bundled analysis libraries must not appear in published library runtime dependencies. `verifyBuildStructure` checks this boundary during the build; generated Maven POMs make dependency scopes reviewable.

JUnit's EPL-2.0 license applies to the test tooling, not to NuaForma's production code. It is not bundled into production JARs. Any later redistribution of test tools must carry their own required licenses and notices. Likewise, Gradle, Kotlin and other dependencies retain their own licensing obligations; Apache-2.0 on NuaForma does not replace them. Library JAR tasks include NuaForma's LICENSE without shading third-party classes.

Review transitive dependencies and upstream security advisories when adding or upgrading dependencies. Lockfiles fix selected versions but are not a vulnerability scan or a security guarantee. No DOCX library, expression engine, HTTP framework or PDF renderer is added in this scaffold; their dependency and license reviews belong to their own issues.
