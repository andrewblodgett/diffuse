package com.diffuse.drive

import com.diffuse.backup.model.MediaKind
import com.diffuse.backup.model.MediaRecord
import com.diffuse.drive.store.UploadManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class DriveMediaSinkTest {
    @get:Rule val tmp = TemporaryFolder()

    /** DriveApi that only expects stream uploads; drains the stream to prove it's readable. */
    private class FakeDrive : DriveApi {
        val folders = mutableListOf<String>()          // "name@parent"
        val streamUploads = mutableListOf<String>()     // "name@parentId len=<n>"
        private var seq = 0
        private val folderIds = HashMap<String, String>()
        override fun ensureFolder(name: String, parentId: String?): String =
            folderIds.getOrPut("$parentId/$name") { folders.add("$name@$parentId"); "folder${seq++}" }
        override fun upload(name: String, parentId: String, mimeType: String, file: File): String =
            error("file upload not expected from a media sink")
        override fun upload(name: String, parentId: String, mimeType: String, length: Long, open: () -> InputStream): String {
            open().use { it.readBytes() }
            streamUploads.add("$name@$parentId len=$length")
            return "file${seq++}"
        }
    }

    private fun photo(size: Long) = MediaRecord(
        id = 1,
        kind = MediaKind.IMAGE,
        displayName = "photo.jpg",
        relativePath = "DCIM/Camera/",
        mimeType = "image/jpeg",
        sizeBytes = size,
        dateTakenEpochMs = null,
        dateAddedEpochMs = 0,
        dateModifiedEpochMs = 0,
        generationModified = 0,
        contentUri = "content://media/external/images/media/1",
    )

    /** Never asked for a length when the record already has one. */
    private val noLength: (String) -> Long = { error("lengthOf must not be called when _size > 0") }

    @Test fun streams_bytes_creates_folders_and_records_manifest() {
        val drive = FakeDrive()
        val manifest = UploadManifest(File(tmp.root, "m.properties"))
        val opened = mutableListOf<String>()
        val sink = DriveMediaSink(
            openStream = { uri -> opened += uri; ByteArrayInputStream(ByteArray(4)) },
            lengthOf = noLength,
            drive = drive,
            folders = DriveFolderTree(drive, "ROOT"),
            manifest = manifest,
        )

        sink.put(photo(4), "media/DCIM/Camera/photo.jpg")

        assertEquals(1, sink.uploaded)
        assertEquals(0, sink.skipped)
        assertEquals(4L, sink.bytesUploaded)
        // The record's contentUri was opened read-only exactly once.
        assertEquals(listOf("content://media/external/images/media/1"), opened)
        // The media/DCIM/Camera tree was created under the Diffuse root.
        assertTrue(drive.folders.any { it == "media@ROOT" })
        assertTrue(drive.folders.any { it.startsWith("Camera@folder") })
        // The photo landed in the Camera folder and length was advertised from the record.
        assertTrue(drive.streamUploads.single().startsWith("photo.jpg@folder"))
        assertTrue(drive.streamUploads.single().endsWith("len=4"))
        assertTrue(manifest.contains("media/DCIM/Camera/photo.jpg"))
    }

    @Test fun skips_when_manifest_already_contains_path() {
        val drive = FakeDrive()
        val manifest = UploadManifest(File(tmp.root, "m.properties"))
        manifest.record("media/DCIM/Camera/photo.jpg", "existingId")
        var openedCount = 0
        val sink = DriveMediaSink(
            openStream = { openedCount++; ByteArrayInputStream(ByteArray(4)) },
            lengthOf = noLength,
            drive = drive,
            folders = DriveFolderTree(drive, "ROOT"),
            manifest = manifest,
        )

        sink.put(photo(4), "media/DCIM/Camera/photo.jpg")

        assertEquals(0, sink.uploaded)
        assertEquals(1, sink.skipped)
        assertEquals(0, openedCount) // never opened the provider stream
        assertTrue(drive.streamUploads.isEmpty())
    }

    @Test fun folder_tree_is_created_once_across_many_files() {
        val drive = FakeDrive()
        val manifest = UploadManifest(File(tmp.root, "m.properties"))
        val sink = DriveMediaSink(
            openStream = { ByteArrayInputStream(ByteArray(1)) },
            lengthOf = noLength,
            drive = drive,
            folders = DriveFolderTree(drive, "ROOT"),
            manifest = manifest,
        )

        sink.put(photo(1), "media/DCIM/Camera/a.jpg")
        sink.put(photo(1), "media/DCIM/Camera/b.jpg")

        // "media", "DCIM", "Camera" each ensured exactly once despite two files.
        assertEquals(1, drive.folders.count { it.startsWith("Camera@") })
        assertFalse(drive.folders.isEmpty())
        assertEquals(2, drive.streamUploads.size)
    }

    @Test fun probes_real_length_when_mediastore_size_is_zero() {
        // Reproduces the LP3 bug: _size=0 for a file that is really 6 bytes. The sink must probe
        // lengthOf and advertise the REAL length, not 0 (which got the upload rejected + dropped).
        val drive = FakeDrive()
        val manifest = UploadManifest(File(tmp.root, "m.properties"))
        var lengthProbed = false
        val sink = DriveMediaSink(
            openStream = { ByteArrayInputStream(ByteArray(6)) },
            lengthOf = { lengthProbed = true; 6L },
            drive = drive,
            folders = DriveFolderTree(drive, "ROOT"),
            manifest = manifest,
        )

        sink.put(photo(0), "media/Pictures/Light/img.jpg") // record says size 0

        assertTrue("must fall back to lengthOf when _size is 0", lengthProbed)
        assertEquals(1, sink.uploaded)
        assertEquals(6L, sink.bytesUploaded)
        assertTrue(drive.streamUploads.single().endsWith("len=6"))
    }

    @Test fun rejects_when_length_is_unknown() {
        val drive = FakeDrive()
        val manifest = UploadManifest(File(tmp.root, "m.properties"))
        val sink = DriveMediaSink(
            openStream = { ByteArrayInputStream(ByteArray(6)) },
            lengthOf = { -1L }, // descriptor couldn't tell us either
            drive = drive,
            folders = DriveFolderTree(drive, "ROOT"),
            manifest = manifest,
        )

        // Better to fail loudly (→ retried next run) than upload a wrong Content-Length.
        try {
            sink.put(photo(0), "media/Pictures/Light/img.jpg")
            org.junit.Assert.fail("expected an exception for unknown length")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertTrue(drive.streamUploads.isEmpty())
    }
}
