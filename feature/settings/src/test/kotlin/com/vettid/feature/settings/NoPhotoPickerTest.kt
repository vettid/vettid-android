package com.vettid.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No picture is ever chosen or taken by another app (owner feedback 2026-10-08): the profile photo comes from the
 * in-app camera only. Fails if any app source or manifest names the Photo Picker, a gallery or document pick, the
 * system camera intents or contracts, or MediaStore writes.
 */
class NoPhotoPickerTest {
    private val forbidden = listOf(
        "PickVisualMedia",
        "PickMultipleVisualMedia",
        "PickVisualMediaRequest",
        "ACTION_PICK",
        "ACTION_GET_CONTENT",
        "ACTION_OPEN_DOCUMENT",
        "ACTION_PICK_IMAGES",
        "GetContent(",
        "OpenDocument(",
        "TakePicture",
        "TakePicturePreview",
        "ACTION_IMAGE_CAPTURE",
        "android.media.action.IMAGE_CAPTURE",
        "android.provider.action.PICK_IMAGES",
        "MediaStore",
        "OutputFileOptions",
        "READ_MEDIA_IMAGES",
        "READ_EXTERNAL_STORAGE",
        "WRITE_EXTERNAL_STORAGE",
    )

    /** The repository root: the test runs in the module's directory (feature/settings). */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "app").isDirectory }

    @Test
    fun noSourceOrManifestNamesAPickerOrTheSystemCamera() {
        val sources = root.walkTopDown()
            .onEnter { d -> d.name !in setOf("build", ".gradle", ".git", "test", "androidTest", "build-logic") }
            .filter { it.isFile && (it.extension == "kt" || it.name == "AndroidManifest.xml") && "/src/" in it.path }
            .toList()
        assertTrue("no sources found under $root", sources.size > 50)
        val hits = sources.flatMap { f ->
            val text = f.readText()
            forbidden.filter { it in text }.map { "${f.relativeTo(root)}: $it" }
        }
        assertEquals(emptyList<String>(), hits)
    }
}
