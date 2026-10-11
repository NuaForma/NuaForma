plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "NuaForma DOCX facade and docx4j adapter"

dependencies {
    api(project(":nuaforma-contracts"))
    implementation(libs.docx4j.core)
    runtimeOnly(libs.docx4j.jaxb)
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("THIRD_PARTY.md")) { into("META-INF") }
}
