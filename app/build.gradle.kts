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

// Google push (ANDROID-PLAN 0.1.30, Notification modes 4; README "Google push"): the Firebase options of a build type,
// from the Gradle properties vettidFcm<BuildType>{ApplicationId,ProjectId,ApiKey,SenderId} (no google-services.json,
// no Google Services plugin). All four or none: without them the build has no Google push ("Not available yet") and
// Firebase's components stay disabled in its manifest. Each environment has its own Firebase project.
val fcmOptionNames = listOf("ApplicationId", "ProjectId", "ApiKey", "SenderId")

fun fcmOptions(buildType: String): Map<String, String>? {
    val prefix = "vettidFcm" + buildType.replaceFirstChar { it.uppercase() }
    val values = fcmOptionNames.associateWith { providers.gradleProperty(prefix + it).orNull?.trim().orEmpty() }
    val missing = values.filterValues { it.isEmpty() }.keys
    if (missing.size == fcmOptionNames.size) return null
    require(missing.isEmpty()) { "Google push for '$buildType': set all four of $prefix{${fcmOptionNames.joinToString(",")}} or none (missing: $missing)" }
    values.forEach { (k, v) -> require(Regex("[A-Za-z0-9:._-]+").matches(v)) { "$prefix$k: unexpected characters" } }
    return values
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

    buildTypes.configureEach {
        val fcm = fcmOptions(name)
        buildConfigField("String", "FCM_APPLICATION_ID", "\"${fcm?.get("ApplicationId").orEmpty()}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${fcm?.get("ProjectId").orEmpty()}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${fcm?.get("ApiKey").orEmpty()}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${fcm?.get("SenderId").orEmpty()}\"")
        // Firebase's receiver and the messaging services exist in the manifest of every build; they are enabled
        // only where the options are (AndroidManifest.xml).
        manifestPlaceholders["vettidFcmEnabled"] = (fcm != null).toString()
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    // The debug screens (component gallery, screenshot launches) serve both debug build types.
    // The on-phone exit test runs against the devStack build (devstack/README.md).
    testBuildType = providers.gradleProperty("vettidTestBuildType").orNull ?: "debug"

    testOptions.unitTests {
        isIncludeAndroidResources = true
        // Robolectric's API 36 runtime reaches into the JDK's file descriptor internals.
        all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }

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

/**
 * Google push adds Firebase Cloud Messaging and nothing else of Firebase or Google (ANDROID-PLAN 0.1.30, Notification
 * modes 4). Fails if a build's merged manifest carries a Firebase start-up provider, WorkManager's start-up
 * initialiser, a Google permission other than the one that receives pushes, an advertising-id permission, a Google
 * component or Firebase registrar that Messaging does not need (Analytics, Crashlytics, …), automatic token creation
 * or metrics export left on, or push components enabled in a build without the Firebase options (or disabled in one
 * with them). `checkReleaseApk` and `checkStagingApk` run it for their builds.
 */
abstract class PushManifestCheck : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    /** Whether this build carries the Firebase options. */
    @get:Input
    abstract val pushConfigured: Property<Boolean>

    @TaskAction
    fun check() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)

        fun all(tag: String) = doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }

        fun org.w3c.dom.Element.a(name: String) = getAttributeNS(ns, name)
        val problems = mutableListOf<String>()
        val permissions = all("uses-permission").map { it.a("name") }
        problems += permissions.filter { it.startsWith("com.google.") && it != "com.google.android.c2dm.permission.RECEIVE" }
            .map { "permission $it" }
        problems += permissions.filter { it.contains("AD_ID") || it.contains("ADSERVICES") }.map { "permission $it" }
        val components = listOf("activity", "service", "receiver", "provider").flatMap { all(it) }
        problems += components.map { it.a("name") }.filter { it.startsWith("com.google.") && it !in GOOGLE_COMPONENTS }.map { "component $it" }
        val meta = all("meta-data").associate { it.a("name") to it.a("value") }
        problems += meta.keys.filter { it.startsWith("com.google.firebase.components:") && it.substringAfter(':') !in REGISTRARS }
            .map { "Firebase registrar ${it.substringAfter(':')}" }
        if ("androidx.work.WorkManagerInitializer" in meta) problems += "WorkManager is initialised at every start"
        for (flag in OFF_FLAGS) if (meta[flag] != "false") problems += "$flag is not false"
        val expected = pushConfigured.get().toString()
        for (name in PUSH_COMPONENTS) {
            val c = components.singleOrNull { it.a("name") == name }
            if (c == null) problems += "$name is missing" else if (c.a("enabled") != expected) problems += "$name enabled=${c.a("enabled")}, expected $expected"
        }
        check(problems.isEmpty()) { "${manifest.get().asFile}: $problems" }
        logger.lifecycle("merged manifest: Firebase Messaging only, push components enabled=$expected")
    }

    companion object {
        /** Enabled only in a build with the Firebase options. */
        val PUSH_COMPONENTS = listOf(
            "com.vettid.core.push.fcm.VettIdMessagingService",
            "com.google.firebase.messaging.FirebaseMessagingService",
            "com.google.firebase.iid.FirebaseInstanceIdReceiver",
        )

        /** What firebase-messaging and its own dependencies declare (no FirebaseInitProvider: removed). */
        val GOOGLE_COMPONENTS = PUSH_COMPONENTS + listOf(
            "com.google.firebase.components.ComponentDiscoveryService",
            "com.google.android.gms.common.api.GoogleApiActivity",
            "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
        )
        val REGISTRARS = listOf(
            "com.google.firebase.messaging.FirebaseMessagingRegistrar",
            "com.google.firebase.messaging.FirebaseMessagingKtxRegistrar",
            "com.google.firebase.installations.FirebaseInstallationsRegistrar",
            "com.google.firebase.installations.FirebaseInstallationsKtxRegistrar",
            "com.google.firebase.FirebaseCommonKtxRegistrar",
            "com.google.firebase.datatransport.TransportRegistrar",
        )
        val OFF_FLAGS = listOf(
            "firebase_messaging_auto_init_enabled",
            "firebase_analytics_collection_enabled",
            "delivery_metrics_exported_to_big_query_enabled",
        )
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
    onVariants { v ->
        tasks.register<PushManifestCheck>("check${v.name.replaceFirstChar { it.uppercase() }}PushManifest") {
            manifest.set(v.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            pushConfigured.set(fcmOptions(v.buildType ?: v.name) != null)
        }
    }
    onVariants(selector().withBuildType("release")) { v ->
        tasks.register<ApkContentCheck>("checkReleaseApk") {
            dependsOn("checkReleasePushManifest")
            apkDir.set(v.artifacts.get(SingleArtifact.APK))
            // Each environment has its own Firebase project (PUSH-GATEWAY §10.4): never the other one's app.
            forbidden.set(stagingPins + devOnly + listOfNotNull(fcmOptions("staging")?.get("ApplicationId")))
            required.set(productionPins)
        }
    }
    onVariants(selector().withBuildType("staging")) { v ->
        tasks.register<ApkContentCheck>("checkStagingApk") {
            dependsOn("checkStagingPushManifest")
            apkDir.set(v.artifacts.get(SingleArtifact.APK))
            forbidden.set(productionPins + devOnly + listOfNotNull(fcmOptions("release")?.get("ApplicationId")))
            required.set(stagingPins)
        }
    }
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.core.notify)
    implementation(projects.core.pushFcm)
    // Configuration.Provider only (VettIdApplication): WorkManager itself is used by :core:push-fcm.
    implementation(libs.androidx.work.runtime)
    implementation(projects.feature.messages)
    implementation(projects.feature.connections)
    implementation(projects.feature.approvals)
    implementation(projects.feature.items)
    implementation(projects.feature.history)
    implementation(projects.feature.credential)
    implementation(projects.feature.settings)
    implementation(projects.feature.notifications)
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
    testImplementation(libs.kotlinx.coroutines.test)
    // TagColorsViewModelTest: the in-memory tags and owner check (TEST ONLY).
    testImplementation(projects.core.testing)
    // The account sheet rendered under Robolectric (as :core:ui's FormScaffoldHeaderTest).
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)

    // The A3 exit test (devStack build, on the phone against the local dev stack) and Compose UI tests.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Espresso 3.7 for API 37 (the version Compose's test library pulls in calls a removed InputManager method).
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
