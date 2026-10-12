# libwebrtc's native code finds its Java classes, methods and fields by name (jni_zero), and the artifact ships no
# consumer rules of its own: nothing of org.webrtc may be renamed or removed.
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
# The artifact's jar lacks one generated class, org.jni_zero.JniZeroJni. Its only user is
# JniZero.setJniClassLoader (for apps that load the library from another class loader), which nothing in org.webrtc
# and nothing in this app calls (C0 finding, draft PR #123).
-dontwarn org.jni_zero.JniZeroJni
