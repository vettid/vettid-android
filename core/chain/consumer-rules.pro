-keep class org.bitcoindevkit.** { *; }
-keep class com.sun.jna.** { *; }
# JNA's desktop-only AWT helper (Native$AWT) is never reached on Android; R8 would stop on the missing java.awt classes.
-dontwarn java.awt.**
