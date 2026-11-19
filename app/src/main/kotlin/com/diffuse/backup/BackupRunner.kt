package com.diffuse.backup

import android.util.Log
import com.diffuse.backup.format.ArchiveDocument
import com.diffuse.backup.format.ArchiveDocumentBuilder
import com.diffuse.backup.format.CallLogXmlWriter
import com.diffuse.backup.format.MediaIndexXmlWriter
import com.diffuse.backup.format.SmsBackupXmlWriter
import com.diffuse.backup.model.MediaRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth
import java.util.UUID

/** How one media category (photos or videos) went in a run. */
data class MediaTally(
    /** Items MediaStore reported when the stage started (progress denominator). */
    val inLibrary: Int = 0,
    /** Items whose bytes were uploaded during this run. */
    val uploaded: Int = 0,
    /** Items whose exact version was already backed up. */
    val alreadyBackedUp: Int = 0,
    /** Items that could not be backed up this run; the next run retries them. */
    val failed: Int = 0,
)

/** Per-source counts for one run. */
data class BackupSummary(
    val calls: Int = 0,
    val sms: Int = 0,
    val mms: Int = 0,
    val photos: MediaTally = MediaTally(),
    val videos: MediaTally = MediaTally(),
) {
    val failedItems: Int get() = photos.failed + videos.failed
}

/**
 * Thrown when several media items in a row fail. That pattern means something run-wide is
 * wrong (the network dropped, Drive is refusing uploads), so carrying on would only burn
 * through every remaining item's retries and timeouts. Aborting fails the run, which the
 * scheduler retries later.
 */
class MediaUploadAbortedException(kind: String, failures: Int, cause: Throwable) :
    Exception("Stopped after $failures $kind in a row failed: ${cause.message}", cause)

/**
 * Runs a **full** export of every enabled source, uploading each document via [fileSink]
 * the moment it's written, always in the same order: **call log, then messages, then
 * photos, then videos**. That fixed order is what [progress] announces, so what the UI says
 * and what's actually uploading always agree.
 *
 * Documents are **monthly snapshots** named for [month]: `calls-2026-11.xml`,
 * `sms-2026-11.xml`, `photos-2026-11.xml`, `videos-2026-11.xml`. Every run in a month
 * rewrites that month's documents with the complete current export, and when the month
 * ends they stay frozen on Drive. So deleting a message on the phone only removes it from
 * the *current* month's snapshot; every earlier month still has it. A source with no rows
 * produces no document at all.
 *
 * Media *originals* are handed to [mediaSink], which streams them provider→Drive and skips
 * any item whose exact version is already there; only the small index XML is written
 * locally. One failed item is counted and left out of this run's index (the next run
 * retries it); [maxConsecutiveMediaFailures] failures in a row abort the run.
 *
 * READ-ONLY: every source reads its provider; the only local writes are files under
 * [workDir] (our own sandbox), which is not a provider mutation.
 */
class BackupRunner(
    private val sources: BackupSources,
    workDir: File,
    private val month: YearMonth,
    private val mediaSink: MediaSink = MediaSink.NONE,
    private val fileSink: FileSink = FileSink.NONE,
    private val progress: BackupProgress = BackupProgress.NONE,
    private val content: BackupContent = BackupContent(),
    private val backupDateMs: Long = System.currentTimeMillis(),
    private val maxConsecutiveMediaFailures: Int = 3,
) {
    private val documents = ArchiveDocumentBuilder(workDir)
    private val backupSet = UUID.randomUUID().toString()

    suspend fun run(): BackupSummary = withContext(Dispatchers.IO) {
        var summary = BackupSummary()
        if (content.calls) {
            progress.onStage(BackupStage.Calls)
            summary = summary.copy(calls = backUpCalls())
        }
        if (content.messages) {
            progress.onStage(BackupStage.Messages)
            val (sms, mms) = backUpMessages()
            summary = summary.copy(sms = sms, mms = mms)
        }
        if (content.pictures) {
            progress.onStage(BackupStage.Photos)
            summary = summary.copy(
                photos = backUpMedia("photos", sources.images, progress::onPhotoProgress),
            )
        }
        if (content.videos) {
            progress.onStage(BackupStage.Videos)
            summary = summary.copy(
                videos = backUpMedia("videos", sources.videos, progress::onVideoProgress),
            )
        }
        summary
    }

    private suspend fun backUpCalls(): Int {
        val writer = CallLogXmlWriter(backupDateMs, backupSet)
        val doc = documents.build("calls-$month.xml", writer::header, writer::footer) { out ->
            var n = 0
            sources.calls.items().collect { writer.call(out, it); n++ }
            n
        }
        doc?.let(::upload)
        return doc?.recordCount ?: 0
    }

    /** Returns (SMS count, MMS count); both go in one `sms-*.xml`, as SMS Backup & Restore expects. */
    private suspend fun backUpMessages(): Pair<Int, Int> {
        val writer = SmsBackupXmlWriter(backupDateMs, backupSet)
        var sms = 0
        var mms = 0
        val doc = documents.build("sms-$month.xml", writer::header, writer::footer) { out ->
            sources.sms.items().collect { writer.sms(out, it); sms++ }
            sources.mms.items().collect { writer.mms(out, it); mms++ }
            sms + mms
        }
        doc?.let(::upload)
        return sms to mms
    }

    /**
     * Streams every item of one media [source] to [mediaSink] and indexes the ones that are
     * backed up, then uploads the index as `<kind>-<month>.xml`.
     */
    private suspend fun backUpMedia(
        kind: String,
        source: BackupSource<MediaRecord>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): MediaTally {
        val total = source.count()
        var tally = MediaTally(inLibrary = total)
        var consecutiveFailures = 0
        var done = 0
        val writer = MediaIndexXmlWriter(backupDateMs, backupSet)
        val doc = documents.build("$kind-$month.xml", writer::header, writer::footer) { out ->
            var indexed = 0
            source.items().collect { record ->
                val result = try {
                    // Interruptible, so a cancelled run (e.g. Wi-Fi lost, charger unplugged)
                    // stops mid-upload instead of finishing a multi-minute video first.
                    runInterruptible { mediaSink.put(record) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A stop mid-upload can surface as an I/O error (InterruptedIOException)
                    // rather than a cancellation; if the run was stopped, that isn't an item failure.
                    currentCoroutineContext().ensureActive()
                    Log.w(TAG, "${record.stableId} failed, will retry next run", e)
                    tally = tally.copy(failed = tally.failed + 1)
                    if (++consecutiveFailures >= maxConsecutiveMediaFailures) {
                        throw MediaUploadAbortedException(kind, consecutiveFailures, e)
                    }
                    null
                }
                when (result) {
                    is MediaResult.Stored -> {
                        consecutiveFailures = 0
                        tally = if (result.uploadedNow) tally.copy(uploaded = tally.uploaded + 1)
                        else tally.copy(alreadyBackedUp = tally.alreadyBackedUp + 1)
                        writer.media(out, record, result.backupPath, result.remoteId)
                        indexed++
                    }
                    MediaResult.Empty -> consecutiveFailures = 0
                    null -> Unit // failed; counted above and left out of the index
                }
                onProgress(++done, total)
            }
            indexed
        }
        doc?.let(::upload)
        return tally
    }

    /** Hand [doc] to the sink, then drop the local copy (an `sms-*.xml` can be 100+ MB). */
    private fun upload(doc: ArchiveDocument) {
        fileSink.put(doc)
        doc.file.delete()
    }

    private companion object {
        const val TAG = "DiffuseBackup"
    }
}
