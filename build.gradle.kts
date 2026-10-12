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
            add("testImplementation", platform(junitBom))
            add("testImplementation", junitJupiter)
            add("testImplementation", mockk)
            add("testImplementation", archunit)
            add("testRuntimeOnly", junitLauncher)
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
        }
        extensions.configure<PublishingExtension> {
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
                        }
                    }
                }
            }
        }
    }
}

tasks.named("assemble") {
    dependsOn(subprojects.map { "${it.path}:assemble" })
}
tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn("verifyBuildStructure")
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
