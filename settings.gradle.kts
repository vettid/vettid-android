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
include(":core:notify")
// Google push (Firebase): the `full` distribution only. An F-Droid build removes the directory before building
// (docs/fdroid/com.vettid.app.yml, `rm`), so that no Google coordinate is left in the tree it builds from; the `foss`
// distribution never depends on the module (README "F-Droid build").
if (file("core/push-fcm").isDirectory) include(":core:push-fcm")

include(":feature:onboarding")
include(":feature:messages")
include(":feature:connections")
include(":feature:approvals")
include(":feature:items")
include(":feature:history")
include(":feature:credential")
include(":feature:settings")
include(":feature:notifications")
