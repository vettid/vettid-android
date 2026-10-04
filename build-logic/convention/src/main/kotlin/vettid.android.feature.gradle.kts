// Feature module: Compose UI + a type-safe navigation destination. Features
// depend only on :core:* modules (ANDROID-PLAN §5).
plugins {
    id("vettid.android.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    "implementation"(project(":core:ui"))
    "implementation"(libs.findLibrary("androidx-navigation-compose").get())
    "implementation"(libs.findLibrary("androidx-lifecycle-runtime-compose").get())
    "implementation"(libs.findLibrary("kotlinx-serialization-json").get())
}
