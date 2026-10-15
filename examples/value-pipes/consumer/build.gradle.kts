plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":pipe"))
    implementation("dev.nuaforma:nuaforma-template:${providers.gradleProperty("nuaformaVersion").get()}")
}
application { mainClass.set("example.MainKt") }
tasks.named<JavaExec>("run") {
    classpath = files(tasks.jar) + configurations.runtimeClasspath.get()
}
