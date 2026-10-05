import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("vettid.android.application")
    id("vettid.hilt")
}

// Signing for the `staging` build type: a properties file OUTSIDE the repository
// (Gradle property `vettidStagingSigning`, default ~/.vettid/staging-signing.properties)
// with storeFile, storePassword, keyAlias, keyPassword. The passwords may instead come
// from VETTID_STAGING_STORE_PASSWORD / VETTID_STAGING_KEY_PASSWORD. Without the file
// the staging APK is built unsigned (CI), and there is no installStaging task.
val stagingSigningFile: File = file(
    (providers.gradleProperty("vettidStagingSigning").orNull ?: "~/.vettid/staging-signing.properties")
        .replaceFirst(Regex("^~(?=/)"), Regex.escapeReplacement(System.getProperty("user.home"))),
)
val stagingSigning: Properties? = providers.fileContents(objects.fileProperty().fileValue(stagingSigningFile)).asText.orNull
    ?.let { text -> Properties().apply { load(text.reader()) } }

android {
    namespace = "com.vettid.app"

    defaultConfig {
        // Same application id as the v1 app (keeps the Play listing and signing key, D1).
        applicationId = "com.vettid.app"
        // v1 ended at versionCode 7 / 1.0.55; the rewrite starts at 100 / 2.0.0.
        versionCode = 100
        versionName = "2.0.0-a0"
    }

    signingConfigs {
        if (stagingSigning != null) {
            create("staging") {
                fun value(key: String, env: String? = null): String =
                    stagingSigning.getProperty(key)?.takeIf { it.isNotBlank() }
                        ?: env?.let { providers.environmentVariable(it).orNull }
                        ?: error("$stagingSigningFile: '$key' is missing" + (env?.let { " (or set $it)" } ?: ""))
                storeFile = stagingSigningFile.parentFile.resolve(value("storeFile"))
                storePassword = value("storePassword", "VETTID_STAGING_STORE_PASSWORD")
                keyAlias = value("keyAlias")
                keyPassword = value("keyPassword", "VETTID_STAGING_KEY_PASSWORD")
            }
        }
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
        // STAGING: a release-like build (R8, not debuggable, no debug tools, no
        // :core:testing) that talks to the staging vault service (src/staging). Same
        // application id as release: staging images pin the package com.vettid.app and
        // accept only the staging signing key, so it replaces a release install on a
        // test phone and cannot sit next to one.
        create("staging") {
            initWith(getByName("release"))
            versionNameSuffix = "-staging"
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.findByName("staging")
        }
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    // The debug screens (component gallery, screenshot launches) serve both debug build types.
    // The on-phone exit test runs against the devStack build (devstack/README.md).
    testBuildType = providers.gradleProperty("vettidTestBuildType").orNull ?: "debug"

    sourceSets {
        getByName("debug").kotlin.srcDir("src/debugTools/kotlin")
        getByName("devStack").kotlin.srcDir("src/debugTools/kotlin")
        getByName("release").kotlin.srcDir("src/noDebugTools/kotlin")
        getByName("staging").kotlin.srcDir("src/noDebugTools/kotlin")
    }
}

/**
 * Fails if a release-like APK's dex or resources carry another environment's
 * endpoints or pins, or dev-stack / debug-tools code, or miss their own.
 * `checkReleaseApk` and `checkStagingApk` run in CI after the R8 builds.
 */
abstract class ApkContentCheck : DefaultTask() {
    @get:InputFiles
    abstract val apkDir: DirectoryProperty

    @get:Input
    abstract val forbidden: ListProperty<String>

    @get:Input
    abstract val required: ListProperty<String>

    @TaskAction
    fun check() {
        val apks = apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
        require(apks.isNotEmpty()) { "no APK in ${apkDir.get().asFile}" }
        for (apk in apks) {
            val text = ZipFile(apk).use { z ->
                z.entries().asSequence().filter { it.name.endsWith(".dex") || it.name == "resources.arsc" }
                    .joinToString("\u0000") { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.ISO_8859_1) } }
            }
            val found = forbidden.get().filter { text.contains(it) }
            val missing = required.get().filterNot { text.contains(it) }
            check(found.isEmpty() && missing.isEmpty()) { "${apk.name}: forbidden $found, missing $missing" }
            logger.lifecycle("${apk.name}: ${forbidden.get().size} forbidden strings absent, ${required.get().size} required present")
        }
    }
}

val productionPins = listOf(
    "https://account.vettid.org",
    "https://vettid.org/.well-known/vettid/pcr-manifest.json",
    // ManifestKeys.KEY_A_SPKI_B64 and KEY_B_SPKI_B64 (prefix past the common P-256 SPKI header)
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7xDU6CSVsFDJvP7UXigsN9SDB",
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIcIodW3liYaxACuOdB0If8igq",
)
val stagingPins = listOf(
    "staging.vettid.org",
    // ManifestKeys.STAGING_KEY_A_SPKI_B64
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEok8gqeC5VcGx4KL",
)
// The dev stack's addresses and the debug tools' launch extras and gallery.
val devOnly = listOf("127.0.0.1", "relay.vettid.test", "vettid.start", "vettid.screenshot", "Component gallery")

androidComponents {
    onVariants(selector().withBuildType("release")) { v ->
        tasks.register<ApkContentCheck>("checkReleaseApk") {
            apkDir.set(v.artifacts.get(SingleArtifact.APK))
            forbidden.set(stagingPins + devOnly)
            required.set(productionPins)
        }
    }
    onVariants(selector().withBuildType("staging")) { v ->
        tasks.register<ApkContentCheck>("checkStagingApk") {
            apkDir.set(v.artifacts.get(SingleArtifact.APK))
            forbidden.set(productionPins + devOnly)
            required.set(stagingPins)
        }
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
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
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

    // The A3 exit test (devStack build, on the phone against the local dev stack) and Compose UI tests.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Espresso 3.7 for API 37 (the version Compose's test library pulls in calls a removed InputManager method).
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
