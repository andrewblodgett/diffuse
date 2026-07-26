package com.diffuse.drive

import com.diffuse.drive.store.UploadManifest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class BackupReconcilerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeDrive(private val liveIds: Set<String>) : DriveApi {
        override fun ensureFolder(name: String, parentId: String?) = error("not used")
        override fun upload(name: String, parentId: String, mimeType: String, file: File) = error("not used")
        override fun upload(name: String, parentId: String, mimeType: String, length: Long, open: () -> InputStream) =
            error("not used")
        override fun listAllFileIds(): Set<String> = liveIds
    }

    @Test fun leaves_manifest_alone_when_everything_it_knows_is_still_on_drive() {
        val manifest = UploadManifest(File(tmp.root, "m.properties")).apply {
            record("media/a.jpg", "id1")
        }
        val reconciler = BackupReconciler(FakeDrive(setOf("id1")), manifest)

        reconciler.reconcile()

        assertTrue(manifest.contains("media/a.jpg"))
    }

    @Test fun prunes_manifest_entries_for_files_drive_no_longer_has() {
        val manifest = UploadManifest(File(tmp.root, "m.properties")).apply {
            record("media/a.jpg", "id1")
            record("media/b.jpg", "id2")
        }
        // Manifest thinks both are uploaded; Drive only actually has id1 (id2 was deleted).
        val reconciler = BackupReconciler(FakeDrive(setOf("id1")), manifest)

        reconciler.reconcile()

        assertTrue(manifest.contains("media/a.jpg"))
        assertTrue("stale entry must be pruned so it gets re-uploaded", !manifest.contains("media/b.jpg"))
    }

    @Test fun empty_drive_wipes_manifest() {
        val manifest = UploadManifest(File(tmp.root, "m.properties")).apply {
            record("media/a.jpg", "id1")
        }
        val reconciler = BackupReconciler(FakeDrive(emptySet()), manifest)

        reconciler.reconcile()

        assertTrue(!manifest.contains("media/a.jpg"))
    }
}
