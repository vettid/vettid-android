import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.vettid.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vettid.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // SECURITY: Signing credentials can be provided via:
            // 1. keystore.properties file (for local development - gitignored)
            // 2. Environment variables (for CI/CD)
            //
            // keystore.properties format:
            //   storeFile=keystore/release.keystore
            //   storePassword=xxx
            //   keyAlias=vettid-release
            //   keyPassword=xxx
            //
            // Environment variables:
            //   VETTID_KEYSTORE_PATH, VETTID_KEYSTORE_PASSWORD,
            //   VETTID_KEY_ALIAS, VETTID_KEY_PASSWORD

            // Try keystore.properties first (local development)
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))

                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            } else {
                // Fall back to environment variables (CI/CD)
                val keystorePath = System.getenv("VETTID_KEYSTORE_PATH")
                val keystorePassword = System.getenv("VETTID_KEYSTORE_PASSWORD")
                val keyAliasValue = System.getenv("VETTID_KEY_ALIAS")
                val keyPasswordValue = System.getenv("VETTID_KEY_PASSWORD")

                if (keystorePath != null && keystorePassword != null &&
                    keyAliasValue != null && keyPasswordValue != null) {
                    storeFile = file(keystorePath)
                    storePassword = keystorePassword
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                } else {
                    // No signing config available
                    println("WARNING: Release signing credentials not configured. " +
                            "Create keystore.properties or set environment variables.")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Don't include debug info in release builds
            ndk {
                debugSymbolLevel = "NONE"
            }
            // Use release signing if configured, otherwise use debug signing for development builds
            val releaseSigningConfig = signingConfigs.findByName("release")
            signingConfig = if (releaseSigningConfig?.storeFile != null) {
                releaseSigningConfig
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            // Enable minification in debug for testing ProGuard rules
            // isMinifyEnabled = true
            // proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("production") {
            dimension = "environment"
            // Production settings - real attestation, real biometrics
            buildConfigField("Boolean", "SKIP_ATTESTATION", "false")
            buildConfigField("Boolean", "AUTO_BIOMETRIC", "false")
            buildConfigField("Boolean", "TEST_MODE", "false")
            buildConfigField("String", "API_BASE_URL", "\"https://api.vettid.dev\"")
            buildConfigField("String", "PCR_MANIFEST_URL", "\"https://pcr-manifest.vettid.dev/pcr-manifest.json\"")
        }
        create("automation") {
            dimension = "environment"
            applicationIdSuffix = ".automation"
            versionNameSuffix = "-automation"
            // Automation settings - mock attestation, auto-bypass biometrics
            buildConfigField("Boolean", "SKIP_ATTESTATION", "true")
            buildConfigField("Boolean", "AUTO_BIOMETRIC", "true")
            buildConfigField("Boolean", "TEST_MODE", "true")
            buildConfigField("String", "API_BASE_URL", "\"https://api.vettid.dev\"")
            buildConfigField("String", "PCR_MANIFEST_URL", "\"https://pcr-manifest.vettid.dev/pcr-manifest.json\"")
            buildConfigField("String", "TEST_API_KEY", "\"\"")  // Set via environment or test-config.json
        }
    }

    // Enable build config fields
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val flavor = variant.flavorName
            val buildType = variant.buildType.name
            output.outputFileName = "vettid-app-${flavor}-${buildType}.apk"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.5")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")  // Drag-and-drop reordering

    // Security / Crypto
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.crypto.tink:tink-android:1.12.0")  // X25519, ChaCha20-Poly1305
    implementation("org.signal:argon2:13.1")  // Argon2id password hashing

    // CBOR/COSE for AWS Nitro Enclave attestation verification
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.16.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")  // Certificate chain verification
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")  // COSE signature verification

    // Biometrics
    implementation("androidx.biometric:biometric:1.1.0")

    // Camera / QR scanning
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("com.google.zxing:core:3.5.2")  // QR code generation

    // Image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // NATS
    implementation("io.nats:jnats:2.17.6")

    // WebRTC for voice/video calling
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Dependency Injection
    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
