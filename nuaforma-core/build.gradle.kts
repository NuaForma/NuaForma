plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "NuaForma core library scaffold"

dependencies {
    api(project(":nuaforma-contracts"))
}
