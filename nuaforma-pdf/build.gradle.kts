plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "Experimental format-neutral NuaForma PDF engine; single-page Latin-text foundation"

dependencies {
    implementation(libs.pdfbox)
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
