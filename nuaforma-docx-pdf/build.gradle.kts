plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "NuaForma DOCX/PDF provider with an optional isolated LibreOffice runtime"

dependencies {
    api(project(":nuaforma-contracts"))
    api(project(":nuaforma-template"))
    implementation(project(":nuaforma-docx"))
    implementation(libs.pdfbox)
    testImplementation(project(":nuaforma-pdf"))
}

val generateOwnEngineFixture = tasks.register<Exec>("generateOwnEngineFixture") {
    val builder = rootProject.file("tools/pdf-evaluation/own-engine/build_fixture.py")
    val logo = rootProject.file("assets/branding/nuaforma-icon-transparent.png")
    val output = layout.buildDirectory.file("own-engine-fixture/source.docx")
    inputs.files(builder, logo)
    outputs.file(output)
    commandLine("python3", builder.absolutePath, output.get().asFile.absolutePath)
}

tasks.processTestResources {
    dependsOn(generateOwnEngineFixture)
    from(layout.buildDirectory.dir("own-engine-fixture")) { into("own-engine") }
    from(rootProject.file("nuaforma-pdf/src/test/resources/fonts")) { into("own-engine-fonts") }
}

val generateOwnEngineReport = tasks.register<Exec>("generateOwnEngineReport") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.file("own-engine-report/source.docx")
    inputs.files(folder.resolve("build_report_fixture.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.file(output)
    commandLine("python3", folder.resolve("build_report_fixture.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineReport)
    from(layout.buildDirectory.dir("own-engine-report")) { into("own-engine-report") }
}

tasks.register<JavaExec>("renderOwnEngineReport") {
    group = "verification"
    description = "Converts the trusted report DOCX with tables, page bands and bounded pagination."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(rootProject.layout.buildDirectory.file("own-engine-report/source.docx").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.file("own-engine-report/own-engine.pdf").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
}

tasks.register<JavaExec>("renderOwnEngineProof") {
    group = "verification"
    description = "Converts a trusted synthetic DOCX through the experimental test adapter, without LibreOffice."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(rootProject.layout.buildDirectory.file("own-engine-proof/source.docx").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.file("own-engine-proof/own-engine.pdf").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
}

val advancedRenders = listOf("merged", "nested", "split").map { kind ->
    val suffix = kind.replaceFirstChar { it.uppercase() }
    val generate = tasks.register<Exec>("generateOwnEngine$suffix") {
        val folder = rootProject.file("tools/pdf-evaluation/own-engine")
        val output = layout.buildDirectory.file("own-engine-advanced/$kind/source.docx")
        inputs.files(folder.resolve("build_advanced_fixtures.py"), folder.resolve("build_report_fixture.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
        outputs.file(output)
        commandLine("python3", folder.resolve("build_advanced_fixtures.py").absolutePath, output.get().asFile.absolutePath, "--kind", kind)
    }
    tasks.processTestResources { dependsOn(generate) }
    tasks.register<JavaExec>("renderOwnEngine$suffix") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(rootProject.layout.buildDirectory.file("own-engine-advanced/$kind/source.docx").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.file("own-engine-advanced/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
    }
}
tasks.processTestResources { from(layout.buildDirectory.dir("own-engine-advanced")) { into("own-engine-advanced") } }
tasks.register("renderOwnEngineAdvanced") { group = "verification"; dependsOn(advancedRenders) }

val frameRenders = listOf("bands", "frames", "rotated").map { kind ->
    val suffix = kind.replaceFirstChar { it.uppercase() }
    val generate = tasks.register<Exec>("generateOwnEngine$suffix") {
        val folder = rootProject.file("tools/pdf-evaluation/own-engine")
        val output = layout.buildDirectory.file("own-engine-frames/$kind/source.docx")
        inputs.files(folder.resolve("build_frame_fixtures.py"), folder.resolve("build_report_fixture.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
        outputs.file(output)
        commandLine("python3", folder.resolve("build_frame_fixtures.py").absolutePath, output.get().asFile.absolutePath, "--kind", kind)
    }
    tasks.processTestResources { dependsOn(generate) }
    tasks.register<JavaExec>("renderOwnEngine$suffix") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(rootProject.layout.buildDirectory.file("own-engine-frames/$kind/source.docx").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.file("own-engine-frames/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
    }
}
tasks.processTestResources { from(layout.buildDirectory.dir("own-engine-frames")) { into("own-engine-frames") } }
tasks.register("renderOwnEngineDrawingProofs") { group = "verification"; dependsOn(frameRenders) }

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
