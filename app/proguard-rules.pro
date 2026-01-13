# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ==================== SECURITY HARDENING ====================

# Obfuscate all class names and package structure
-repackageclasses ''
-allowaccessmodification

# Remove debugging information
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Remove toString() from security-sensitive classes to prevent leaking info
-assumenosideeffects class com.vettid.app.core.security.** {
    public java.lang.String toString();
}

# Optimize aggressively
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Don't keep source file attributes or line numbers in release
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ==================== KEEP RULES ====================

# Retrofit and OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Retrofit service interfaces
-keep,allowobfuscation interface com.vettid.app.core.network.VaultServiceApi { *; }

# Keep all network classes (API requests/responses)
-keep class com.vettid.app.core.network.* { *; }
-keep class com.vettid.app.core.storage.StoredCredential { *; }

# Keep Hilt and Dagger
-keep class dagger.** { *; }
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManager { *; }
-keepclasseswithmembers class * {
    @dagger.* <methods>;
}
-keepclasseswithmembers class * {
    @javax.inject.* <fields>;
}
-keepclasseswithmembers class * {
    @javax.inject.* <methods>;
}

# Keep all ViewModels
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }
-keep class com.vettid.app.features.**.* { *; }
-keep class com.vettid.app.ui.**.* { *; }

# Keep Compose runtime
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }

# Keep security-related enums (needed for proper deserialization)
-keep enum com.vettid.app.core.security.SecurityThreat { *; }
-keep enum com.vettid.app.features.auth.BiometricCapability { *; }
-keep enum com.vettid.app.features.auth.BiometricAuthError { *; }

# Keep Tink crypto library
-keep class com.google.crypto.tink.** { *; }

# Keep Signal Argon2 library
-keep class org.signal.argon2.** { *; }

# Keep NATS client
-keep class io.nats.client.** { *; }

# Keep WorkManager workers
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# ==================== WARNINGS ====================

# Don't warn about missing classes from optional dependencies
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.naming.**

# EdDSA/NATS dependencies (uses Sun internal classes not available on Android)
-dontwarn sun.security.x509.**
-dontwarn net.i2p.crypto.eddsa.**
-keep class net.i2p.crypto.eddsa.** { *; }

# ==================== NATIVE METHODS ====================

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# ==================== SERIALIZATION ====================

# Keep serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
