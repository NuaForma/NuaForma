pluginManagement { repositories { gradlePluginPortal() } }
rootProject.name = "nuaforma-independent-kotlin-example"
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(providers.gradleProperty("nuaformaRepository").get()) } }
            filter { includeGroup("dev.nuaforma") }
        }
        mavenCentral()
    }
}
