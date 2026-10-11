import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    java
}

description = "REST service scaffold"

dependencies {
    // Already present through MockK at test runtime; compile the Kotlin visibility checks explicitly.
    testImplementation(libs.kotlin.reflect)
    implementation(project(":nuaforma-core"))
    runtimeOnly(project(":nuaforma-docx"))
    runtimeOnly(project(":nuaforma-docx-pdf"))
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
