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
        assertEquals("L1", inv.takeLegacy("media/a.jpg")!!.fileId)
        assertNull("each legacy file can be claimed only once", inv.takeLegacy("media/a.jpg"))
        assertNull("a legacy file no longer on Drive is not offered", inv.takeLegacy("media/gone.jpg"))
        assertTrue(inv.isNameTaken("b.jpg"))
    }

    @Test fun media_lookups_require_the_same_origin() {
        val files = listOf(
            DriveFile("OLD_PHONE", "a.jpg", DriveInventory.mediaTags("media:images:1", "4", "origin-A", "media/DCIM")),
            DriveFile("NEW_PHONE", "b.jpg", DriveInventory.mediaTags("media:images:1", "9", "origin-B", "media/DCIM")),
        )
        val inv = DriveInventory(files, journal())
        assertEquals("OLD_PHONE", inv.find("media:images:1", "origin-A")!!.fileId)
        assertEquals("NEW_PHONE", inv.find("media:images:1", "origin-B")!!.fileId)
        assertNull(inv.find("media:images:1", "origin-C"))
    }

    @Test fun a_folder_too_long_for_a_drive_property_is_left_out() {
        assertEquals("media/DCIM", DriveInventory.mediaTags("k", "1", "o", "media/DCIM")[DriveInventory.FOLDER_PROPERTY])
        val long = "media/" + "x".repeat(200)
        assertNull(DriveInventory.mediaTags("k", "1", "o", long)[DriveInventory.FOLDER_PROPERTY])
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
        inv.recordUpload("doc:A:sms-2026-11.xml", DriveInventory.Entry("D", "sms-2026-11.xml", "f"))
        assertEquals("D", inv["doc:A:sms-2026-11.xml"]!!.fileId)
        assertEquals("D", journal().load().single().fileId)
    }
}
