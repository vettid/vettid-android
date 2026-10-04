// Android library with Jetpack Compose.
plugins {
    id("vettid.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    buildFeatures {
        compose = true
    }
}

dependencies {
    val bom = platform(libs.findLibrary("androidx-compose-bom").get())
    "implementation"(bom)
    "implementation"(libs.findLibrary("androidx-compose-ui").get())
    "implementation"(libs.findLibrary("androidx-compose-foundation").get())
    "implementation"(libs.findLibrary("androidx-compose-material3").get())
    "implementation"(libs.findLibrary("androidx-compose-material-icons-extended").get())
    "implementation"(libs.findLibrary("androidx-compose-ui-tooling-preview").get())
    "debugImplementation"(libs.findLibrary("androidx-compose-ui-tooling").get())
}
