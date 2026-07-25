package com.diffuse.drive

import com.diffuse.drive.store.UploadManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriveUploaderTest {
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
        override fun upload(name: String, parentId: String, mimeType: String, length: Long, open: () -> java.io.InputStream): String {
            uploads.add("$name@$parentId"); return "file${seq++}"
        }
    }

    private fun archive(): File {
        val dir = tmp.newFolder("backup")
        File(dir, "sms-20260725.xml").writeText("<smses/>")
        File(dir, "calls-20260725.xml").writeText("<calls/>")
        File(dir, "media/Camera").mkdirs()
        File(dir, "media/Camera/photo.jpg").writeBytes(ByteArray(4))
        return dir
    }

    @Test fun mirrors_tree_and_reports_counts() {
        val drive = FakeDrive()
        val manifest = UploadManifest(File(tmp.root, "manifest.properties"))
        val summary = DriveUploader(drive, manifest).upload(archive())

        assertEquals(3, summary.uploaded) // two xml + one photo
        assertEquals(0, summary.skipped)
        // Root "Diffuse" + nested media + media/Camera folders were ensured.
        assertTrue(drive.folders.any { it.startsWith("Diffuse@") })
        assertTrue(drive.folders.any { it.startsWith("media@") })
        assertTrue(drive.folders.any { it.startsWith("Camera@") })
        // The photo went into the Camera folder, not the root.
        assertTrue(drive.uploads.any { it.startsWith("photo.jpg@folder") })
    }

    @Test fun second_run_skips_already_uploaded() {
        val dir = archive()
        val manifestFile = File(tmp.root, "manifest.properties")

        val first = DriveUploader(FakeDrive(), UploadManifest(manifestFile)).upload(dir)
        assertEquals(3, first.uploaded)

        // Fresh manifest instance reads the persisted state → everything skipped.
        val drive2 = FakeDrive()
        val second = DriveUploader(drive2, UploadManifest(manifestFile)).upload(dir)
        assertEquals(0, second.uploaded)
        assertEquals(3, second.skipped)
        assertTrue(drive2.uploads.isEmpty())
    }
}
