plugins {
    id("vettid.android.application")
    id("vettid.hilt")
}

android {
    namespace = "com.vettid.app"

    defaultConfig {
        // Same application id as the v1 app (keeps the Play listing and signing key, D1).
        applicationId = "com.vettid.app"
        // v1 ended at versionCode 7 / 1.0.55; the rewrite starts at 100 / 2.0.0.
        versionCode = 100
        versionName = "2.0.0-a0"
    }

    buildTypes {
        debug {
            // Never clobbers an installed release build.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No signing config in the repository: release builds are signed outside it.
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.feature.messages)
    implementation(projects.feature.connections)
    implementation(projects.feature.approvals)
    implementation(projects.feature.items)
    implementation(projects.feature.credential)
    implementation(projects.feature.settings)
    implementation(projects.feature.onboarding)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
