package com.diffuse.drive

import com.diffuse.backup.DocumentUpload
import com.diffuse.backup.format.ArchiveDocument
import com.diffuse.drive.store.RecentUploads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriveFileSinkTest {
    @get:Rule val tmp = TemporaryFolder()

    private val drive = FakeDrive()

    private fun sink() = DriveFileSink(
        drive,
        DriveFolderTree(drive, "ROOT"),
        DriveInventory.load(drive, RecentUploads(File(tmp.root, "recent.tsv")), emptyMap()),
    )

    private fun doc(name: String, fingerprint: String) = ArchiveDocument(
        file = File(tmp.root, name).apply { writeText("<doc/>") },
        name = name,
        recordCount = 1,
        fingerprint = fingerprint,
    )

    @Test fun creates_a_new_document_at_the_root_tagged_with_its_fingerprint() {
        assertEquals(DocumentUpload.Created, sink().put(doc("sms-2026-11.xml", "f1")))
        assertTrue(drive.created.single().startsWith("sms-2026-11.xml@ROOT"))
        val tags = drive.files.single().appProperties
        assertEquals("doc:sms-2026-11.xml", tags[DriveInventory.KEY_PROPERTY])
        assertEquals("f1", tags[DriveInventory.VERSION_PROPERTY])
        assertTrue("a top-level doc never creates a subfolder", drive.folders.isEmpty())
    }

    @Test fun unchanged_content_is_not_uploaded_again() {
        sink().put(doc("sms-2026-11.xml", "f1"))
        assertEquals(DocumentUpload.Unchanged, sink().put(doc("sms-2026-11.xml", "f1")))
        assertEquals(1, drive.created.size)
        assertTrue(drive.contentUpdates.isEmpty())
    }

    @Test fun changed_content_overwrites_the_same_file() {
        sink().put(doc("sms-2026-11.xml", "f1"))
        val id = drive.files.single().id

        assertEquals(DocumentUpload.Updated, sink().put(doc("sms-2026-11.xml", "f2")))
        assertEquals(listOf(id), drive.contentUpdates)
        assertEquals("f2", drive.files.single().appProperties[DriveInventory.VERSION_PROPERTY])
    }

    @Test fun a_new_month_starts_a_new_file_and_leaves_the_old_one_alone() {
        sink().put(doc("sms-2026-10.xml", "f1"))
        assertEquals(DocumentUpload.Created, sink().put(doc("sms-2026-11.xml", "f2")))
        assertEquals(2, drive.files.size)
        assertTrue(drive.contentUpdates.isEmpty())
    }

    @Test fun a_document_deleted_since_the_listing_is_recreated() {
        drive.files += DriveFile("GONE", "calls-2026-11.xml", DriveInventory.tags("doc:calls-2026-11.xml", "old"))
        val stale = sink() // its listing still shows GONE
        drive.files.clear() // deleted between the listing and the upload

        assertEquals(DocumentUpload.Created, stale.put(doc("calls-2026-11.xml", "new")))
        assertEquals(1, drive.files.size)
    }
}
