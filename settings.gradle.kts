pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VettID"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:ui")
include(":core:crypto")
include(":core:keystore")
include(":core:attestation")
include(":core:relay")
include(":core:altchan")
include(":core:vault")
include(":core:data")
include(":core:testing")

include(":feature:onboarding")
include(":feature:messages")
include(":feature:connections")
include(":feature:approvals")
include(":feature:items")
include(":feature:history")
include(":feature:credential")
include(":feature:settings")
include(":feature:notifications")
