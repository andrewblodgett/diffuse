package com.diffuse.drive

import com.diffuse.backup.MediaResult
import com.diffuse.backup.model.MediaKind
import com.diffuse.backup.model.MediaRecord
import com.diffuse.drive.store.RecentUploads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate

class DriveMediaSinkTest {
    @get:Rule val tmp = TemporaryFolder()

    private val drive = FakeDrive()
    private val journal by lazy { RecentUploads(File(tmp.root, "recent.tsv")) }
    private val opened = mutableListOf<String>()

    /** The real byte length of each content URI, as the file descriptor would report it. */
    private val lengths = mutableMapOf<String, Long>()

    private fun photo(id: Long, name: String = "photo.jpg", size: Long = 4) = MediaRecord(
        id = id,
        kind = MediaKind.IMAGE,
        displayName = name,
        relativePath = "DCIM/Camera/",
        mimeType = "image/jpeg",
        sizeBytes = 0, // the LP3 reports _size=0 for fresh captures; the sink must not care
        dateTakenEpochMs = null,
        dateAddedEpochMs = 0,
        dateModifiedEpochMs = 0,
        generationModified = 0,
        contentUri = "content://media/external/images/media/$id",
    ).also { lengths[it.contentUri] = size }

    /** A sink for one "run": a fresh inventory built from what's on the fake Drive now. */
    private fun sink(legacy: Map<String, String> = emptyMap()) = DriveMediaSink(
        openStream = { uri -> opened += uri; ByteArrayInputStream(ByteArray(lengths.getValue(uri).toInt())) },
        lengthOf = { uri -> lengths[uri] ?: -1L },
        drive = drive,
        folders = DriveFolderTree(drive, "ROOT"),
        inventory = DriveInventory.load(drive, journal, legacy),
        today = { LocalDate.of(2026, 11, 19) },
    )

    @Test fun uploads_a_new_item_tagged_with_its_identity_and_real_length() {
        val result = sink().put(photo(1, size = 6)) as MediaResult.Stored

        assertTrue(result.uploadedNow)
        assertEquals("media/DCIM/Camera/photo.jpg", result.backupPath)
        val file = drive.files.single()
        assertEquals(result.remoteId, file.id)
        assertEquals("media:images:1", file.appProperties[DriveInventory.KEY_PROPERTY])
        assertEquals("6", file.appProperties[DriveInventory.VERSION_PROPERTY])
        assertTrue(drive.created.single().endsWith("len=6")) // real length, not _size=0
        assertTrue(drive.folders.containsAll(listOf("media@ROOT", "DCIM@folder0")))
    }

    @Test fun the_next_run_recognises_it_without_reading_the_file() {
        sink().put(photo(1))
        opened.clear()

        val again = sink().put(photo(1)) as MediaResult.Stored

        assertEquals(false, again.uploadedNow)
        assertEquals(1, drive.files.size)
        assertTrue("an unchanged item must not be streamed again", opened.isEmpty())
    }

    @Test fun recognised_after_reinstall_from_drive_alone() {
        sink().put(photo(1))
        File(tmp.root, "recent.tsv").delete() // a new install has no local state at all

        val again = sink().put(photo(1)) as MediaResult.Stored
        assertEquals(false, again.uploadedNow)
        assertEquals(1, drive.files.size)
    }

    @Test fun an_upload_the_listing_does_not_show_yet_is_still_recognised() {
        val first = sink()
        first.put(photo(1))
        val listedLate = drive.files.toList()
        drive.files.clear() // Drive's listing lags behind the upload...

        val again = sink().put(photo(1)) as MediaResult.Stored // ...the journal covers the gap
        assertEquals(false, again.uploadedNow)
        assertEquals(listedLate.single().id, again.remoteId)
    }

    @Test fun a_deleted_drive_file_is_uploaded_again() {
        sink().put(photo(1))
        File(tmp.root, "recent.tsv").delete()
        drive.files.clear() // the user emptied the Diffuse folder

        val again = sink().put(photo(1)) as MediaResult.Stored
        assertTrue(again.uploadedNow)
    }

    @Test fun an_edited_item_is_uploaded_next_to_the_original() {
        sink().put(photo(1, size = 4))

        val edited = sink().put(photo(1, size = 9)) as MediaResult.Stored

        assertTrue(edited.uploadedNow)
        assertEquals("media/DCIM/Camera/photo (edited 2026-11-19).jpg", edited.backupPath)
        assertEquals(2, drive.files.size) // the original is kept
        // And the edited version is now the one recognised.
        assertEquals(false, (sink().put(photo(1, size = 9)) as MediaResult.Stored).uploadedNow)
    }

    @Test fun two_items_with_the_same_name_both_survive() {
        val s = sink()
        val a = s.put(photo(1, name = "IMG.jpg")) as MediaResult.Stored
        val b = s.put(photo(2, name = "IMG.jpg")) as MediaResult.Stored

        assertEquals("media/DCIM/Camera/IMG.jpg", a.backupPath)
        assertEquals("media/DCIM/Camera/IMG (2).jpg", b.backupPath)
        assertEquals(2, drive.files.size)
    }

    @Test fun an_empty_file_is_skipped_not_failed() {
        assertEquals(MediaResult.Empty, sink().put(photo(1, size = 0)))
        assertTrue(drive.files.isEmpty())
    }

    @Test fun unknown_length_fails_the_item() {
        val record = photo(1)
        lengths.remove(record.contentUri)
        assertThrows(IllegalArgumentException::class.java) { sink().put(record) }
        assertTrue(drive.files.isEmpty())
    }

    @Test fun a_file_from_version_0_1_is_adopted_and_tagged_instead_of_re_uploaded() {
        drive.files += DriveFile("OLD", "photo.jpg") // untagged, as 0.1.x uploaded it
        val legacy = mapOf("media/DCIM/Camera/photo.jpg" to "OLD")

        val result = sink(legacy).put(photo(1)) as MediaResult.Stored

        assertEquals(false, result.uploadedNow)
        assertEquals("OLD", result.remoteId)
        assertEquals(listOf("OLD"), drive.tagged)
        assertTrue(drive.created.isEmpty())
        // Once tagged, later runs need no legacy data.
        assertEquals(false, (sink().put(photo(1)) as MediaResult.Stored).uploadedNow)
    }

    @Test fun folder_tree_is_created_once_across_many_files() {
        val s = sink()
        s.put(photo(1, name = "a.jpg"))
        s.put(photo(2, name = "b.jpg"))
        assertEquals(1, drive.folders.count { it.startsWith("Camera@") })
        assertEquals(2, drive.created.size)
    }
}
