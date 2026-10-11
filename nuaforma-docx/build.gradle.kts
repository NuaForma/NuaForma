plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "NuaForma docx library scaffold"

dependencies {
    api(project(":nuaforma-contracts"))
}
