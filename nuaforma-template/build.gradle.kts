plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "NuaForma restricted format-independent template language"

dependencies {
    api(project(":nuaforma-contracts"))
}
