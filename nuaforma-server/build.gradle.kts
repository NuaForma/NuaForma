plugins {
    alias(libs.plugins.kotlin.jvm)
    java
}

description = "REST service scaffold"

dependencies {
    implementation(project(":nuaforma-core"))
    runtimeOnly(project(":nuaforma-docx"))
    runtimeOnly(project(":nuaforma-docx-pdf"))
}
