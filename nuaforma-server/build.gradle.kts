import java.io.File
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.Version
import org.cyclonedx.model.Component

plugins {
    alias(libs.plugins.kotlin.jvm)
    java
    application
    alias(libs.plugins.cyclonedx)
}

description = "Bounded REST transport for the NuaForma engine"
application { mainClass.set("dev.nuaforma.server.Main") }
tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}

val sourceCommit = providers.provider {
    runCatching {
        providers.exec {
            workingDir(rootProject.projectDir)
            commandLine("git", "rev-parse", "--verify", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("")
}.map { if (it.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) it else "unknown" }
val libraryVersion = project.version.toString()
val buildVersion = providers.gradleProperty("containerVersion").orElse(libraryVersion).map {
    require(it.matches(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?"))) { "Invalid container build version" }
    it
}
val buildInformationDirectory = layout.buildDirectory.dir("generated/build-information")
val generateBuildInformation = tasks.register("generateBuildInformation") {
    inputs.property("version", buildVersion)
    inputs.property("commit", sourceCommit)
    outputs.dir(buildInformationDirectory)
    doLast {
        val destination = buildInformationDirectory.get().file("META-INF/nuaforma/server-build.properties").asFile
        destination.parentFile.mkdirs()
        destination.writeText("version=${buildVersion.get()}\ncommit=${sourceCommit.get()}\n", Charsets.UTF_8)
    }
}
sourceSets.main { resources.srcDir(buildInformationDirectory) }
val rawSbom = layout.buildDirectory.file("reports/cyclonedx/runtime.json")
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs = listOf("runtimeClasspath")
    skipConfigs = listOf("(?i).*test.*")
    testConfigs = emptyList()
    schemaVersion = Version.VERSION_17
    projectType = Component.Type.APPLICATION
    includeBomSerialNumber = false
    includeBuildSystem = false
    componentVersion = buildVersion.get()
    jsonOutput = rawSbom
    xmlOutput.convention(null as RegularFile?)
}
val runtimeInventory = tasks.register("writeSbomRuntimeInventory") {
    val output = layout.buildDirectory.file("sbom-runtime-files.json")
    inputs.files(configurations.runtimeClasspath)
    outputs.file(output)
    doLast {
        val artifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.map {
            mapOf("group" to it.moduleVersion.id.group, "name" to it.name, "version" to it.moduleVersion.id.version, "file" to it.file.absolutePath)
        }
        output.get().asFile.writeText(groovy.json.JsonOutput.toJson(artifacts))
    }
}
val sbomDirectory = layout.buildDirectory.dir("generated/sbom")
val sbomScript = rootProject.file("tools/sbom/normalize_runtime.py")
val sbomModuleName = project.name
val sbomRuntimeManifest = layout.buildDirectory.file("sbom-runtime-files.json")
val externalSbomFile = providers.gradleProperty("sbomFile").map { rootProject.file(it) }
val generateSbom = tasks.register<Exec>("generateSbom") {
    dependsOn("cyclonedxDirectBom", runtimeInventory)
    inputs.files(configurations.runtimeClasspath).withPropertyName("resolvedProductionArtifacts")
    inputs.file(rawSbom)
    inputs.file(layout.buildDirectory.file("sbom-runtime-files.json"))
    inputs.file(rootProject.file("tools/sbom/normalize_runtime.py"))
    inputs.property("version", buildVersion)
    inputs.property("commit", sourceCommit)
    outputs.dir(sbomDirectory)
    doFirst { commandLine("python3", sbomScript, rawSbom.get().asFile,
        sbomRuntimeManifest.get().asFile, sbomDirectory.get().file("META-INF/nuaforma/sbom.json").asFile,
        "--name", sbomModuleName, "--version", buildVersion.get(), "--commit", sourceCommit.get()) }
    doLast {
        val file = sbomDirectory.get().file("META-INF/nuaforma/sbom.json").asFile
        val errors = org.cyclonedx.parsers.JsonParser().validate(file, Version.VERSION_17)
        check(errors.isEmpty()) { "CycloneDX 1.7 schema validation failed: $errors" }
    }
}
sourceSets.main { resources.srcDir(sbomDirectory) }
tasks.named("processResources") { dependsOn(generateSbom) }
tasks.register("validateSbomFile") {
    group = "verification"
    doLast {
        val file = externalSbomFile.get()
        check(file.length() in 2..16 * 1024 * 1024)
        val errors = org.cyclonedx.parsers.JsonParser().validate(file, Version.VERSION_17)
        check(errors.isEmpty()) { "CycloneDX 1.7 schema validation failed: $errors" }
    }
}
val verifyShippedSboms = tasks.register("verifyShippedSboms") {
    group = "verification"
    dependsOn(generateSbom)
    doLast {
        listOf(sbomDirectory.get().file("META-INF/nuaforma/sbom.json").asFile).forEach { file ->
            check(org.cyclonedx.parsers.JsonParser().validate(file, Version.VERSION_17).isEmpty()) { "Invalid shipped SBOM" }
        }
        val invalid = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.7\",\"version\":\"invalid\"}"
        check(org.cyclonedx.parsers.JsonParser().validate(invalid, Version.VERSION_17).isNotEmpty()) { "Schema validator must reject invalid documents" }
    }
}
tasks.named("check") { dependsOn(verifyShippedSboms) }
tasks.named("processResources") { dependsOn(generateBuildInformation) }
tasks.named<Jar>("jar") { from(rootProject.file("LICENSE")) { into("META-INF") } }
distributions { main { contents {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("THIRD_PARTY.md"))
    from(rootProject.file("DEPENDENCIES.md"))
    from(rootProject.file("docs/libreoffice-licensing.md")) { into("docs") }
    from(rootProject.file("docs/container-releases.md")) { into("docs") }
    from(rootProject.file("docs/java-runtime-compatibility.md")) { into("docs") }
} } }

tasks.register<Sync>("prepareEngineQualification") {
    group = "verification"
    description = "Stages test-only embedded engine qualification without an HTTP server."
    dependsOn(tasks.named("testClasses"))
    from(configurations.runtimeClasspath) { into("lib") }
    from(sourceSets.main.get().output) { into("classes") }
    from(sourceSets.test.get().output.classesDirs) { into("classes") }
    into(layout.buildDirectory.dir("engine-qualification"))
}

tasks.register<JavaExec>("prepareMountedProviderQualification") {
    group = "verification"
    description = "Compiles a synthetic independent Java 17 extension using the server's Java 25 runtime."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.main.get().runtimeClasspath + sourceSets.test.get().output.classesDirs
    mainClass.set("dev.nuaforma.server.MountedProviderFixture")
    args(layout.buildDirectory.dir("mounted-provider-qualification").get().asFile.absolutePath)
}

dependencies {
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.json)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.swagger.parser)
    // Patched, aligned parser transitives; never part of the production JSON adapter.
    testImplementation(platform(libs.jackson.test.bom))
    constraints {
        testImplementation(libs.commons.lang)
        testImplementation(libs.jna)
        testImplementation(libs.jna.platform)
        testImplementation(libs.httpclient.tests)
        testImplementation(libs.httpcore.tests)
        testImplementation(libs.httpcore.h2.tests)
        testImplementation(libs.swagger.core.tests)
        testImplementation(libs.swagger.models.tests)
        testImplementation(libs.swagger.annotations.tests)
        testImplementation(libs.snakeyaml.tests)
    }
    // Already present through MockK at test runtime; compile the Kotlin visibility checks explicitly.
    testImplementation(libs.kotlin.reflect)
    implementation(project(":nuaforma-core"))
    runtimeOnly(project(":nuaforma-docx"))
    runtimeOnly(project(":nuaforma-docx-pdf"))
    runtimeOnly(project(":nuaforma-template"))
}

tasks.register<JavaExec>("generateOpenApi") {
    group = "documentation"
    description = "Exports JSON and YAML from the same server-owned OpenAPI contract."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.nuaforma.server.internal.OpenApiExport")
    val destination = layout.buildDirectory.dir("generated/openapi")
    outputs.dir(destination)
    inputs.files(sourceSets.main.get().output)
    doFirst { args(destination.get().asFile.absolutePath) }
}

tasks.named<Test>("test") {
    dependsOn(tasks.named("jar"))
    inputs.dir(rootProject.file("deployment/pdf-runtime")).withPropertyName("containerRuntime")
    inputs.file(rootProject.file(".github/workflows/container-release.yml")).withPropertyName("containerReleasePreparation")
    inputs.file(rootProject.file(".github/workflows/build.yml")).withPropertyName("splitJvmBuildMatrix")
    val modules = rootProject.subprojects.sortedBy { it.name }
    val productionOutputs = modules.associateWith { module ->
        module.provider { module.extensions.getByType<SourceSetContainer>().getByName("main").output.classesDirs }
    }
    dependsOn(modules.map { "${it.path}:classes" })
    inputs.files(productionOutputs.values).withPropertyName("architectureProductionClasses")
    doFirst {
        systemProperty("nuaforma.loggingTestClasspath", sourceSets.test.get().runtimeClasspath.asPath)
        systemProperty("nuaforma.expectedBuildVersion", buildVersion.get())
        systemProperty("nuaforma.expectedLibraryVersion", libraryVersion)
        systemProperty("nuaforma.expectedBuildCommit", sourceCommit.get())
        systemProperty("nuaforma.serverJar", tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
        systemProperty("nuaforma.sbomRuntimeManifest", sbomRuntimeManifest.get().asFile.absolutePath)
        systemProperty("nuaforma.repositoryRoot", rootProject.projectDir.absolutePath)
        systemProperty("nuaforma.architecture.modules", modules.joinToString("|") { it.name })
        productionOutputs.forEach { (module, output) ->
            systemProperty("nuaforma.architecture.${module.name}", output.get().files.joinToString(File.pathSeparator) { it.absolutePath })
        }
    }
}
