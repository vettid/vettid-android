// The application module.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("vettid.detekt")
}

android {
    compileSdk = VettIdBuild.COMPILE_SDK
    defaultConfig {
        minSdk = VettIdBuild.MIN_SDK
        targetSdk = VettIdBuild.TARGET_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        xmlReport = false
        sarifReport = true
    }
}

apply(plugin = "vettid.kotlin")
