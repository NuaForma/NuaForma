plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("dev.nuaforma:nuaforma-contracts:${providers.gradleProperty("nuaformaVersion").get()}")
}
