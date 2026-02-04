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

# Optimize but preserve type information for Retrofit
-optimizationpasses 3
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!class/unboxing/enum

# Don't keep source file attributes or line numbers in release
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ==================== KEEP RULES ====================

# Retrofit and OkHttp - CRITICAL: Keep generic type signatures
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Retrofit core - keep everything including internal utils for type resolution
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keepclassmembers class retrofit2.** { *; }
-dontwarn retrofit2.**

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson - preserve ALL generic type information
-keep class com.google.gson.** { *; }
-keep interface com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * extends com.google.gson.reflect.TypeToken { *; }

# Keep generic signatures on ALL classes that use Retrofit Response
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Retrofit service interfaces - DO NOT allow obfuscation
-keep interface com.vettid.app.core.network.VaultServiceApi { *; }
-keep interface com.vettid.app.core.network.VaultLifecycleApi { *; }
-keep interface com.vettid.app.core.network.VaultHandlerApi { *; }
-keep interface com.vettid.app.core.nats.NatsApi { *; }

# Keep ALL network classes with full signatures (API requests/responses)
-keep class com.vettid.app.core.network.** { *; }
-keepclassmembers class com.vettid.app.core.network.** { *; }

# Keep NATS API classes
-keep class com.vettid.app.core.nats.** { *; }
-keepclassmembers class com.vettid.app.core.nats.** { *; }

# Preserve generic type info on method return types
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

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

# Keep Kotlin metadata for proper type resolution
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep security-related enums (needed for proper deserialization)
-keep enum com.vettid.app.core.security.SecurityThreat { *; }
-keep enum com.vettid.app.features.auth.BiometricCapability { *; }
-keep enum com.vettid.app.features.auth.BiometricAuthError { *; }

# Keep Tink crypto library
-keep class com.google.crypto.tink.** { *; }

# Keep Signal Argon2 library
-keep class org.signal.argon2.** { *; }

# Keep BouncyCastle - required for PCR manifest signature verification and attestation
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }
-keepclassmembers class org.bouncycastle.** { *; }

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
-dontwarn java.beans.**
-dontwarn com.fasterxml.jackson.**

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
