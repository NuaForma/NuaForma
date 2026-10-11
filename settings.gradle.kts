pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "nuaforma"

include(
    "nuaforma-contracts",
    "nuaforma-core",
    "nuaforma-template",
    "nuaforma-docx",
    "nuaforma-docx-pdf",
    "nuaforma-server",
)
