plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "NuaForma docx-pdf library scaffold"

dependencies {
    api(project(":nuaforma-contracts"))
    implementation(project(":nuaforma-docx"))
}
