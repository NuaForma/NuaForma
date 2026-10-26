plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "NuaForma DOCX/PDF provider using the shared native engine and isolated output validation"

tasks.withType<Jar>().configureEach { includeEmptyDirs = false }

tasks.register<JavaExec>("qualifyOwnEngineProfiles") {
    group = "verification"
    description = "Qualifies explicit trusted DOCX cases against the four own-engine alpha output profiles."
    dependsOn(tasks.testClasses, ":nuaforma-pdf:prepareOwnEngineValidation")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProfileProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    doFirst {
        args(providers.gradleProperty("ownEngineProfileCases").get(),
            rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath,
            project(":nuaforma-pdf").layout.buildDirectory.dir("pdfa-validation").get().asFile.absolutePath,
            javaLauncher.get().executablePath.asFile.absolutePath,
            providers.gradleProperty("ownEngineProfileOutput").get())
    }
}

dependencies {
    api(project(":nuaforma-contracts"))
    api(project(":nuaforma-template"))
    implementation(project(":nuaforma-docx"))
    implementation(libs.pdfbox)
    api(project(":nuaforma-pdf"))
}

tasks.test {
    dependsOn(":nuaforma-pdf:prepareOwnEngineValidation")
    systemProperty("java.awt.headless", "true")
    doFirst {
        systemProperty("nuaforma.pdf.validation", project(":nuaforma-pdf").layout.buildDirectory.dir("pdfa-validation").get().asFile.absolutePath)
        systemProperty("nuaforma.pdf.validation.java", javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }.get().executablePath.asFile.absolutePath)
    }
}

tasks.register<JavaExec>("renderNativeDocxProof") {
    group = "verification"
    description = "Runs the registered native provider on explicit trusted inputs; never overwrites reference PDFs."
    dependsOn(tasks.testClasses, ":nuaforma-pdf:prepareOwnEngineValidation")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.NativeDocxProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
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
    from(rootProject.file("tools/container/fixtures/image-jpeg.base64")) { into("native-profile") }
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

val generateOwnEngineInternational = tasks.register<Exec>("generateOwnEngineInternational") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.file("own-engine-international/source.docx")
    inputs.files(folder.resolve("build_international_fixture.py"), folder.resolve("build_report_fixture.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.file(output)
    commandLine("python3", folder.resolve("build_international_fixture.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineInternational)
    from(layout.buildDirectory.dir("own-engine-international")) { into("own-engine-international") }
}
tasks.register<JavaExec>("renderOwnEngineInternational") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(rootProject.layout.buildDirectory.file("own-engine-international/source.docx").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.file("own-engine-international/own-engine.pdf").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
}

val generateOwnEnginePageMasters = tasks.register<Exec>("generateOwnEnginePageMasters") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.dir("own-engine-page-masters")
    inputs.files(listOf("build_page_master_fixtures.py", "build_sections_fixture.py", "build_report_fixture.py", "build_fixture.py").map { folder.resolve(it) }, rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.dir(output)
    commandLine("python3", folder.resolve("build_page_master_fixtures.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEnginePageMasters)
    from(layout.buildDirectory.dir("own-engine-page-masters")) { into("own-engine-page-masters") }
}
val pageMasterRenders = listOf("variants", "blank").map { kind ->
    tasks.register<JavaExec>("renderOwnEnginePageMaster${kind.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(rootProject.layout.buildDirectory.file("own-engine-page-masters/$kind/source.docx").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.file("own-engine-page-masters/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
    }
}
tasks.register("renderOwnEnginePageMasters") { group = "verification"; dependsOn(pageMasterRenders) }

val generateOwnEngineNumbering = tasks.register<Exec>("generateOwnEngineNumbering") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.dir("own-engine-numbering")
    inputs.files(listOf("build_numbering_fixtures.py", "build_page_master_fixtures.py", "build_sections_fixture.py", "build_report_fixture.py", "build_fixture.py").map { folder.resolve(it) }, rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.dir(output)
    commandLine("python3", folder.resolve("build_numbering_fixtures.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineNumbering)
    from(layout.buildDirectory.dir("own-engine-numbering")) { into("own-engine-numbering") }
}
val numberingRenders = listOf("restart", "offset", "continue", "shared").map { kind ->
    tasks.register<JavaExec>("renderOwnEngineNumbering${kind.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(rootProject.layout.buildDirectory.file("own-engine-numbering/$kind/source.docx").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.file("own-engine-numbering/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
    }
}
tasks.register("renderOwnEngineNumbering") { group = "verification"; dependsOn(numberingRenders) }

val generateOwnEngineCellAlignment = tasks.register<Exec>("generateOwnEngineCellAlignment") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.dir("own-engine-cell-alignment")
    inputs.files(listOf("build_cell_alignment_fixtures.py", "build_advanced_fixtures.py", "build_report_fixture.py", "build_fixture.py").map { folder.resolve(it) }, rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.dir(output)
    commandLine("python3", folder.resolve("build_cell_alignment_fixtures.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineCellAlignment)
    from(layout.buildDirectory.dir("own-engine-cell-alignment")) { into("own-engine-cell-alignment") }
}
val cellAlignmentRenders = listOf("ordinary", "merged", "nested").map { kind ->
    tasks.register<JavaExec>("renderOwnEngineCellAlignment${kind.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(rootProject.layout.buildDirectory.file("own-engine-cell-alignment/$kind/source.docx").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.file("own-engine-cell-alignment/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
    }
}
tasks.register("renderOwnEngineCellAlignment") { group = "verification"; dependsOn(cellAlignmentRenders) }

val generateOwnEngineCellBorders = tasks.register<Exec>("generateOwnEngineCellBorders") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.dir("own-engine-cell-borders")
    inputs.files(listOf("build_border_fixtures.py", "build_advanced_fixtures.py", "build_report_fixture.py", "build_fixture.py").map { folder.resolve(it) }, rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.dir(output)
    commandLine("python3", folder.resolve("build_border_fixtures.py").absolutePath, output.get().asFile.absolutePath)
}
val generateOwnEngineBorderConflicts = tasks.register<Exec>("generateOwnEngineBorderConflicts") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.dir("own-engine-border-conflicts")
    inputs.files(listOf("build_border_fixtures.py", "build_advanced_fixtures.py", "build_report_fixture.py", "build_fixture.py").map { folder.resolve(it) }, rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.dir(output)
    commandLine("python3", folder.resolve("build_border_fixtures.py").absolutePath, output.get().asFile.absolutePath, "--conflicting")
}
tasks.processTestResources {
    dependsOn(generateOwnEngineCellBorders, generateOwnEngineBorderConflicts)
    from(layout.buildDirectory.dir("own-engine-cell-borders")) { into("own-engine-cell-borders") }
    from(layout.buildDirectory.dir("own-engine-border-conflicts")) { into("own-engine-border-conflicts") }
}
val cellBorderRenders = listOf("colors", "weights", "outline").map { kind ->
    tasks.register<JavaExec>("renderOwnEngineCellBorders${kind.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(rootProject.layout.buildDirectory.file("own-engine-cell-borders/consistent/$kind/source.docx").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.file("own-engine-cell-borders/consistent/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
    }
}
tasks.register("renderOwnEngineCellBorders") { group = "verification"; dependsOn(cellBorderRenders) }

val generateOwnEngineBorderCombinations = tasks.register<Exec>("generateOwnEngineBorderCombinations") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.dir("own-engine-border-combinations")
    inputs.files(listOf("build_border_combination_fixtures.py", "build_page_format_fixtures.py", "build_advanced_fixtures.py", "build_report_fixture.py", "build_fixture.py").map { folder.resolve(it) }, rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.dir(output)
    commandLine("python3", folder.resolve("build_border_combination_fixtures.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineBorderCombinations)
    from(layout.buildDirectory.dir("own-engine-border-combinations")) { into("own-engine-border-combinations") }
}
val borderCombinationRenders = listOf("merged", "nested", "pages", "letter").map { kind ->
    tasks.register<JavaExec>("renderOwnEngineBorder${kind.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(rootProject.layout.buildDirectory.file("own-engine-border-combinations/$kind/source.docx").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.file("own-engine-border-combinations/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
    }
}
tasks.register("renderOwnEngineBorderCombinations") { group = "verification"; dependsOn(borderCombinationRenders) }

val generateOwnEngineBorderFragmentation = tasks.register<Exec>("generateOwnEngineBorderFragmentation") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.dir("own-engine-border-fragmentation")
    inputs.files(listOf("build_border_fragmentation_fixtures.py", "build_advanced_fixtures.py", "build_report_fixture.py", "build_fixture.py").map { folder.resolve(it) }, rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.dir(output)
    commandLine("python3", folder.resolve("build_border_fragmentation_fixtures.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineBorderFragmentation)
    from(layout.buildDirectory.dir("own-engine-border-fragmentation")) { into("own-engine-border-fragmentation") }
}
val borderFragmentationRenders = listOf("a4", "letter").map { kind ->
    tasks.register<JavaExec>("renderOwnEngineBorderFragmentation${kind.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(rootProject.layout.buildDirectory.file("own-engine-border-fragmentation/$kind/source.docx").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.file("own-engine-border-fragmentation/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
    }
}
tasks.register("renderOwnEngineBorderFragmentation") { group = "verification"; dependsOn(borderFragmentationRenders) }

val anchoredImageKinds = listOf("left", "right", "largest", "top-bottom", "page", "boundary", "letter", "crop", "both-sides", "multipage")
val generateOwnEngineNavigation = tasks.register<Exec>("generateOwnEngineNavigation") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.dir("own-engine-navigation")
    inputs.files(folder.resolve("build_navigation_fixtures.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.dir(output)
    commandLine("python3", folder.resolve("build_navigation_fixtures.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineNavigation)
    from(layout.buildDirectory.dir("own-engine-navigation")) { into("own-engine-navigation") }
}
val navigationRenders = listOf("a4", "letter", "table", "multipage").map { kind ->
    tasks.register<JavaExec>("renderOwnEngineNavigation${kind.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(layout.buildDirectory.file("own-engine-navigation/$kind/source.docx").get().asFile.absolutePath,
            layout.buildDirectory.file("own-engine-navigation/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.file("nuaforma-pdf/src/test/resources/fonts").absolutePath)
    }
}
tasks.register("renderOwnEngineNavigation") { group = "verification"; dependsOn(navigationRenders) }

val generateOwnEngineAnchoredImages = tasks.register<Exec>("generateOwnEngineAnchoredImages") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.dir("own-engine-anchored-images")
    inputs.files(listOf("build_anchored_image_fixtures.py", "build_fixture.py").map { folder.resolve(it) }, rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.dir(output)
    commandLine("python3", folder.resolve("build_anchored_image_fixtures.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineAnchoredImages)
    from(layout.buildDirectory.dir("own-engine-anchored-images")) { into("own-engine-anchored-images") }
}
val anchoredImageRenders = anchoredImageKinds.map { kind ->
    tasks.register<JavaExec>("renderOwnEngineAnchoredImages${kind.replace("-", "").replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(layout.buildDirectory.file("own-engine-anchored-images/$kind/source.docx").get().asFile.absolutePath,
            layout.buildDirectory.file("own-engine-anchored-images/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.file("nuaforma-pdf/src/test/resources/fonts").absolutePath)
    }
}
tasks.register("renderOwnEngineAnchoredImages") { group = "verification"; dependsOn(anchoredImageRenders) }

val generateOwnEngineNaturalWidths = tasks.register<Exec>("generateOwnEngineNaturalWidths") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.dir("own-engine-natural-widths")
    inputs.files(listOf("build_natural_width_fixtures.py", "build_advanced_fixtures.py", "build_report_fixture.py", "build_fixture.py").map { folder.resolve(it) }, rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.dir(output)
    commandLine("python3", folder.resolve("build_natural_width_fixtures.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineNaturalWidths)
    from(layout.buildDirectory.dir("own-engine-natural-widths")) { into("own-engine-natural-widths") }
}
val naturalWidthRenders = listOf("a4", "letter").map { kind ->
    tasks.register<JavaExec>("renderOwnEngineNaturalWidths${kind.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(rootProject.layout.buildDirectory.file("own-engine-natural-widths/$kind/source.docx").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.file("own-engine-natural-widths/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
    }
}
tasks.register("renderOwnEngineNaturalWidths") { group = "verification"; dependsOn(naturalWidthRenders) }

val generateOwnEngineFields = tasks.register<Exec>("generateOwnEngineFields") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.file("own-engine-fields/source.docx")
    inputs.files(listOf("build_fields_fixture.py", "build_page_master_fixtures.py", "build_sections_fixture.py", "build_report_fixture.py", "build_fixture.py").map { folder.resolve(it) }, rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.file(output)
    commandLine("python3", folder.resolve("build_fields_fixture.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineFields)
    from(layout.buildDirectory.dir("own-engine-fields")) { into("own-engine-fields") }
}
tasks.register<JavaExec>("renderOwnEngineFields") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(rootProject.layout.buildDirectory.file("own-engine-fields/source.docx").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.file("own-engine-fields/own-engine.pdf").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
}

val generateOwnEngineSections = tasks.register<Exec>("generateOwnEngineSections") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.file("own-engine-sections/source.docx")
    inputs.files(folder.resolve("build_sections_fixture.py"), folder.resolve("build_report_fixture.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.file(output)
    commandLine("python3", folder.resolve("build_sections_fixture.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineSections)
    from(layout.buildDirectory.dir("own-engine-sections")) { into("own-engine-sections") }
}
tasks.register<JavaExec>("renderOwnEngineSections") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(rootProject.layout.buildDirectory.file("own-engine-sections/source.docx").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.file("own-engine-sections/own-engine.pdf").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
}

val generateOwnEnginePageFormats = tasks.register<Exec>("generateOwnEnginePageFormats") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.dir("own-engine-page-formats")
    inputs.files(folder.resolve("build_page_format_fixtures.py"), folder.resolve("build_report_fixture.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.dir(output)
    commandLine("python3", folder.resolve("build_page_format_fixtures.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEnginePageFormats)
    from(layout.buildDirectory.dir("own-engine-page-formats")) { into("own-engine-page-formats") }
}
val pageFormatRenders = listOf("a4", "a3", "letter", "legal").flatMap { size -> listOf("portrait", "landscape").map { "$size-$it" } }.map { kind ->
    val suffix = kind.split('-').joinToString("") { it.replaceFirstChar { character -> character.uppercase() } }
    tasks.register<JavaExec>("renderOwnEngine$suffix") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        args(rootProject.layout.buildDirectory.file("own-engine-page-formats/$kind/source.docx").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.file("own-engine-page-formats/$kind/own-engine.pdf").get().asFile.absolutePath,
            rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
    }
}
tasks.register("renderOwnEnginePageFormats") { group = "verification"; dependsOn(pageFormatRenders) }

val generateOwnEngineHebrew = tasks.register<Exec>("generateOwnEngineHebrew") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.file("own-engine-hebrew/source.docx")
    inputs.files(folder.resolve("build_hebrew_fixture.py"), folder.resolve("build_international_fixture.py"), folder.resolve("build_report_fixture.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.file(output)
    commandLine("python3", folder.resolve("build_hebrew_fixture.py").absolutePath, output.get().asFile.absolutePath)
}

tasks.processTestResources {
    dependsOn(generateOwnEngineHebrew)
    from(layout.buildDirectory.dir("own-engine-hebrew")) { into("own-engine-hebrew") }
}

tasks.register<JavaExec>("renderOwnEngineHebrew") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(rootProject.layout.buildDirectory.file("own-engine-hebrew/source.docx").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.file("own-engine-hebrew/own-engine.pdf").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
}

val generateOwnEngineStyles = tasks.register<Exec>("generateOwnEngineStyles") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.file("own-engine-styles/source.docx")
    inputs.files(folder.resolve("build_styles_fixture.py"), folder.resolve("build_report_fixture.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.file(output)
    commandLine("python3", folder.resolve("build_styles_fixture.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineStyles)
    from(layout.buildDirectory.dir("own-engine-styles")) { into("own-engine-styles") }
}
tasks.register<JavaExec>("renderOwnEngineStyles") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(rootProject.layout.buildDirectory.file("own-engine-styles/source.docx").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.file("own-engine-styles/own-engine.pdf").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
}

val generateOwnEngineTabs = tasks.register<Exec>("generateOwnEngineTabs") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.file("own-engine-tabs/source.docx")
    inputs.files(folder.resolve("build_tabs_fixture.py"), folder.resolve("build_report_fixture.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.file(output)
    commandLine("python3", folder.resolve("build_tabs_fixture.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineTabs)
    from(layout.buildDirectory.dir("own-engine-tabs")) { into("own-engine-tabs") }
}
tasks.register<JavaExec>("renderOwnEngineTabs") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(rootProject.layout.buildDirectory.file("own-engine-tabs/source.docx").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.file("own-engine-tabs/own-engine.pdf").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
}

val generateOwnEngineLists = tasks.register<Exec>("generateOwnEngineLists") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.file("own-engine-lists/source.docx")
    inputs.files(folder.resolve("build_lists_fixture.py"), folder.resolve("build_report_fixture.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.file(output)
    commandLine("python3", folder.resolve("build_lists_fixture.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEngineLists)
    from(layout.buildDirectory.dir("own-engine-lists")) { into("own-engine-lists") }
}
tasks.register<JavaExec>("renderOwnEngineLists") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(rootProject.layout.buildDirectory.file("own-engine-lists/source.docx").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.file("own-engine-lists/own-engine.pdf").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
}

val generateOwnEnginePagination = tasks.register<Exec>("generateOwnEnginePagination") {
    val folder = rootProject.file("tools/pdf-evaluation/own-engine")
    val output = layout.buildDirectory.file("own-engine-pagination/source.docx")
    inputs.files(folder.resolve("build_pagination_fixture.py"), folder.resolve("build_report_fixture.py"), folder.resolve("build_fixture.py"), rootProject.file("assets/branding/nuaforma-icon-transparent.png"))
    outputs.file(output)
    commandLine("python3", folder.resolve("build_pagination_fixture.py").absolutePath, output.get().asFile.absolutePath)
}
tasks.processTestResources {
    dependsOn(generateOwnEnginePagination)
    from(layout.buildDirectory.dir("own-engine-pagination")) { into("own-engine-pagination") }
}
tasks.register<JavaExec>("renderOwnEnginePagination") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nuaforma.docx.pdf.ownengine.OwnEngineProof")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    args(rootProject.layout.buildDirectory.file("own-engine-pagination/source.docx").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.file("own-engine-pagination/own-engine.pdf").get().asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("own-engine-proof/fonts").get().asFile.absolutePath)
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
    add(pdfValidationRuntime.name, project(":nuaforma-pdf"))
    add(pdfValidationRuntime.name, platform(libs.kotlin.bom))
}
tasks.register<Sync>("preparePdfValidation") {
    group = "verification"
    description = "Stages the bounded validator JVM without DOCX, core, server or test dependencies."
    dependsOn(":nuaforma-pdf:jar")
    from(pdfValidationRuntime)
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
