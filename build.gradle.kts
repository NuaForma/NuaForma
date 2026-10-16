import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    jacoco
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.kotlin.jvm) apply false
}

val coverageEnabled = providers.gradleProperty("coverage").map {
    require(it in setOf("true", "false")) { "coverage must be true or false" }
    it == "true"
}.orElse(false)
val jacocoVersion = libs.versions.jacoco.get()
jacoco { toolVersion = jacocoVersion }
dependencyLocking { lockAllConfigurations() }
sonar {
    properties {
        property("sonar.projectKey", "NuaForma_NuaForma")
        property("sonar.organization", "nuaforma")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.sourceEncoding", "UTF-8")
    }
}

val kotlinBom = libs.kotlin.bom
val junitBom = libs.junit.bom
val junitJupiter = libs.junit.jupiter
val junitLauncher = libs.junit.launcher
val mockk = libs.mockk
val archunit = libs.archunit
val annotations = libs.annotations
val testTransitives = listOf(libs.byte.buddy, libs.byte.buddy.agent, libs.objenesis, libs.jspecify, libs.coroutines.bom, libs.coroutines.core, libs.slf4j.api)
val docxRuntimeUpgrades = listOf(libs.commons.codec, libs.commons.collections, libs.commons.lang, libs.istack, libs.jaxb.plugins,
    libs.checker.qual, libs.error.prone.annotations, libs.slf4j.jcl, libs.slf4j.api).map { it.get() }

subprojects {
    val jvmVersion = if (name in setOf("nuaforma-server", "nuaforma-playground")) 25 else 17
    group = rootProject.group
    version = rootProject.version

    dependencyLocking {
        lockAllConfigurations()
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "jacoco")
        extensions.configure<JacocoPluginExtension> { toolVersion = jacocoVersion }
        extensions.configure<org.sonarqube.gradle.SonarExtension> {
            properties {
                property("sonar.coverage.jacoco.xmlReportPaths", rootProject.layout.buildDirectory.file("reports/jacoco/coverageReport/coverageReport.xml").get().asFile.absolutePath)
            }
        }
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(jvmVersion)
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(jvmVersion.toString()))
                freeCompilerArgs.add("-Xjdk-release=$jvmVersion")
            }
        }
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(jvmVersion))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(jvmVersion)
        }
        dependencies {
            add("implementation", platform(kotlinBom))
            add("implementation", annotations)
            add("testImplementation", platform(junitBom))
            add("testImplementation", junitJupiter)
            add("testImplementation", mockk)
            add("testImplementation", archunit)
            add("testRuntimeOnly", junitLauncher)
            constraints {
                testTransitives.forEach { add("testImplementation", it) }
            }
        }
        tasks.withType<Test>().configureEach {
            inputs.property("coverageEnabled", coverageEnabled)
            extensions.configure<JacocoTaskExtension> {
                isEnabled = coverageEnabled.get()
                includes = listOf("dev.nuaforma.*")
            }
            javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(jvmVersion))
            })
            useJUnitPlatform()
            testLogging {
                events("failed", "skipped")
            }
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    pluginManager.withPlugin("maven-publish") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        tasks.withType<Jar>().configureEach {
            from(rootProject.file("LICENSE")) {
                into("META-INF")
            }
            from(rootProject.file("THIRD_PARTY.md")) { into("META-INF") }
        }
        tasks.named<Jar>("javadocJar") {
            from(project.file("README.md"))
            from(rootProject.file("ARCHITECTURE.md"))
        }
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "Staging"
                    url = rootProject.layout.buildDirectory.dir("library-repository").get().asFile.toURI()
                }
            }
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    pom {
                        name.set(project.name)
                        description.set(provider { project.description })
                        url.set("https://github.com/NuaForma/NuaForma")
                        licenses {
                            license {
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                                distribution.set("repo")
                            }
                        }
                        scm {
                            url.set("https://github.com/NuaForma/NuaForma")
                            connection.set("scm:git:https://github.com/NuaForma/NuaForma.git")
                            developerConnection.set("scm:git:ssh://git@github.com/NuaForma/NuaForma.git")
                        }
                        developers {
                            developer {
                                id.set("nuaforma")
                                name.set("NuaForma maintainers")
                                url.set("https://github.com/NuaForma")
                                organization.set("NuaForma")
                                organizationUrl.set("https://nuaforma.dev")
                            }
                        }
                    }
                }
            }
        }
        val signArtifacts = providers.gradleProperty("signArtifacts").orNull
        require(signArtifacts == null || signArtifacts in setOf("true", "false")) { "signArtifacts must be true or false" }
        if (signArtifacts == "true") {
            apply(plugin = "signing")
            val signingKey = providers.environmentVariable("MAVEN_SIGNING_KEY").orNull
            val signingPassword = providers.environmentVariable("MAVEN_SIGNING_PASSWORD").orNull
            require(!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) { "Artifact signing credentials are required" }
            extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(extensions.getByType<PublishingExtension>().publications["mavenJava"])
            }
        }
    }
}

val modulesWithJvmTests = provider {
    subprojects.filter {
        !it.extensions.getByType<SourceSetContainer>()["test"].allSource.matching {
            include("**/*.kt", "**/*.java")
        }.isEmpty
    }
}
val verifyCoverageInputs = tasks.register("verifyCoverageInputs") {
    dependsOn(subprojects.map { "${it.path}:test" })
    doLast {
        check(coverageEnabled.get()) { "Use -Pcoverage=true to record test execution before reporting coverage" }
        modulesWithJvmTests.get().forEach {
            check(it.layout.buildDirectory.file("jacoco/test.exec").get().asFile.isFile) {
                "Missing JVM coverage data for ${it.name}"
            }
        }
    }
}
val coverageReport = tasks.register<JacocoReport>("coverageReport") {
    group = "verification"
    description = "Merges JVM tests against all seven production modules; requires -Pcoverage=true."
    dependsOn(verifyCoverageInputs)
    executionData.from(modulesWithJvmTests.map { modules -> modules.map { it.layout.buildDirectory.file("jacoco/test.exec") } })
    classDirectories.from(provider {
        subprojects.map { it.extensions.getByType<SourceSetContainer>()["main"].output.classesDirs }
    })
    sourceDirectories.from(provider {
        subprojects.map { it.extensions.getByType<SourceSetContainer>()["main"].allSource.sourceDirectories }
    })
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}
tasks.named("sonar") { dependsOn(coverageReport) }

val sonarProperties = tasks.named<org.sonarqube.gradle.SonarTask>("sonar").flatMap { it.properties }
tasks.register("verifySonarConfiguration") {
    group = "verification"
    description = "Checks Sonar project identity and per-module report mapping without contacting Sonar."
    doLast {
        val properties = sonarProperties.get()
        check(properties["sonar.projectKey"] == "NuaForma_NuaForma")
        check(properties["sonar.organization"] == "nuaforma")
        val reportPaths = properties.filterKeys { it.endsWith(".sonar.coverage.jacoco.xmlReportPaths") }
        check(reportPaths.size == subprojects.size) { "Every module must import the aggregate coverage report" }
        check(reportPaths.values.toSet() == setOf(layout.buildDirectory.file("reports/jacoco/coverageReport/coverageReport.xml").get().asFile.absolutePath))
        logger.lifecycle("Sonar identity and seven-module coverage mapping verified without upload")
    }
}

val libraryModules = subprojects.filter { it.name !in setOf("nuaforma-server", "nuaforma-playground") }
tasks.register("writeLibraryRuntimeInventory") {
    group = "verification"
    val output = layout.buildDirectory.file("library-runtime-files.json")
    doLast {
        val records = libraryModules.associate { module ->
            module.name to module.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
                .filter { it.moduleVersion.id.group != "dev.nuaforma" }.map { it.file.absolutePath }.sorted()
        }
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(records)))
        }
    }
}
tasks.register("stageLibraries") {
    group = "publishing"
    description = "Publishes the five libraries only to the ignored local staging repository; never uploads."
    dependsOn(libraryModules.map { "${it.path}:publishMavenJavaPublicationToStagingRepository" })
    dependsOn("writeLibraryRuntimeInventory")
}

tasks.register<Exec>("verifyLibraryPublication") {
    group = "verification"
    dependsOn("stageLibraries")
    commandLine("python3", "tools/release/verify_libraries.py", "build/library-repository", "--version", project.version.toString())
}

tasks.register<Exec>("verifyReleaseTooling") {
    group = "verification"
    commandLine("python3", "-m", "unittest", "discover", "-s", "tools/release", "-p", "test_*.py")
}

tasks.register<Exec>("verifyContainerTooling") {
    group = "verification"
    commandLine("python3", "-m", "unittest", "discover", "-s", "tools/container", "-p", "test_*.py")
}

tasks.register<Exec>("verifyQualityTooling") {
    group = "verification"
    commandLine("python3", "-m", "unittest", "discover", "-s", "tools/quality", "-p", "test_*.py")
}

tasks.register<Exec>("verifyEmbeddedConsumers") {
    group = "verification"
    dependsOn("stageLibraries")
    commandLine("python3", "tools/release/verify_consumers.py", "--repository", "build/library-repository", "--output", "build/consumer-tests",
        "--version", project.version.toString())
}

tasks.named("assemble") {
    dependsOn(subprojects.map { "${it.path}:assemble" })
}
tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn("verifyBuildStructure")
    dependsOn("verifyLibraryPublication")
    dependsOn("verifyReleaseTooling")
    dependsOn("verifyContainerTooling")
    dependsOn("verifyQualityTooling")
    dependsOn("verifySonarConfiguration")
}

tasks.register("verifyBuildStructure") {
    group = "verification"
    description = "Verifies the scaffold's runtime module graph and test dependency isolation."
    doLast {
        val expected = mapOf(
            ":nuaforma-contracts" to setOf(":nuaforma-contracts"),
            ":nuaforma-core" to setOf(":nuaforma-core", ":nuaforma-contracts"),
            ":nuaforma-template" to setOf(":nuaforma-template", ":nuaforma-contracts"),
            ":nuaforma-docx" to setOf(":nuaforma-docx", ":nuaforma-contracts", ":nuaforma-template"),
            ":nuaforma-docx-pdf" to setOf(":nuaforma-docx-pdf", ":nuaforma-docx", ":nuaforma-contracts", ":nuaforma-template"),
            ":nuaforma-server" to subprojects.filter { it.name != "nuaforma-playground" }.map { it.path }.toSet(),
            ":nuaforma-playground" to subprojects.map { it.path }.toSet(),
        )
        subprojects.forEach { module ->
            val requiredPublicationDependencies = if (module.path == ":nuaforma-docx") docxRuntimeUpgrades + annotations.get() else listOf(annotations.get())
            requiredPublicationDependencies.forEach { required ->
                check(module.configurations.getByName("implementation").dependencies.any {
                    it.group == required.group && it.name == required.name && it.version == required.versionConstraint.requiredVersion
                }) { "${module.path} must declare ${required.module} directly so Maven consumers retain the reviewed runtime version" }
            }
            val components = module.configurations.getByName("runtimeClasspath")
                .incoming.resolutionResult.allComponents
            if (module.name !in setOf("nuaforma-server", "nuaforma-playground")) {
                check(components.none { it.moduleVersion?.group == "org.apache.logging.log4j" }) {
                    "Server logging implementation leaked into ${module.path} runtimeClasspath"
                }
            }
            val projectPaths = components.mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }.toSet()
            check(components.none {
                it.moduleVersion?.group?.let { group -> group == "org.jacoco" || group.startsWith("org.sonarsource") } == true
            }) { "Code-quality build tools leaked into ${module.path} runtimeClasspath" }
            check(projectPaths == expected.getValue(module.path)) {
                "Unexpected runtime module graph for ${module.path}: $projectPaths"
            }
            val testGroups = setOf("org.junit", "org.junit.jupiter", "org.junit.platform", "io.mockk", "com.tngtech.archunit")
            check(components.none { it.moduleVersion?.group in testGroups }) {
                "Test dependencies leaked into ${module.path} runtimeClasspath"
            }
            check(components.none { it.moduleVersion?.group?.startsWith("io.swagger") == true }) {
                "OpenAPI test tooling leaked into ${module.path} runtimeClasspath"
            }
            check(components.none { it.moduleVersion?.name in setOf("ktor-server-test-host-jvm", "ktor-test-dispatcher-jvm", "kotlinx-coroutines-test-jvm", "kotlinx-coroutines-debug") }) {
                "HTTP test tooling leaked into ${module.path} runtimeClasspath"
            }
            if (module.path !in setOf(":nuaforma-docx-pdf", ":nuaforma-server", ":nuaforma-playground")) {
                check(components.none { it.moduleVersion?.group == "org.apache.pdfbox" && it.moduleVersion?.name == "pdfbox" }) {
                    "PDF output validation leaked into ${module.path} runtimeClasspath"
                }
            }
        }
    }
}
