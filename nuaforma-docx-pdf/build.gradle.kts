plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "NuaForma DOCX/PDF provider with an optional isolated LibreOffice runtime"

dependencies {
    api(project(":nuaforma-contracts"))
    implementation(project(":nuaforma-docx"))
    implementation(libs.pdfbox)
}

sourceSets.test { resources.srcDir(project(":nuaforma-docx").file("src/test/resources")) }

// Unmodified upstream library JARs, isolated from the hosting application's runtime.
val veraPdfRuntime = configurations.create("veraPdfRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    exclude(group = "net.java.dev.stax-utils", module = "stax-utils")
}
dependencies {
    add(veraPdfRuntime.name, libs.verapdf.model)
    constraints {
        add(veraPdfRuntime.name, "javax.xml.bind:jaxb-api:2.3.1") {
            version { strictly("2.3.1") }
            because("Match the verified official CLI instead of a historical transitive JAXB beta")
        }
    }
}
configurations.compileOnly { extendsFrom(veraPdfRuntime) }
tasks.register<Sync>("prepareVeraPdfValidation") {
    group = "verification"
    description = "Stages unmodified veraPDF libraries without the unused XML-report StAX utility."
    from(veraPdfRuntime)
    from(rootProject.file("deployment/pdf-runtime/licenses/verapdf")) { into("licenses") }
    into(layout.buildDirectory.dir("verapdf-validation"))
    doLast {
        check(veraPdfRuntime.incoming.resolutionResult.allComponents.none {
            it.moduleVersion?.group == "net.java.dev.stax-utils"
        }) { "Legacy StAX utility must not enter the isolated validator" }
        val artifacts = veraPdfRuntime.resolvedConfiguration.resolvedArtifacts.map { artifact ->
            val id = artifact.moduleVersion.id
            mapOf("group" to id.group, "artifact" to id.name, "version" to id.version,
                "classifier" to artifact.classifier, "file" to artifact.file.name)
        }.sortedBy { it.getValue("file") }
        layout.buildDirectory.file("verapdf-validation/components.json").get().asFile.writeText(
            groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(artifacts)))
    }
}

val pdfValidationRuntime = configurations.create("pdfValidationRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(pdfValidationRuntime.name, project(":nuaforma-contracts"))
    add(pdfValidationRuntime.name, libs.pdfbox)
    add(pdfValidationRuntime.name, platform(libs.kotlin.bom))
}
tasks.register<Sync>("preparePdfValidation") {
    group = "verification"
    description = "Stages the bounded validator JVM without DOCX, core, server or test dependencies."
    dependsOn(tasks.named("jar"))
    from(pdfValidationRuntime)
    from(tasks.named("jar"))
    into(layout.buildDirectory.dir("pdf-validation"))
}

tasks.register<Sync>("prepareRuntimeQualification") {
    group = "verification"
    description = "Stages the embedded runtime and independent qualification entry point; not a service distribution."
    dependsOn(tasks.named("testClasses"))
    from(configurations.runtimeClasspath) { into("lib") }
    from(sourceSets.main.get().output) { into("classes") }
    from(sourceSets.test.get().output.classesDirs) { into("classes") }
    into(layout.buildDirectory.dir("runtime-qualification"))
}
