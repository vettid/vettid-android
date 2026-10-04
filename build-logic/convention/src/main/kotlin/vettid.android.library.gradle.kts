// Plain Android library: core modules without UI.
plugins {
    id("com.android.library")
    id("vettid.detekt")
}

android {
    namespace = project.vettIdNamespace()
    compileSdk = VettIdBuild.COMPILE_SDK
    defaultConfig {
        minSdk = VettIdBuild.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = false
        xmlReport = false
        sarifReport = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

apply(plugin = "vettid.kotlin")

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
}
