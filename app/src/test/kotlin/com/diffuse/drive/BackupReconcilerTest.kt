package com.diffuse.drive

import com.diffuse.backup.store.TokenStore
import com.diffuse.drive.store.UploadManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private class InMemoryTokenStore : TokenStore {
        val values = mutableMapOf<String, Long>()
        override fun get(sourceId: String) = values[sourceId]
        override fun put(sourceId: String, token: Long) { values[sourceId] = token }
        override fun clear(sourceId: String) { values.remove(sourceId) }
    }

    @Test fun leaves_tokens_alone_when_everything_manifest_knows_is_still_on_drive() {
        val manifest = UploadManifest(File(tmp.root, "m.properties")).apply {
            record("media/a.jpg", "id1")
        }
        val tokens = InMemoryTokenStore().apply { put("images", 42L) }
        val reconciler = BackupReconciler(FakeDrive(setOf("id1")), manifest, tokens, listOf("images"))

        reconciler.reconcile()

        assertTrue(manifest.contains("media/a.jpg"))
        assertEquals(42L, tokens.get("images"))
    }

    @Test fun prunes_manifest_and_clears_every_token_when_drive_lost_a_file() {
        val manifest = UploadManifest(File(tmp.root, "m.properties")).apply {
            record("media/a.jpg", "id1")
            record("media/b.jpg", "id2")
        }
        // Manifest thinks both are uploaded; Drive only actually has id1 (id2 was deleted).
        val tokens = InMemoryTokenStore().apply {
            put("images", 42L)
            put("sms", 7L)
        }
        val reconciler = BackupReconciler(FakeDrive(setOf("id1")), manifest, tokens, listOf("images", "sms"))

        reconciler.reconcile()

        assertTrue(manifest.contains("media/a.jpg"))
        assertTrue("stale entry must be pruned so it gets re-uploaded", !manifest.contains("media/b.jpg"))
        assertNull("every token is cleared, not just the media one", tokens.get("images"))
        assertNull(tokens.get("sms"))
    }

    @Test fun empty_drive_wipes_manifest_and_all_tokens() {
        val manifest = UploadManifest(File(tmp.root, "m.properties")).apply {
            record("media/a.jpg", "id1")
        }
        val tokens = InMemoryTokenStore().apply { put("images", 1L) }
        val reconciler = BackupReconciler(FakeDrive(emptySet()), manifest, tokens, listOf("images"))

        reconciler.reconcile()

        assertTrue(!manifest.contains("media/a.jpg"))
        assertNull(tokens.get("images"))
    }
}
