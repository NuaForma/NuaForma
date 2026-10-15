plugins {
    kotlin("jvm") version "2.4.21"
    application
}

kotlin { jvmToolchain(17) }
dependencies {
    implementation("dev.nuaforma:nuaforma-core:${providers.gradleProperty("nuaformaVersion").getOrElse("0.2.0-alpha.1")}")
    implementation("dev.nuaforma:nuaforma-docx:${providers.gradleProperty("nuaformaVersion").getOrElse("0.2.0-alpha.1")}")
}
application { mainClass.set("dev.nuaforma.examples.KotlinExampleKt") }

tasks.register("writeRuntimeInventory") {
    val files = configurations.runtimeClasspath
    inputs.files(files)
    val output = layout.buildDirectory.file("runtime-files.txt")
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(files.get().files.sortedBy { it.name }.joinToString("\n") { it.absolutePath })
        }
    }
}
