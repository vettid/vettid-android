import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuildConfigField
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.security.MessageDigest
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
// Firebase's components stay disabled in its manifest. Each environment has its own Firebase project. The `full`
// distribution only: the `foss` distribution has no Firebase and reads none of these.
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

val productionAccountHost = "account.vettid.org"
val stagingAccountHost = "account.staging.vettid.org"

// Application ids (ANDROID-PLAN 0.1.35 §5 "Build types and application ids"): the release id, and each other build
// type's suffix. The checks below hold every release-like APK to its own.
val releaseApplicationId = "com.vettid.app"
val stagingApplicationId = "$releaseApplicationId.staging"

android {
    namespace = "com.vettid.app"

    defaultConfig {
        // Same application id as the v1 app (keeps the Play listing and signing key, D1).
        applicationId = releaseApplicationId
        // v1 ended at versionCode 7 / 1.0.55; the rewrite starts at 100 / 2.0.0. 101 / 2.0.0-a1 is the first build
        // that implements release R (VAULT-MESSAGING 0.33 §11.14); the staging channel requires it. 102 / 2.0.0-a2 adds
        // the way out of a wipe that did not finish (erase VettID's data on this phone).
        versionCode = 102
        versionName = "2.0.0-a2"
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
            // A release with calls linked in (`-PvettidCallsInRelease=true`, CI's F-Droid-with-calls step) is a
            // check build and nothing else: under this id it cannot replace or pass for the production app, and no
            // vault accepts its key attestation (a release names its Android package, §11.10.8).
            if (providers.gradleProperty("vettidCallsInRelease").orNull == "true") applicationIdSuffix = ".callscheck"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Reproducible builds (README "F-Droid build"): no META-INF/version-control-info.textproto. Its content
            // depends on how the source was checked out (a commit in a clone, an error text in a git worktree or a
            // source archive), so two builds of the same commit would differ.
            vcsInfo.include = false
            // No signing config in the repository: release builds are signed outside it.
        }
        // STAGING: a release-like build (R8, not debuggable, no debug tools, no
        // :core:testing) that talks to the staging vault service (src/staging). Its own
        // application id, com.vettid.app.staging (ANDROID-PLAN 0.1.35 §5, VAULT-MESSAGING
        // 0.26.0 §11.10.8, from staging release S10): staging images accept exactly that
        // package in key attestation, signed with a staging key, and the staging and
        // production apps install side by side. Never published to a store.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.findByName("staging")
        }
    }

    buildTypes.configureEach {
        // CALLS_TEST: whether this build may switch on the call faults and the experimental DTLS trial that the
        // two-phone tests need (:core:calls MediaFaults; CallsController refuses them otherwise). Debuggable builds
        // always may. The staging vaults accept only the staging package under a pinned signer, so the phone tests
        // run on a staging build: `-PvettidCallsTest=true` makes one that may (signed with the automation key by
        // the tester, never the build members get). Never a release build.
        val callsTest = name != "release" && (isDebuggable || (name == "staging" && providers.gradleProperty("vettidCallsTest").orNull == "true"))
        buildConfigField("boolean", "CALLS_TEST", callsTest.toString())
        // Firebase's receiver and the messaging services exist in the manifest of every `full` build; they are
        // enabled only where the options are (src/full/AndroidManifest.xml, the only manifest that reads this; set
        // on the build type so that the unit-test manifests of a variant get it too).
        manifestPlaceholders["vettidFcmEnabled"] = (fcmOptions(name) != null).toString()        // The account site whose same-device setup link (/vault/enroll/, VAULT-MESSAGING §11.12.1) this build claims
        // as an App Link: its own environment's only. Each site's /.well-known/assetlinks.json names its
        // environment's package (account.staging.vettid.org: com.vettid.app.staging, §11.10.8).
        manifestPlaceholders["vettidAccountHost"] = if (name == "staging") stagingAccountHost else productionAccountHost
    }

    // Two distributions of the same app (README "F-Droid build"; same application id and version;
    // Google Play installs are signed by Google, F-Droid and direct builds by VettID):
    // - `full`: Google Play and VettID's own releases. Google push (Firebase Cloud Messaging, :core:push-fcm) is
    //   linked, and off unless the Gradle properties supply the Firebase options.
    // - `foss`: F-Droid. No Google proprietary code at all: no Firebase, no Google Play services, no WorkManager.
    //   The on-phone service is the notification path; "Google push" is shown as not available in this build.
    //   Only its release build exists (beforeVariants, below): debug, devStack and staging are `full` only.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            isDefault = true
        }
        create("foss") {
            dimension = "distribution"
        }
    }

    // F-Droid (and reproducible builds): no dependency metadata block, which is encrypted with a Google key and
    // cannot be verified by anyone else. One setting for both distributions.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        // The media stack's native libraries (:core:calls) are prebuilt and already stripped. AGP strips native
        // libraries again only where an NDK is installed, so two machines could package different bytes: they go
        // into the APK exactly as the pinned artifact has them (reproducible builds; `check<Variant>Calls` compares).
        jniLibs.keepDebugSymbols += "**/libjingle_peerconnection_so.so"
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
        // src/full and src/foss: each distribution's DistributionApplication and DistributionModule (and, for
        // `full`, the manifest entries of Google push).
        getByName("debug").kotlin.srcDir("src/debugTools/kotlin")
        getByName("devStack").kotlin.srcDir("src/debugTools/kotlin")
        getByName("release").kotlin.srcDir("src/noDebugTools/kotlin")
        getByName("staging").kotlin.srcDir("src/noDebugTools/kotlin")
        // Calls (CALLS-PLAN C1): `src/calls` wires :core:calls into the builds that have it, `src/noCalls` binds
        // "no calls" in the others (CallsModule in both; `callsInRelease` further down decides for release).
        val releaseCalls = providers.gradleProperty("vettidCallsInRelease").orNull == "true"
        for (type in listOf("debug", "devStack", "staging")) getByName(type).kotlin.srcDir("src/calls/kotlin")
        getByName("release").kotlin.srcDir(if (releaseCalls) "src/calls/kotlin" else "src/noCalls/kotlin")
        // Every build that is not production carries a marked launcher icon (src/nonProduction: the white band on
        // the icon's background; not part of the brand kit). Never `release`, so never the F-Droid build.
        listOf("debug", "devStack", "staging").forEach { getByName(it).res.srcDir("src/nonProduction/res") }
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
 * A release-like build carries its own application id and nothing that would collide with the other environment's
 * app on the same phone (ANDROID-PLAN 0.1.35 §5: `com.vettid.app` for release, `com.vettid.app.staging` for staging,
 * installed side by side). Reads the merged manifest and fails if
 * - its package is not [applicationId];
 * - a content provider's authority or a declared permission's name does not start with that id (two apps cannot
 *   share an authority, nor declare one permission under different signatures);
 * - its verified App Links (`autoVerify`) miss [requiredHosts] or name one of [forbiddenHosts]: a build claims its
 *   own environment's account site only.
 * `checkReleaseApk`, `checkFossReleaseApk` and `checkStagingApk` run it.
 */
abstract class ApplicationIdCheck : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val requiredHosts: ListProperty<String>

    @get:Input
    abstract val forbiddenHosts: ListProperty<String>

    @TaskAction
    fun check() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)

        fun all(parent: org.w3c.dom.Element, tag: String) =
            parent.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }
        val root = doc.documentElement
        val id = applicationId.get()
        val problems = mutableListOf<String>()
        if (root.getAttribute("package") != id) problems += "package ${root.getAttribute("package")}, expected $id"
        for (p in all(root, "provider")) {
            p.getAttributeNS(ns, "authorities").split(';').filter { it.isNotEmpty() && !it.startsWith("$id.") }
                .forEach { problems += "provider ${p.getAttributeNS(ns, "name")}: authority $it is not under $id" }
        }
        all(root, "permission").map { it.getAttributeNS(ns, "name") }.filterNot { it.startsWith("$id.") }
            .forEach { problems += "declared permission $it is not under $id" }
        val hosts = all(root, "intent-filter").filter { it.getAttributeNS(ns, "autoVerify") == "true" }
            .flatMap { f -> all(f, "data").map { it.getAttributeNS(ns, "host") } }.filter { it.isNotEmpty() }.toSet()
        problems += requiredHosts.get().filterNot { it in hosts }.map { "App Link host $it is missing" }
        problems += forbiddenHosts.get().filter { it in hosts }.map { "App Link host $it belongs to another environment" }
        check(problems.isEmpty()) { "${manifest.get().asFile}: $problems" }
        logger.lifecycle("merged manifest: application id $id, its own authorities and permissions, App Link hosts $hosts")
    }
}

/**
 * Photo attachments need no media or storage permission (ANDROID-PLAN 0.1.39 "Choosing a photo"): photos come from
 * the Photo Picker, which hands over only what the member picks, or from the app's own camera; Save writes through
 * MediaStore without one. Fails if a build's merged manifest asks for one. Every variant's build runs it.
 */
abstract class MediaPermissionCheck : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun check() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)
        val tags = listOf("uses-permission", "uses-permission-sdk-23")
        val asked = tags.flatMap { t ->
            doc.getElementsByTagName(t).let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") } }
        }
        val found = asked.filter { it in FORBIDDEN }
        check(found.isEmpty()) { "${manifest.get().asFile}: media or storage permissions $found (ANDROID-PLAN 0.1.39)" }
        logger.lifecycle("merged manifest: no media or storage permission")
    }

    companion object {
        val FORBIDDEN = setOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.ACCESS_MEDIA_LOCATION",
        )
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

    /** The `foss` distribution: nothing of Google or of WorkManager may be in the manifest at all. */
    @get:Input
    abstract val foss: Property<Boolean>

    @TaskAction
    fun check() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)

        fun all(tag: String) = doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }

        fun org.w3c.dom.Element.a(name: String) = getAttributeNS(ns, name)
        val components = listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { all(it) }
        val problems = mutableListOf<String>()
        val permissions = all("uses-permission").map { it.a("name") }
        if (foss.get()) {
            checkFoss(permissions, all("package").map { it.a("name") }, components.map { it.a("name") }, all("meta-data").map { it.a("name") }, all("action").map { it.a("name") })
            return
        }
        problems += permissions.filter { it.startsWith("com.google.") && it != "com.google.android.c2dm.permission.RECEIVE" }
            .map { "permission $it" }
        problems += permissions.filter { it.contains("AD_ID") || it.contains("ADSERVICES") }.map { "permission $it" }
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

    /** The `foss` distribution (F-Droid): no Google permission, component, registrar, query or action, no WorkManager. */
    private fun checkFoss(permissions: List<String>, queries: List<String>, components: List<String>, meta: List<String>, actions: List<String>) {
        fun google(name: String) = FOSS_FORBIDDEN.any { name.startsWith(it) }
        val problems = permissions.filter { google(it) || it.contains("AD_ID") || it.contains("ADSERVICES") }.map { "permission $it" } +
            queries.filter { google(it) }.map { "package query $it" } +
            components.filter { google(it) || it.startsWith("androidx.work.") }.map { "component $it" } +
            meta.filter { google(it) || it.startsWith("firebase_") || it.startsWith("androidx.work.") || it.startsWith("delivery_metrics") }.map { "meta-data $it" } +
            actions.filter { google(it) }.map { "intent action $it" }
        check(problems.isEmpty()) { "${manifest.get().asFile}: the foss distribution must carry nothing of Google: $problems" }
        logger.lifecycle("merged manifest (foss): no Google permission, component, registrar, query or action, no WorkManager")
    }

    companion object {
        /** Manifest names that mean Google proprietary code ([checkFoss]). */
        val FOSS_FORBIDDEN = listOf("com.google.", "com.android.vending", "com.android.installreferrer")

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

/**
 * The `foss` distribution contains no Google proprietary code (README "F-Droid build"; F-Droid's inclusion policy).
 * Fails if
 * - the variant's resolved runtime dependency graph has a module of a proprietary group (Firebase, Google Play
 *   services, datatransport, Play Core, ML Kit, install referrer, billing, ads), whoever pulled it in, or
 *   WorkManager, which only Google push used;
 * - an APK's dex carries a class of those packages (their type descriptors survive R8 wherever a class is kept), or
 *   the name of the permission that receives Google pushes;
 * - an APK carries a file that those libraries ship (`firebase-*.properties`, `play-services-*.properties`, …).
 * `checkFossReleaseApk` runs it in CI.
 */
abstract class NoGoogleCheck : DefaultTask() {
    @get:Input
    abstract val graph: Property<ResolvedComponentResult>

    @get:InputFiles
    abstract val apkDir: DirectoryProperty

    @TaskAction
    fun check() {
        val seen = mutableSetOf<ResolvedComponentResult>()
        val queue = ArrayDeque(listOf(graph.get()))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (seen.add(c)) c.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
        val modules = seen.mapNotNull { it.id as? ModuleComponentIdentifier }
        val projects = seen.map { it.id.displayName }.filter { it.contains(":core:push-fcm") }
        val bad = modules.filter { m -> GROUPS.any { m.group == it || m.group.startsWith("$it.") } }.map { it.displayName } + projects
        check(bad.isEmpty()) { "the foss distribution depends on proprietary or push-only modules: $bad" }

        val apks = apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
        require(apks.isNotEmpty()) { "no APK in ${apkDir.get().asFile}" }
        for (apk in apks) {
            ZipFile(apk).use { z ->
                val entries = z.entries().asSequence().toList()
                val files = entries.map { it.name }.filter { n -> FILES.any { n.substringAfterLast('/').startsWith(it) } }
                val dex = entries.filter { it.name.endsWith(".dex") }
                    .joinToString("\u0000") { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.ISO_8859_1) } }
                val classes = DEX.filter { dex.contains(it) } +
                    Regex("com\\.google\\.android\\.gms[A-Za-z0-9_.$]*").findAll(dex).map { it.value }.filterNot { it in ALLOWED }.distinct()
                // Native libraries (the media stack of :core:calls is prebuilt): the same names, as JNI class paths or
                // as text, would mean a library that reaches for Google Play services or Firebase.
                val native = entries.filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }.flatMap { e ->
                    val text = z.getInputStream(e).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                    NATIVE.filter { text.contains(it) }.map { "${e.name}: $it" }
                }
                check(files.isEmpty() && classes.isEmpty() && native.isEmpty()) {
                    "${apk.name}: Google proprietary code in the foss distribution: files $files, dex $classes, native $native"
                }
            }
            logger.lifecycle("${apk.name}: ${modules.size} modules, none proprietary; no Google class, file or push permission in the APK")
        }
    }

    companion object {
        /** Maven groups (and their sub-groups) that F-Droid does not accept, and WorkManager (push-only here). */
        val GROUPS = listOf(
            "com.google.firebase", "com.google.android.gms", "com.google.android.datatransport", "com.google.android.play",
            "com.google.mlkit", "com.google.android.libraries", "com.google.android.ump", "com.google.android.recaptcha",
            "com.android.installreferrer", "com.android.billingclient", "com.crashlytics.sdk.android", "com.huawei",
            "androidx.work",
        )

        /** Dex strings: the type descriptors of those packages, and Google push's permission and action. */
        val DEX = listOf(
            "Lcom/google/firebase/", "Lcom/google/android/gms/", "Lcom/google/android/datatransport/", "Lcom/google/android/play/",
            "Lcom/google/mlkit/", "Lcom/android/installreferrer/", "Lcom/android/billingclient/", "Lcom/android/vending/",
            "Landroidx/work/", "com.google.android.c2dm", "com.google.firebase",
        )

        /**
         * The one Google Play services name a free library carries as a string: OkHttp (Apache-2.0) looks for a
         * Conscrypt TLS provider under this package name by reflection, on phones that have one. No Google code.
         */
        val ALLOWED = setOf("com.google.android.gms.org.conscrypt.", "com.google.android.gms.org.conscrypt")

        /** Strings that a native library calling into those packages would carry. */
        val NATIVE = listOf(
            "com/google/android/gms", "com.google.android.gms", "com/google/firebase", "com.google.firebase",
            "com/android/vending", "com.android.vending", "com/google/android/play",
        )

        /** Version and marker files those libraries put into an APK. */
        val FILES = listOf("firebase-", "play-services-", "transport-", "com.google.firebase", "com.google.android.gms")
    }
}

/**
 * Calls (CALLS-PLAN 0.1.1, phase C1) are in the debug, devStack and staging builds only until they are promoted
 * (STAGING-DEVELOPMENT-PLAN §2.4). `check<Variant>Calls`, run by `checkReleaseApk`, `checkFossReleaseApk` and
 * `checkStagingApk`:
 * - **without calls** (a release build): the APK has no media stack (no `org.webrtc` class, no native library of
 *   it), no class of `:core:calls`, and the merged manifest none of the permissions calls need and no call component;
 * - **with calls** (staging; a release built with `-PvettidCallsInRelease=true`): the APK is universal (the media
 *   stack's native library for all four ABIs: no ABI split or filter, owner decision 2026-10-12) and each library is
 *   byte for byte the one in the pinned artifact (nothing stripped or rebuilt it), and the dex has the stack.
 */
abstract class CallsApkCheck : DefaultTask() {
    @get:InputFiles
    abstract val apkDir: DirectoryProperty

    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val expected: Property<Boolean>

    @TaskAction
    fun check() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)

        fun names(tag: String) = doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") } }
        val permissions = names("uses-permission").filter { it in PERMISSIONS }
        val components = listOf("activity", "activity-alias", "service", "receiver", "provider").flatMap { names(it) }
            .filter { it.contains(".calls.") }
        val apks = apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
        require(apks.isNotEmpty()) { "no APK in ${apkDir.get().asFile}" }
        for (apk in apks) {
            ZipFile(apk).use { z ->
                val entries = z.entries().asSequence().toList()
                val libs = entries.filter { it.name.endsWith("/$LIBRARY") }.associate { e ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    z.getInputStream(e).use { input ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    e.name.removePrefix("lib/").substringBefore('/') to digest.digest().joinToString("") { "%02x".format(it) }
                }
                val dex = entries.filter { it.name.endsWith(".dex") }
                    .joinToString("\u0000") { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.ISO_8859_1) } }
                val classes = DEX.filter { dex.contains(it) }
                if (expected.get()) {
                    check(libs == NATIVE_SHA256) { "${apk.name}: the media stack's native libraries are $libs, expected $NATIVE_SHA256 (universal, unchanged)" }
                    check(DEX[0] in classes) { "${apk.name}: calls are expected in this build, but the media stack's classes are missing" }
                    logger.lifecycle("${apk.name}: calls: the media stack for ${libs.keys.sorted()}, each library as pinned")
                } else {
                    check(libs.isEmpty() && classes.isEmpty() && permissions.isEmpty() && components.isEmpty()) {
                        "${apk.name}: calls are not in production builds yet, but found: libraries ${libs.keys}, dex $classes, " +
                            "permissions $permissions, components $components"
                    }
                    logger.lifecycle("${apk.name}: no calls: no media stack, no call permission or component")
                }
            }
        }
    }

    companion object {
        const val LIBRARY = "libjingle_peerconnection_so.so"

        /** The four libraries inside `io.github.webrtc-sdk:android:150.7871.04` (AAR SHA-256 `1872dda4…c84c`, core/calls). */
        val NATIVE_SHA256 = mapOf(
            "arm64-v8a" to "2b561520298d9fec3e216baf58a53fbddf4a46dfd247880cbc3821875ed20200",
            "armeabi-v7a" to "5415e7a02ce83e983329575e7c2e46b8ced6c75baea3c41aa52b5699ccb54eda",
            "x86_64" to "b87bbb6d6c81c50f04538cd809575de633a789cc3555af2c5eb6ec414b148908",
            "x86" to "9a532ef72dcda8b4b9874d4f578dbd3a5eb18bce01f9f91bee883bc0962dce3c",
        )

        /** Type descriptors: the media stack's, and :core:calls' own. */
        val DEX = listOf("Lorg/webrtc/", "Lorg/jni_zero/", "Lcom/vettid/core/calls/", "Lcom/vettid/feature/calls/")

        /** What calls will ask for (CALLS-PLAN §4); no build without calls declares any of them today. */
        val PERMISSIONS = listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.FOREGROUND_SERVICE_PHONE_CALL",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.MANAGE_OWN_CALLS",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.USE_FULL_SCREEN_INTENT",
        )
    }
}

/**
 * Calls in a release build: off. `-PvettidCallsInRelease=true` links them in, for the one CI step that keeps the
 * F-Droid distribution building with them (`:app:assembleFossRelease :app:checkFossReleaseApk`: no Google code, the
 * media stack as pinned). Never for a build that is published before calls are promoted: such a build has the
 * application id `com.vettid.app.callscheck` (the release build type, above), which no vault accepts and which
 * cannot update the production app.
 */
val callsInRelease = providers.gradleProperty("vettidCallsInRelease").orNull == "true"

/**
 * "No calls code in production" beyond the media stack: the call code of :core:crypto and :core:vault is compiled
 * into every variant and only R8 keeps it out of a release. These strings are in that code and nowhere else, and
 * R8 does not rename strings: a release dex that has one has call code that something reaches (for example a
 * `call.list` probe wired outside the build-type gate).
 */
val callsMarkers = listOf(
    "vettid/vms/2/call-media", "vettid/vms/2/call-frame", "vettid/vms/2/call-share", "vettid/vms/2/call-vouch", "vettid/vms/2/call-key",
    "vettid-media-suites", "vettid-media-suite", "call.start", "call.ringing", "call.ice", "call.list",
)

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

fun String.cap() = replaceFirstChar { it.uppercase() }

androidComponents {
    // The `foss` distribution is built for release only (F-Droid); every other build type is `full`, as before
    // the distributions existed: no variant is built or tested twice.
    beforeVariants(selector().withFlavor("distribution" to "foss")) { it.enable = it.buildType == "release" }

    // Google push's options in BuildConfig: the `full` distribution only.
    onVariants(selector().withFlavor("distribution" to "full")) { v ->
        val fcm = fcmOptions(v.buildType!!)
        for ((field, option) in listOf("FCM_APPLICATION_ID" to "ApplicationId", "FCM_PROJECT_ID" to "ProjectId", "FCM_API_KEY" to "ApiKey", "FCM_SENDER_ID" to "SenderId")) {
            v.buildConfigFields!!.put(field, BuildConfigField("String", "\"${fcm?.get(option).orEmpty()}\"", null))
        }
    }

    onVariants { v ->
        val foss = v.flavorName == "foss"
        // The task names of the `full` distribution are the ones from before the distributions:
        // checkReleasePushManifest, checkReleaseApplicationId, checkReleaseApk, checkStagingApk. `foss`:
        // checkFossReleasePushManifest, checkFossReleaseApplicationId, checkFossReleaseApk.
        val name = if (foss) v.name.cap() else v.buildType!!.cap()
        val media = tasks.register<MediaPermissionCheck>("check${v.name.cap()}MediaPermissions") {
            manifest.set(v.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        // Every build of every variant (debug, devStack, staging, release; both distributions) runs it: APKs,
        // bundles and unit tests (not pre*Build, which the merged manifest itself depends on).
        tasks.matching { t ->
            t.name in setOf("assemble${v.name.cap()}", "bundle${v.name.cap()}", "test${v.name.cap()}UnitTest")
        }.configureEach { dependsOn(media) }
        tasks.register<PushManifestCheck>("check${name}PushManifest") {
            manifest.set(v.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            pushConfigured.set(!foss && fcmOptions(v.buildType!!) != null)
            this.foss.set(foss)
        }
        // The application id of each release-like build (ANDROID-PLAN 0.1.35 §5), and its own account site only.
        val staging = v.buildType == "staging"
        if (staging || v.buildType == "release") {
            tasks.register<ApplicationIdCheck>("check${name}ApplicationId") {
                manifest.set(v.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                applicationId.set(
                    when {
                        staging -> stagingApplicationId
                        callsInRelease -> "$releaseApplicationId.callscheck"
                        else -> releaseApplicationId
                    },
                )
                requiredHosts.set(listOf(if (staging) stagingAccountHost else productionAccountHost))
                forbiddenHosts.set(listOf(if (staging) productionAccountHost else stagingAccountHost))
            }
        }
        if (staging || v.buildType == "release") {
            tasks.register<CallsApkCheck>("check${name}Calls") {
                apkDir.set(v.artifacts.get(SingleArtifact.APK))
                manifest.set(v.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                expected.set(staging || callsInRelease)
            }
        }
        when (v.buildType) {
            "release" -> {
                val contents = tasks.register<ApkContentCheck>(if (foss) "checkFossReleaseApkContents" else "checkReleaseApk") {
                    dependsOn("check${name}PushManifest", "check${name}ApplicationId", "check${name}Calls")
                    apkDir.set(v.artifacts.get(SingleArtifact.APK))
                    // Each environment has its own Firebase project (PUSH-GATEWAY §10.4): never the other one's app.
                    forbidden.set(
                        stagingPins + devOnly + listOfNotNull(fcmOptions("staging")?.get("ApplicationId")) +
                            if (callsInRelease) emptyList() else callsMarkers,
                    )
                    required.set(productionPins)
                }
                if (foss) {
                    val runtime = configurations.named("${v.name}RuntimeClasspath")
                    tasks.register<NoGoogleCheck>("checkFossReleaseApk") {
                        dependsOn(contents)
                        graph.set(runtime.flatMap { it.incoming.resolutionResult.rootComponent })
                        apkDir.set(v.artifacts.get(SingleArtifact.APK))
                    }
                }
            }
            "staging" -> tasks.register<ApkContentCheck>("checkStagingApk") {
                dependsOn("check${name}PushManifest", "check${name}ApplicationId", "check${name}Calls")
                apkDir.set(v.artifacts.get(SingleArtifact.APK))
                forbidden.set(productionPins + devOnly + listOfNotNull(fcmOptions("release")?.get("ApplicationId")))
                required.set(stagingPins)
            }
        }
    }
}

// The `full` distribution's per-variant tasks keep the names they had before the distributions (CI, the READMEs and
// devstack/README.md use them): `:app:testDebugUnitTest` is `:app:testFullDebugUnitTest`, and so on. Without these,
// a root `./gradlew testDebugUnitTest` would silently skip the app's own tests.
afterEvaluate {
    for (type in listOf("debug", "devStack", "staging", "release")) {
        val t = type.cap()
        for ((alias, target) in listOf(
            "test${t}UnitTest" to "testFull${t}UnitTest",
            "lint$t" to "lintFull$t",
            "install$t" to "installFull$t",
            "uninstall$t" to "uninstallFull$t",
            "connected${t}AndroidTest" to "connectedFull${t}AndroidTest",
        )) {
            if (target in tasks.names && alias !in tasks.names) {
                tasks.register(alias) {
                    group = "full distribution"
                    description = "Runs $target (the name from before the distributions)."
                    dependsOn(target)
                }
            }
        }
    }
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.core.notify)
    // Calls (CALLS-PLAN 0.1.1 phase C1): not in production builds until they are promoted (CallsApkCheck above).
    for (type in listOf("debug", "devStack", "staging")) "${type}Implementation"(projects.core.calls)
    if (callsInRelease) "releaseImplementation"(projects.core.calls)
    // Google push: the `full` distribution only. Looked up by path, not by accessor: an F-Droid build removes the
    // module's directory, and settings.gradle.kts then does not include it (the `foss` distribution never needs it).
    findProject(":core:push-fcm")?.let { "fullImplementation"(it) }
    // Configuration.Provider only (DistributionApplication of `full`): WorkManager itself is used by :core:push-fcm.
    "fullImplementation"(libs.androidx.work.runtime)
    implementation(projects.feature.messages)
    implementation(projects.feature.connections)
    implementation(projects.feature.approvals)
    implementation(projects.feature.items)
    implementation(projects.feature.history)
    implementation(projects.feature.devices)
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
