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

    /** Records folder creation and uploads; returns synthetic ids. */
    private class FakeDrive : DriveApi {
        val folders = mutableListOf<String>()   // "name@parent"
        val uploads = mutableListOf<String>()    // "name@parentId"
        private var seq = 0
        private val folderIds = HashMap<String, String>()
        override fun ensureFolder(name: String, parentId: String?): String =
            folderIds.getOrPut("$parentId/$name") { folders.add("$name@$parentId"); "folder${seq++}" }
        override fun upload(name: String, parentId: String, mimeType: String, file: File): String {
            uploads.add("$name@$parentId"); return "file${seq++}"
        }
        override fun upload(name: String, parentId: String, mimeType: String, length: Long, open: () -> InputStream): String =
            error("file sink must use the File-based upload overload")
    }

    private fun archiveFile(name: String) = File(tmp.root, name).apply { writeText("<doc/>") }

    @Test fun uploads_to_root_and_records_manifest() {
        val drive = FakeDrive()
        val manifest = UploadManifest(File(tmp.root, "manifest.properties"))
        val sink = DriveFileSink(drive, DriveFolderTree(drive, "ROOT"), manifest)

        sink.put(archiveFile("calls-1.xml"), "calls-1.xml")

        assertEquals(1, sink.uploaded)
        assertEquals(0, sink.skipped)
        assertTrue(drive.uploads.any { it.startsWith("calls-1.xml@ROOT") })
        assertTrue(manifest.contains("calls-1.xml"))
        // A top-level doc never creates a subfolder.
        assertTrue(drive.folders.isEmpty())
    }

    @Test fun skips_when_manifest_already_has_it() {
        val drive = FakeDrive()
        val manifest = UploadManifest(File(tmp.root, "manifest.properties")).apply {
            record("sms-1.xml", "existingId")
        }
        val sink = DriveFileSink(drive, DriveFolderTree(drive, "ROOT"), manifest)

        sink.put(archiveFile("sms-1.xml"), "sms-1.xml")

        assertEquals(0, sink.uploaded)
        assertEquals(1, sink.skipped)
        assertTrue(drive.uploads.isEmpty())
    }

    @Test fun second_run_skips_already_uploaded() {
        val manifestFile = File(tmp.root, "manifest.properties")
        val file = archiveFile("photos-1.xml")

        val drive1 = FakeDrive()
        val first = DriveFileSink(drive1, DriveFolderTree(drive1, "ROOT"), UploadManifest(manifestFile))
        first.put(file, "photos-1.xml")
        assertEquals(1, first.uploaded)

        // Fresh manifest instance reads the persisted state → skipped without hitting Drive.
        val drive2 = FakeDrive()
        val second = DriveFileSink(drive2, DriveFolderTree(drive2, "ROOT"), UploadManifest(manifestFile))
        second.put(file, "photos-1.xml")

        assertEquals(0, second.uploaded)
        assertEquals(1, second.skipped)
        assertTrue(drive2.uploads.isEmpty())
    }
}
