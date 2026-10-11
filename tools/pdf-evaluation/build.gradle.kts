plugins { application }

repositories { mavenCentral() }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation("org.docx4j:docx4j-export-fo:17.1.0")
    runtimeOnly("org.docx4j:docx4j-JAXB-ReferenceImpl:17.1.0")
}

application {
    mainClass.set("dev.nuaforma.evaluation.FopProbe")
    applicationDefaultJvmArgs = listOf("-Xmx512m", "-Djava.awt.headless=true")
}
