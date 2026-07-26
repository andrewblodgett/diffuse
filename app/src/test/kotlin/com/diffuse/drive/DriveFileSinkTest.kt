package com.diffuse.drive

import com.diffuse.drive.store.UploadManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class DriveFileSinkTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Records folder creation, creates (upload), and overwrites (update); returns synthetic ids. */
    private class FakeDrive : DriveApi {
        val folders = mutableListOf<String>()   // "name@parent"
        val uploads = mutableListOf<String>()    // "name@parentId"
        val updates = mutableListOf<String>()    // fileId overwritten
        private var seq = 0
        private val folderIds = HashMap<String, String>()
        override fun ensureFolder(name: String, parentId: String?): String =
            folderIds.getOrPut("$parentId/$name") { folders.add("$name@$parentId"); "folder${seq++}" }
        override fun upload(name: String, parentId: String, mimeType: String, file: File): String {
            uploads.add("$name@$parentId"); return "file${seq++}"
        }
        override fun update(fileId: String, mimeType: String, file: File) { updates.add(fileId) }
        override fun upload(name: String, parentId: String, mimeType: String, length: Long, open: () -> InputStream): String =
            error("file sink must use the File-based upload overload")
    }

    private fun archiveFile(name: String) = File(tmp.root, name).apply { writeText("<doc/>") }

    @Test fun creates_at_root_and_records_manifest_when_new() {
        val drive = FakeDrive()
        val manifest = UploadManifest(File(tmp.root, "manifest.properties"))
        val sink = DriveFileSink(drive, DriveFolderTree(drive, "ROOT"), manifest)

        sink.put(archiveFile("calls.xml"), "calls.xml")

        assertEquals(1, sink.uploaded)
        assertEquals(0, sink.updated)
        assertTrue(drive.uploads.any { it.startsWith("calls.xml@ROOT") })
        assertTrue(manifest.contains("calls.xml"))
        // A top-level doc never creates a subfolder.
        assertTrue(drive.folders.isEmpty())
    }

    @Test fun overwrites_in_place_when_manifest_already_has_it() {
        val drive = FakeDrive()
        val manifest = UploadManifest(File(tmp.root, "manifest.properties")).apply {
            record("sms.xml", "existingId")
        }
        val sink = DriveFileSink(drive, DriveFolderTree(drive, "ROOT"), manifest)

        sink.put(archiveFile("sms.xml"), "sms.xml")

        assertEquals(0, sink.uploaded)
        assertEquals(1, sink.updated)
        // Overwrites the existing file id; never creates a second copy.
        assertEquals(listOf("existingId"), drive.updates)
        assertTrue(drive.uploads.isEmpty())
    }

    @Test fun second_run_overwrites_the_same_drive_file() {
        val manifestFile = File(tmp.root, "manifest.properties")
        val file = archiveFile("photos.xml")

        val drive1 = FakeDrive()
        val first = DriveFileSink(drive1, DriveFolderTree(drive1, "ROOT"), UploadManifest(manifestFile))
        first.put(file, "photos.xml")
        assertEquals(1, first.uploaded)
        val createdId = drive1.uploads // one create happened

        // Fresh manifest instance reads the persisted state → overwrites in place, no new create.
        val drive2 = FakeDrive()
        val second = DriveFileSink(drive2, DriveFolderTree(drive2, "ROOT"), UploadManifest(manifestFile))
        second.put(file, "photos.xml")

        assertEquals(0, second.uploaded)
        assertEquals(1, second.updated)
        assertTrue(drive2.uploads.isEmpty())
        assertEquals(1, drive2.updates.size)
        assertTrue(createdId.isNotEmpty())
    }
}
