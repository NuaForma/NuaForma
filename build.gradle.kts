import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
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
    group = rootProject.group
    version = rootProject.version

    dependencyLocking {
        lockAllConfigurations()
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
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

val libraryModules = subprojects.filter { it.name != "nuaforma-server" }
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
            ":nuaforma-server" to subprojects.map { it.path }.toSet(),
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
            val projectPaths = components.mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }.toSet()
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
            if (module.path !in setOf(":nuaforma-docx-pdf", ":nuaforma-server")) {
                check(components.none { it.moduleVersion?.group == "org.apache.pdfbox" && it.moduleVersion?.name == "pdfbox" }) {
                    "PDF output validation leaked into ${module.path} runtimeClasspath"
                }
            }
        }
    }
}
