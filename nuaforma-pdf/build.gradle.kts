plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "Experimental format-neutral NuaForma PDF engine; qualified Latin, Greek and Cyrillic text"

dependencies {
    implementation(libs.pdfbox)
}

val veraPdfRuntime = configurations.create("veraPdfRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    exclude(group = "net.java.dev.stax-utils", module = "stax-utils")
}
dependencies {
    add(veraPdfRuntime.name, libs.verapdf.model)
    constraints { add(veraPdfRuntime.name, "javax.xml.bind:jaxb-api:2.3.1") { version { strictly("2.3.1") } } }
}
configurations.compileOnly { extendsFrom(veraPdfRuntime) }
val prepareOwnEngineValidation = tasks.register<Sync>("prepareOwnEngineValidation") {
    group = "verification"
    dependsOn(tasks.jar)
    from(veraPdfRuntime, configurations.runtimeClasspath, tasks.jar)
    from(rootProject.file("deployment/pdf-runtime/licenses/verapdf")) { into("licenses") }
    into(layout.buildDirectory.dir("pdfa-validation"))
}
tasks.test {
    dependsOn(prepareOwnEngineValidation)
    doFirst {
        systemProperty("nuaforma.pdf.validation", layout.buildDirectory.dir("pdfa-validation").get().asFile.absolutePath)
        systemProperty("nuaforma.pdf.validation.java", javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }.get().executablePath.asFile.absolutePath)
    }
}

tasks.processTestResources {
    from(rootProject.file("assets/branding/nuaforma-icon-transparent.png")) {
        into("fixtures")
    }
}

tasks.register<JavaExec>("renderFoundationLetter") {
    group = "verification"
    description = "Writes the synthetic one-page foundation PDF using test-only fonts and the approved logo."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.pdf.FoundationLetter")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(layout.buildDirectory.file("foundation/foundation-letter.pdf").get().asFile.absolutePath)
}
