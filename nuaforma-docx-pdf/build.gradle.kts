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
