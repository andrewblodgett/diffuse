package com.diffuse.backup

import com.diffuse.backup.format.ArchiveDocument
import com.diffuse.backup.model.CallRecord
import com.diffuse.backup.model.MediaKind
import com.diffuse.backup.model.MediaRecord
import com.diffuse.backup.model.MmsRecord
import com.diffuse.backup.model.SmsRecord
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.YearMonth

class BackupRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class ListSource<T>(private val items: List<T>, private val reportedCount: Int = items.size) : BackupSource<T> {
        override fun count() = reportedCount
        override fun items() = items.asFlow()
    }

    private fun call(id: Long) = CallRecord(id, "555", 10, 1_000L * id, 1, 1, null)
    private fun sms(id: Long) = SmsRecord(id, "555", 1_000L * id, 0, 1, "hi $id", null, 1, -1, null, null, 0, -1)
    private fun photo(id: Long) = MediaRecord(id, MediaKind.IMAGE, "p$id.jpg", "DCIM/", "image/jpeg", 1, null, 0, 0, 0, "content://x/$id")

    private fun sources(
        calls: List<CallRecord> = listOf(call(1), call(2)),
        sms: List<SmsRecord> = listOf(sms(1)),
        mms: List<MmsRecord> = emptyList(),
        photos: List<MediaRecord> = listOf(photo(1), photo(2)),
        videos: List<MediaRecord> = emptyList(),
        reportedSmsCount: Int = sms.size,
    ) = BackupSources(
        calls = ListSource(calls),
        sms = ListSource(sms, reportedSmsCount),
        mms = ListSource(mms),
        images = ListSource(photos),
        videos = ListSource(videos),
    )

    /** Captures each uploaded document's name and full text (the runner deletes nothing itself). */
    private class CapturingFileSink : FileSink {
        val docs = linkedMapOf<String, String>()
        val fingerprints = mutableMapOf<String, String>()
        override fun put(document: ArchiveDocument): DocumentUpload {
            docs[document.name] = document.file.readText()
            fingerprints[document.name] = document.fingerprint
            return DocumentUpload.Created
        }
    }

    private fun runner(
        sources: BackupSources,
        files: FileSink = CapturingFileSink(),
        media: MediaSink = MediaSink.NONE,
        content: BackupContent = BackupContent(),
        progress: BackupProgress = BackupProgress.NONE,
        backupDateMs: Long = 0,
    ) = BackupRunner(sources, tmp.root, YearMonth.of(2026, 11), media, files, progress, content, backupDateMs)

    @Test fun writes_monthly_snapshots_in_the_announced_order() = runTest {
        val files = CapturingFileSink()
        val stages = mutableListOf<BackupStage>()
        val progress = object : BackupProgress {
            override fun onStage(stage: BackupStage) { stages += stage }
        }
        runner(sources(videos = listOf(photo(9).copy(kind = MediaKind.VIDEO))), files, progress = progress).run()

        assertEquals(
            listOf("calls-2026-11.xml", "sms-2026-11.xml", "photos-2026-11.xml", "videos-2026-11.xml"),
            files.docs.keys.toList(),
        )
        assertEquals(listOf(BackupStage.Calls, BackupStage.Messages, BackupStage.Photos, BackupStage.Videos), stages)
    }

    @Test fun header_count_is_what_was_written_even_if_the_provider_changed() = runTest {
        val files = CapturingFileSink()
        // count() said 5, but only 2 rows came back (messages were deleted mid-backup).
        runner(sources(sms = listOf(sms(1), sms(2)), reportedSmsCount = 5), files).run()
        assertTrue(files.docs.getValue("sms-2026-11.xml").contains("<smses count=\"2\""))
    }

    @Test fun same_data_on_a_later_run_has_the_same_fingerprint() = runTest {
        val first = CapturingFileSink()
        val second = CapturingFileSink()
        runner(sources(), first, backupDateMs = 1).run()
        runner(sources(), second, backupDateMs = 2).run() // different backup_date and backup_set
        assertEquals(first.fingerprints, second.fingerprints)
    }

    @Test fun an_empty_source_uploads_nothing() = runTest {
        val files = CapturingFileSink()
        runner(sources(calls = emptyList()), files).run()
        assertFalse(files.docs.containsKey("calls-2026-11.xml"))
    }

    @Test fun disabled_categories_are_skipped() = runTest {
        val files = CapturingFileSink()
        val summary = runner(sources(), files, content = BackupContent(pictures = true, videos = false, messages = false)).run()
        assertEquals(listOf("photos-2026-11.xml"), files.docs.keys.toList())
        assertEquals(0, summary.calls)
    }

    @Test fun messages_back_up_without_the_call_log_and_vice_versa() = runTest {
        val onlyMessages = CapturingFileSink()
        runner(sources(), onlyMessages, content = BackupContent(pictures = false, videos = false, messages = true, calls = false)).run()
        assertEquals(listOf("sms-2026-11.xml"), onlyMessages.docs.keys.toList())

        val onlyCalls = CapturingFileSink()
        runner(sources(), onlyCalls, content = BackupContent(pictures = false, videos = false, messages = false, calls = true)).run()
        assertEquals(listOf("calls-2026-11.xml"), onlyCalls.docs.keys.toList())
    }

    @Test fun a_failed_item_is_counted_and_left_out_of_the_index() = runTest {
        val files = CapturingFileSink()
        val media = MediaSink { r ->
            if (r.id == 2L) throw IOException("unreadable")
            MediaResult.Stored("media/DCIM/${r.displayName}", "drive-${r.id}", uploadedNow = true)
        }
        val summary = runner(sources(photos = listOf(photo(1), photo(2), photo(3))), files, media).run()

        assertEquals(MediaTally(inLibrary = 3, uploaded = 2, alreadyBackedUp = 0, failed = 1), summary.photos)
        assertEquals(1, summary.failedItems)
        val index = files.docs.getValue("photos-2026-11.xml")
        assertTrue(index.contains("<medias count=\"2\""))
        assertTrue(index.contains("drive_file_id=\"drive-1\""))
        assertFalse(index.contains("p2.jpg"))
    }

    @Test fun several_failures_in_a_row_abort_the_run() = runTest {
        var attempts = 0
        val media = MediaSink { attempts++; throw IOException("network is down") }
        val photos = (1L..10L).map(::photo)

        val error = try {
            runner(sources(photos = photos), media = media).run()
            null
        } catch (e: MediaUploadAbortedException) {
            e
        }
        assertEquals(3, attempts) // stopped instead of trying all 10
        assertTrue(error!!.message!!.contains("network is down"))
    }

    @Test fun empty_media_files_are_neither_failures_nor_indexed() = runTest {
        val files = CapturingFileSink()
        val media = MediaSink { MediaResult.Empty }
        val summary = runner(sources(photos = listOf(photo(1))), files, media).run()
        assertEquals(0, summary.failedItems)
        assertFalse("nothing to index → no document", files.docs.containsKey("photos-2026-11.xml"))
    }

    @Test fun reports_media_progress_per_item() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()
        val progress = object : BackupProgress {
            override fun onPhotoProgress(done: Int, total: Int) { seen += done to total }
        }
        runner(sources(), progress = progress).run()
        assertEquals(listOf(1 to 2, 2 to 2), seen)
    }
}
