import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    java
    application
}

description = "Bounded REST transport for the NuaForma engine"
application { mainClass.set("dev.nuaforma.server.Main") }
tasks.named<Jar>("jar") { from(rootProject.file("LICENSE")) { into("META-INF") } }
distributions { main { contents {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("THIRD_PARTY.md"))
    from(rootProject.file("DEPENDENCIES.md"))
} } }

tasks.register<Sync>("prepareEngineQualification") {
    group = "verification"
    description = "Stages test-only embedded engine qualification without an HTTP server."
    dependsOn(tasks.named("testClasses"))
    from(configurations.runtimeClasspath) { into("lib") }
    from(sourceSets.main.get().output.classesDirs) { into("classes") }
    from(sourceSets.test.get().output.classesDirs) { into("classes") }
    into(layout.buildDirectory.dir("engine-qualification"))
}

dependencies {
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.json)
    implementation(libs.slf4j.api)
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
    val modules = rootProject.subprojects.sortedBy { it.name }
    val productionOutputs = modules.associateWith { module ->
        module.provider { module.extensions.getByType<SourceSetContainer>().getByName("main").output.classesDirs }
    }
    dependsOn(modules.map { "${it.path}:classes" })
    inputs.files(productionOutputs.values).withPropertyName("architectureProductionClasses")
    doFirst {
        systemProperty("nuaforma.architecture.modules", modules.joinToString("|") { it.name })
        productionOutputs.forEach { (module, output) ->
            systemProperty("nuaforma.architecture.${module.name}", output.get().files.joinToString(File.pathSeparator) { it.absolutePath })
        }
    }
}
