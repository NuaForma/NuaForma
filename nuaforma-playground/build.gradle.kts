plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

description = "Development-only web Playground over the NuaForma REST service"
val playgroundSbomDirectory = layout.buildDirectory.dir("generated/sbom")
val sbomScript = rootProject.file("tools/sbom/normalize_runtime.py")
val generatePlaygroundSbom = tasks.register<Exec>("generatePlaygroundSbom") {
    dependsOn(":nuaforma-server:jar")
    val server = project(":nuaforma-server")
    val serverSbom = server.layout.buildDirectory.file("generated/sbom/META-INF/nuaforma/sbom.json")
    val serverJar = server.tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(serverSbom)
    inputs.file(serverJar)
    inputs.file(rootProject.file("tools/sbom/normalize_runtime.py"))
    outputs.dir(playgroundSbomDirectory)
    doFirst { commandLine("python3", sbomScript, serverSbom.get().asFile, serverJar.get().asFile,
        playgroundSbomDirectory.get().file("META-INF/nuaforma/playground-sbom.json").asFile, "--playground") }
}
sourceSets.main { resources.srcDir(playgroundSbomDirectory) }
tasks.named<ProcessResources>("processResources") {
    dependsOn(generatePlaygroundSbom)
    from(rootProject.file("assets/branding/nuaforma-icon-transparent.png")) {
        into("playground")
        rename { "icon.png" }
    }
}
application { mainClass.set("dev.nuaforma.playground.Main") }
dependencies { implementation(project(":nuaforma-server")) }
tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.named<Jar>("jar") { from(rootProject.file("LICENSE")) { into("META-INF") } }
distributions { main { contents {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("THIRD_PARTY.md"))
    from(rootProject.file("DEPENDENCIES.md"))
} } }
