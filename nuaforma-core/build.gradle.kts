plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "NuaForma format-independent provider discovery and orchestration"

dependencies {
    api(project(":nuaforma-contracts"))
}
