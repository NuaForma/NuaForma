plugins { java }
group = "example"
version = "1.0.0"
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17); options.encoding = "UTF-8" }
dependencies {
    compileOnly("dev.nuaforma:nuaforma-contracts:0.3.0-alpha.2")
    testImplementation("dev.nuaforma:nuaforma-template:0.3.0-alpha.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)
    // Match the operator's real JAR installation rather than split classes/resources directories.
    classpath = files(sourceSets.test.get().output, configurations.testRuntimeClasspath, tasks.jar.flatMap { it.archiveFile })
}
tasks.jar { from("LICENSE") { into("META-INF") } }
