plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

description = "NuaForma DOCX facade and docx4j adapter"

dependencies {
    api(project(":nuaforma-contracts"))
    api(project(":nuaforma-template"))
    implementation(libs.docx4j.core)
    runtimeOnly(libs.docx4j.jaxb)
    // Direct runtime publication preserves reviewed transitive upgrades for Maven consumers too.
    implementation(libs.commons.codec)
    implementation(libs.commons.collections)
    implementation(libs.commons.lang)
    implementation(libs.istack)
    implementation(libs.jaxb.plugins)
    implementation(libs.checker.qual)
    implementation(libs.error.prone.annotations)
    implementation(libs.slf4j.jcl)
    implementation(libs.slf4j.api)
    implementation(libs.xmlgraphics) { exclude(group = "commons-logging", module = "commons-logging") }
    implementation(libs.rhino)
}
