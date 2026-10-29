# secp256k1-kmp's own rules (fr.acinq.secp256k1.jni.** kept) arrive with the AAR; nothing more is needed.
# Wallet W0 spike: keep bitcoin-kmp whole, so the APK size shows its real cost (nothing in the app calls it yet, R8 would remove it all).
-keep class fr.acinq.** { *; }
-dontwarn fr.acinq.**
