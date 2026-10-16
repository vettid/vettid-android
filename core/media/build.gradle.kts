// Photo attachments (ANDROID-PLAN 0.1.39 "Photo attachments", VAULT-MESSAGING 0.32.0 §10.5.1): reading picker and
// camera input into memory, bounds read from the header first, orientation, downscaling, JPEG encoding without
// metadata, thumbnails, type sniffing, bounded first-frame decoding, name sanitizing and the memory-only cache. No
// Android permission and no disk: nothing here writes a file.
plugins {
    id("vettid.android.library")
}

dependencies {
    // ImageDecoder, Bitmap and the JPEG encoder under Robolectric's native graphics.
    testImplementation(libs.robolectric)
}

android {
    testOptions.unitTests {
        isIncludeAndroidResources = true
        // Robolectric's API 36 runtime reaches into the JDK's file descriptor internals.
        all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}
