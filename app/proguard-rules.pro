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

# Keep Retrofit interfaces
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep Gson serialized classes
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.vettid.app.core.network.* { *; }
-keep class com.vettid.app.core.storage.StoredCredential { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }

# Keep Compose runtime
-keep class androidx.compose.** { *; }

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
