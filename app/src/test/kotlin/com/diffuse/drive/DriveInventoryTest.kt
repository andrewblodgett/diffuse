package com.diffuse.drive

import com.diffuse.drive.store.RecentUploads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriveInventoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun journal() = RecentUploads(File(tmp.root, "recent.tsv"))

    @Test fun the_newest_file_for_a_key_wins() {
        val files = listOf(
            DriveFile("OLD", "a.jpg", DriveInventory.tags("media:images:1", "4")),
            DriveFile("NEW", "a (edited).jpg", DriveInventory.tags("media:images:1", "9")),
        )
        val entry = DriveInventory(files, journal())["media:images:1"]!!
        assertEquals("NEW", entry.fileId)
        assertEquals("9", entry.version)
    }

    @Test fun untagged_files_are_matched_only_through_the_legacy_manifest() {
        val files = listOf(DriveFile("L1", "a.jpg"), DriveFile("STRAY", "b.jpg"))
        val inv = DriveInventory(files, journal(), mapOf("media/a.jpg" to "L1", "media/gone.jpg" to "DELETED"))
        assertEquals("L1", inv.legacyAt("media/a.jpg")!!.fileId)
        assertNull("a legacy file no longer on Drive is not offered", inv.legacyAt("media/gone.jpg"))
        assertTrue(inv.isNameTaken("b.jpg"))
    }

    @Test fun recent_uploads_fill_in_for_a_lagging_listing_but_never_override_it() {
        journal().record(RecentUploads.Entry("media:images:1", "JUST_UPLOADED", "a.jpg", "4"))
        journal().record(RecentUploads.Entry("media:images:2", "LISTED", "b.jpg", "4"))
        val listed = listOf(DriveFile("LISTED", "b.jpg", DriveInventory.tags("media:images:2", "4")))

        val inv = DriveInventory(listed, journal())
        assertEquals("JUST_UPLOADED", inv["media:images:1"]!!.fileId)
        assertEquals("LISTED", inv["media:images:2"]!!.fileId)
    }

    @Test fun recordUpload_updates_the_inventory_and_the_journal() {
        val inv = DriveInventory(emptyList(), journal())
        inv.recordUpload("doc:sms-2026-11.xml", DriveInventory.Entry("D", "sms-2026-11.xml", "f"))
        assertEquals("D", inv["doc:sms-2026-11.xml"]!!.fileId)
        assertEquals("D", journal().load().single().fileId)
    }
}
