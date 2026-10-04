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
        // DEVELOPMENT ONLY: the debug app pointed at the local dev stack
        // (devstack/README.md) through `adb reverse`. A debug build type of
        // its own, so no release variant can ever carry these settings.
        create("devStack") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".devstack"
            versionNameSuffix = "-devstack"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "DEV_STACK_API", "\"http://127.0.0.1:18081\"")
            buildConfigField("String", "DEV_STACK_RELAY", "\"http://127.0.0.1:18080\"")
            buildConfigField("String", "DEV_STACK_CTL", "\"http://127.0.0.1:18082\"")
            // The member the dev stack's member API stand-in knows this install as.
            buildConfigField("String", "DEV_STACK_GUID", "\"" + (providers.gradleProperty("devStackGuid").orNull ?: "android-devstack") + "\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No signing config in the repository: release builds are signed outside it.
        }
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    // The debug screens (component gallery, screenshot launches) serve both debug build types.
    sourceSets {
        getByName("debug").kotlin.srcDir("src/debugTools/kotlin")
        getByName("devStack").kotlin.srcDir("src/debugTools/kotlin")
    }
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.data)
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
    "devStackImplementation"(libs.androidx.compose.ui.tooling)
    // TEST-ONLY helpers (the dev stack's control API and TEST attestation CA): devStack builds only.
    "devStackImplementation"(projects.core.testing)

    testImplementation(libs.junit)
}
