package com.diffuse.drive.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadManifestTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun records_round_trip_and_survive_reopen() {
        val file = File(tmp.root, "m.properties")
        UploadManifest(file).record("media/a.jpg", "id1")
        val reopened = UploadManifest(file)
        assertTrue(reopened.contains("media/a.jpg"))
        assertEquals("id1", reopened.fileId("media/a.jpg"))
    }

    @Test fun retain_is_a_no_op_when_everything_still_lives_on_drive() {
        val file = File(tmp.root, "m.properties")
        val manifest = UploadManifest(file)
        manifest.record("media/a.jpg", "id1")
        manifest.record("media/b.jpg", "id2")

        val pruned = manifest.retain(setOf("id1", "id2", "id3-not-ours"))

        assertFalse(pruned)
        assertTrue(manifest.contains("media/a.jpg"))
        assertTrue(manifest.contains("media/b.jpg"))
    }

    @Test fun retain_drops_entries_missing_from_drive_and_persists() {
        val file = File(tmp.root, "m.properties")
        val manifest = UploadManifest(file)
        manifest.record("media/a.jpg", "id1")
        manifest.record("media/b.jpg", "id2")

        // Drive still has "id1" but "id2" was deleted (e.g. the user emptied the folder).
        val pruned = manifest.retain(setOf("id1"))

        assertTrue(pruned)
        assertTrue(manifest.contains("media/a.jpg"))
        assertFalse(manifest.contains("media/b.jpg"))
        // Survives reopening from disk.
        val reopened = UploadManifest(file)
        assertFalse(reopened.contains("media/b.jpg"))
    }

    @Test fun retain_with_empty_drive_drops_everything() {
        val file = File(tmp.root, "m.properties")
        val manifest = UploadManifest(file)
        manifest.record("media/a.jpg", "id1")
        manifest.record("sms-1.xml", "id2")

        val pruned = manifest.retain(emptySet())

        assertTrue(pruned)
        assertFalse(manifest.contains("media/a.jpg"))
        assertFalse(manifest.contains("sms-1.xml"))
    }
}
