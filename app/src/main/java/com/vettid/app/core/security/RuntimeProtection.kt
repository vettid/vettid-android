package com.vettid.app.core.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime Application Self-Protection (RASP)
 *
 * Detects runtime security threats:
 * - Root/jailbreak detection
 * - Debugger attachment
 * - Emulator detection
 * - App tampering
 * - Frida/instrumentation framework detection
 * - Hooking framework detection
 */
@Singleton
class RuntimeProtection @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // MARK: - Comprehensive Security Check

    /**
     * Perform all security checks
     * Returns a list of detected threats
     */
    fun performSecurityCheck(): SecurityCheckResult {
        val threats = mutableListOf<SecurityThreat>()

        if (isRooted()) threats.add(SecurityThreat.ROOTED_DEVICE)
        if (isDebuggerAttached()) threats.add(SecurityThreat.DEBUGGER_ATTACHED)
        if (isEmulator()) threats.add(SecurityThreat.EMULATOR)
        if (isAppTampered()) threats.add(SecurityThreat.APP_TAMPERED)
        if (isFridaDetected()) threats.add(SecurityThreat.FRIDA_DETECTED)
        if (isHookingFrameworkDetected()) threats.add(SecurityThreat.HOOKING_FRAMEWORK)
        if (isDebuggableBuild()) threats.add(SecurityThreat.DEBUGGABLE_BUILD)
        if (isAdbEnabled()) threats.add(SecurityThreat.ADB_ENABLED)

        return SecurityCheckResult(
            isSecure = threats.isEmpty(),
            threats = threats
        )
    }

    /**
     * Quick check for critical threats only
     */
    fun hasCriticalThreats(): Boolean {
        return isDebuggerAttached() || isFridaDetected() || isAppTampered()
    }

    // MARK: - Root Detection

    /**
     * Comprehensive root detection
     */
    fun isRooted(): Boolean {
        return checkRootBinaries() ||
                checkRootPaths() ||
                checkSuBinary() ||
                checkRootManagementApps() ||
                checkDangerousProps() ||
                checkRWSystem() ||
                checkBusybox()
    }

    private fun checkRootBinaries(): Boolean {
        val rootBinaries = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su-backup",
            "/system/xbin/mu",
            "/data/local/su",
            "/data/local/bin/su",
            "/data/local/xbin/su"
        )
        return rootBinaries.any { File(it).exists() }
    }

    private fun checkRootPaths(): Boolean {
        val pathEnv = System.getenv("PATH") ?: return false
        val paths = pathEnv.split(":")
        return paths.any { path ->
            File("$path/su").exists()
        }
    }

    private fun checkSuBinary(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.readLine() != null
        } catch (e: Exception) {
            false
        }
    }

    private fun checkRootManagementApps(): Boolean {
        val rootApps = listOf(
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.topjohnwu.magisk",
            "me.phh.superuser",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.smedialink.oneclickroot",
            "com.zhiqupk.root.global"
        )

        val pm = context.packageManager
        return rootApps.any { app ->
            try {
                pm.getPackageInfo(app, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    private fun checkDangerousProps(): Boolean {
        val dangerousProps = mapOf(
            "ro.debuggable" to "1",
            "ro.secure" to "0"
        )

        return dangerousProps.any { (prop, dangerousValue) ->
            try {
                val value = getSystemProperty(prop)
                value == dangerousValue
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun checkRWSystem(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("mount")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            // Check if /system is mounted as read-write
            output.contains("/system") && output.contains("rw,")
        } catch (e: Exception) {
            false
        }
    }

    private fun checkBusybox(): Boolean {
        val busyboxPaths = listOf(
            "/system/xbin/busybox",
            "/system/bin/busybox",
            "/sbin/busybox",
            "/data/local/xbin/busybox",
            "/data/local/bin/busybox"
        )
        return busyboxPaths.any { File(it).exists() }
    }

    private fun getSystemProperty(propName: String): String? {
        return try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val getMethod = systemProperties.getMethod("get", String::class.java)
            getMethod.invoke(null, propName) as? String
        } catch (e: Exception) {
            null
        }
    }

    // MARK: - Debugger Detection

    /**
     * Check if a debugger is attached
     */
    fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() ||
                Debug.waitingForDebugger() ||
                checkTracerPid()
    }

    private fun checkTracerPid(): Boolean {
        return try {
            val statusFile = File("/proc/self/status")
            if (!statusFile.exists()) return false

            val content = statusFile.readText()
            val tracerPid = content.lines()
                .find { it.startsWith("TracerPid:") }
                ?.substringAfter(":")
                ?.trim()
                ?.toIntOrNull() ?: 0

            tracerPid > 0
        } catch (e: Exception) {
            false
        }
    }

    // MARK: - Emulator Detection

    /**
     * Check if running on an emulator
     */
    fun isEmulator(): Boolean {
        return checkEmulatorBuild() ||
                checkEmulatorFiles() ||
                checkEmulatorHardware() ||
                checkQemuDriver()
    }

    private fun checkEmulatorBuild(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.lowercase().contains("droid4x") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.PRODUCT.contains("sdk") ||
                Build.PRODUCT.contains("google_sdk") ||
                Build.PRODUCT.contains("sdk_x86") ||
                Build.PRODUCT.contains("vbox86p") ||
                Build.PRODUCT.contains("emulator") ||
                Build.PRODUCT.contains("simulator") ||
                Build.BOARD.lowercase().contains("nox") ||
                Build.BOOTLOADER.lowercase().contains("nox") ||
                Build.HARDWARE.lowercase().contains("nox") ||
                Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
    }

    private fun checkEmulatorFiles(): Boolean {
        val emulatorFiles = listOf(
            "/dev/socket/qemud",
            "/dev/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-props",
            "/dev/socket/genyd",
            "/dev/socket/baseband_genyd",
            "fstab.goldfish",
            "fstab.ranchu",
            "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq",
            "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq"
        )
        return emulatorFiles.any { File(it).exists() }
    }

    private fun checkEmulatorHardware(): Boolean {
        return try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            cpuInfo.lowercase().contains("goldfish") ||
                    cpuInfo.lowercase().contains("ranchu") ||
                    cpuInfo.lowercase().contains("virtual")
        } catch (e: Exception) {
            false
        }
    }

    private fun checkQemuDriver(): Boolean {
        return try {
            val drivers = File("/proc/tty/drivers").readText()
            drivers.contains("goldfish") || drivers.contains("msm_serial")
        } catch (e: Exception) {
            false
        }
    }

    // MARK: - App Tampering Detection

    /**
     * Check if the app has been tampered with
     */
    fun isAppTampered(): Boolean {
        return checkSignature() || checkInstaller()
    }

    private fun checkSignature(): Boolean {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            // In production, compare against known good signature hash
            // For now, just check if signatures exist
            signatures.isNullOrEmpty()
        } catch (e: Exception) {
            true // If we can't check, assume tampered
        }
    }

    private fun checkInstaller(): Boolean {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }

            // Legitimate installers
            val legitimateInstallers = listOf(
                "com.android.vending",      // Google Play Store
                "com.google.android.feedback", // Google Play Store (alternate)
                "com.amazon.venezia",       // Amazon App Store
                "com.sec.android.app.samsungapps", // Samsung Galaxy Store
                null // Direct install (for development)
            )

            installer !in legitimateInstallers
        } catch (e: Exception) {
            false
        }
    }

    // MARK: - Frida Detection

    /**
     * Detect Frida instrumentation framework
     */
    fun isFridaDetected(): Boolean {
        return checkFridaPort() ||
                checkFridaFiles() ||
                checkFridaLibraries() ||
                checkFridaProcess()
    }

    private fun checkFridaPort(): Boolean {
        val fridaPorts = listOf(27042, 27043)
        return fridaPorts.any { port ->
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 100)
                    true
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun checkFridaFiles(): Boolean {
        val fridaFiles = listOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/re.frida.server",
            "/sdcard/Download/frida-server"
        )
        return fridaFiles.any { File(it).exists() }
    }

    private fun checkFridaLibraries(): Boolean {
        return try {
            val mapsFile = File("/proc/self/maps")
            if (!mapsFile.exists()) return false

            val content = mapsFile.readText().lowercase()
            content.contains("frida") ||
                    content.contains("gadget") ||
                    content.contains("xposed")
        } catch (e: Exception) {
            false
        }
    }

    private fun checkFridaProcess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("ps")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText().lowercase()
            output.contains("frida") || output.contains("gadget")
        } catch (e: Exception) {
            false
        }
    }

    // MARK: - Hooking Framework Detection

    /**
     * Detect hooking frameworks (Xposed, Substrate, etc.)
     */
    fun isHookingFrameworkDetected(): Boolean {
        return checkXposed() ||
                checkSubstrate() ||
                checkHookingLibraries()
    }

    private fun checkXposed(): Boolean {
        // Check for Xposed modules
        val xposedPackages = listOf(
            "de.robv.android.xposed.installer",
            "io.va.exposed",
            "org.meowcat.edxposed.manager",
            "com.topjohnwu.magisk"
        )

        val pm = context.packageManager
        val hasXposedApp = xposedPackages.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

        // Check for Xposed in stack trace
        val hasXposedInStack = try {
            throw Exception("Stack trace check")
        } catch (e: Exception) {
            e.stackTrace.any { element ->
                element.className.contains("de.robv.android.xposed") ||
                        element.className.contains("EdXposed") ||
                        element.className.contains("LSPosed")
            }
        }

        return hasXposedApp || hasXposedInStack
    }

    private fun checkSubstrate(): Boolean {
        return try {
            // Check if Cydia Substrate is present
            Class.forName("com.saurik.substrate.MS")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private fun checkHookingLibraries(): Boolean {
        return try {
            val mapsFile = File("/proc/self/maps")
            if (!mapsFile.exists()) return false

            val content = mapsFile.readText().lowercase()
            content.contains("xposed") ||
                    content.contains("substrate") ||
                    content.contains("edxposed") ||
                    content.contains("lsposed") ||
                    content.contains("riru")
        } catch (e: Exception) {
            false
        }
    }

    // MARK: - Debug Build Detection

    /**
     * Check if this is a debuggable build
     */
    fun isDebuggableBuild(): Boolean {
        return try {
            val appInfo = context.applicationInfo
            (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            false
        }
    }

    // MARK: - ADB Detection

    /**
     * Check if ADB is enabled
     */
    fun isAdbEnabled(): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            false
        }
    }
}

// MARK: - Data Classes

data class SecurityCheckResult(
    val isSecure: Boolean,
    val threats: List<SecurityThreat>
)

enum class SecurityThreat {
    ROOTED_DEVICE,
    DEBUGGER_ATTACHED,
    EMULATOR,
    APP_TAMPERED,
    FRIDA_DETECTED,
    HOOKING_FRAMEWORK,
    DEBUGGABLE_BUILD,
    ADB_ENABLED
}
