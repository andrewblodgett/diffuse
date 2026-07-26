package com.diffuse.backup

import android.content.ContentResolver
import android.util.Log
import com.diffuse.backup.format.CallLogXmlWriter
import com.diffuse.backup.format.MediaIndexXmlWriter
import com.diffuse.backup.format.SmsBackupXmlWriter
import com.diffuse.backup.model.CallRecord
import com.diffuse.backup.model.MediaRecord
import com.diffuse.backup.model.MmsRecord
import com.diffuse.backup.model.SmsRecord
import com.diffuse.backup.provider.CallLogSource
import com.diffuse.backup.provider.MediaSource
import com.diffuse.backup.provider.MmsSource
import com.diffuse.backup.provider.SmsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Per-source counts and where the archive landed. */
data class BackupSummary(
    val smsCount: Int,
    val mmsCount: Int,
    val callCount: Int,
    val imageCount: Int,
    val videoCount: Int,
    val outputDir: String,
)

/**
 * Runs a **full** extraction of every source into a local SMS Backup &
 * Restore-compatible archive under [outputDir], uploading each document via [fileSink]
 * (production: straight to Drive) the moment it's finished — always in the same order:
 * **call log, then messages, then photos, then videos**. That fixed order is what the
 * progress UI announces via [progress], so what it says and what's actually uploading always
 * agree (a real bug: an earlier version wrote all the XML docs first and uploaded them in a
 * separate alphabetical-filename sweep afterward, so the UI said "messages" while the network
 * was actually sending something else, and text data uploaded last regardless of the order
 * announced).
 *
 * Each run re-extracts everything and writes each document to a **stable filename**
 * (`calls.xml`, `sms.xml`, `photos.xml`, `videos.xml`), so every doc is the current, complete
 * export of its source. [fileSink] overwrites the matching file already on Drive rather than
 * uploading a new one, so running the backup twice in a row never accumulates duplicate — or
 * empty — documents (an earlier version stamped each filename with the run time, so the upload
 * manifest could never recognize a doc as already-present: repeat runs piled up a fresh, often
 * metadata-only, copy of every document). A source with **no** rows writes and uploads nothing,
 * leaving any prior doc untouched.
 *
 * Layout produced:
 * ```
 * outputDir/
 *   calls.xml    # <calls>
 *   sms.xml      # <smses> with <sms> + <mms> (attachments inline base64)
 *   photos.xml   # <medias> index for images (references media/<relative_path>/<name>)
 *   videos.xml   # <medias> index for video, same shape
 * ```
 *
 * Media *originals* are not copied under [outputDir] — each is handed to [mediaSink], which in
 * production streams it straight provider→Drive (see [com.diffuse.drive.DriveMediaSink]) and
 * skips anything already recorded in the upload manifest; only the small index XML is written
 * locally. So although every run re-walks the whole media library to rebuild the complete index,
 * the large media bytes upload exactly once and peak on-device disk stays at a copy buffer.
 *
 * READ-ONLY: every source reads its provider; the only local writes are to files under
 * [outputDir] (our own sandbox), which is not a provider mutation.
 */
class BackupRunner(
    private val resolver: ContentResolver,
    private val outputDir: File,
    private val mediaSink: MediaSink = MediaSink.NONE,
    private val fileSink: FileSink = FileSink.NONE,
    private val progress: BackupProgress = BackupProgress.NONE,
    private val content: BackupContent = BackupContent(),
    private val sms: SmsSource = SmsSource(resolver),
    private val mms: MmsSource = MmsSource(resolver),
    private val calls: CallLogSource = CallLogSource(resolver),
    private val images: MediaSource = MediaSource.images(resolver),
    private val video: MediaSource = MediaSource.video(resolver),
) {
    suspend fun run(): BackupSummary = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val backupDateMs = System.currentTimeMillis()
        val backupSet = UUID.randomUUID().toString()

        var nSms = 0
        var nMms = 0
        var nCall = 0
        var nImg = 0
        var nVid = 0

        // Messages and calls are gated by the same "text & call history" choice, but uploaded
        // as two separate documents, call log first.
        if (content.messages) {
            progress.onStage(BackupStage.Calls)
            nCall = calls.countSince(null)
            if (nCall > 0) {
                val callsFile = File(outputDir, "calls.xml")
                callsFile.bufferedWriter().use { w ->
                    val writer = CallLogXmlWriter(w, backupDateMs, backupSet)
                    writer.start(nCall)
                    calls.itemsSince(null).collect { writer.writeCall(it as CallRecord) }
                    writer.finish()
                }
                fileSink.put(callsFile, callsFile.name)
            }

            progress.onStage(BackupStage.Messages)
            nSms = sms.countSince(null)
            nMms = mms.countSince(null)
            if (nSms + nMms > 0) {
                val smsFile = File(outputDir, "sms.xml")
                smsFile.bufferedWriter().use { w ->
                    val writer = SmsBackupXmlWriter(w, backupDateMs, backupSet)
                    writer.start(nSms + nMms)
                    sms.itemsSince(null).collect { writer.writeSms(it as SmsRecord) }
                    mms.itemsSince(null).collect { writer.writeMms(it as MmsRecord) }
                    writer.finish()
                }
                fileSink.put(smsFile, smsFile.name)
            }
        }

        // Photos, then videos — each kind gated independently, own document, own progress.
        if (content.pictures) {
            progress.onStage(BackupStage.Photos)
            nImg = images.countSince(null)
            if (nImg > 0) {
                val photosFile = File(outputDir, "photos.xml")
                photosFile.bufferedWriter().use { w ->
                    val writer = MediaIndexXmlWriter(w, backupDateMs, backupSet)
                    writer.start(nImg)
                    var done = 0
                    backupMediaSource(images, writer) { progress.onPhotoProgress(++done, nImg) }
                    writer.finish()
                }
                fileSink.put(photosFile, photosFile.name)
            }
        }

        if (content.videos) {
            progress.onStage(BackupStage.Videos)
            nVid = video.countSince(null)
            if (nVid > 0) {
                val videosFile = File(outputDir, "videos.xml")
                videosFile.bufferedWriter().use { w ->
                    val writer = MediaIndexXmlWriter(w, backupDateMs, backupSet)
                    writer.start(nVid)
                    var done = 0
                    backupMediaSource(video, writer) { progress.onVideoProgress(++done, nVid) }
                    writer.finish()
                }
                fileSink.put(videosFile, videosFile.name)
            }
        }

        progress.onStage(BackupStage.Complete)
        BackupSummary(nSms, nMms, nCall, nImg, nVid, outputDir.absolutePath)
    }

    /**
     * Streams every item of one media [source] to the sink and indexes it. The sink skips any
     * item already recorded in the upload manifest, so re-walking the whole library each run
     * never re-uploads bytes; a single failed item is logged and left out of this run's index
     * (see [sinkAndIndex]) so it never aborts the backup and is retried on the next full run.
     */
    private suspend fun backupMediaSource(
        source: MediaSource,
        writer: MediaIndexXmlWriter,
        onProgress: () -> Unit,
    ) {
        source.itemsSince(null).collect { item ->
            sinkAndIndex(item as MediaRecord, writer)
            onProgress()
        }
    }

    /**
     * Hands one media original's bytes to [mediaSink] (which reads the provider stream
     * READ-ONLY and, in production, streams it to Drive — or skips it if the manifest already
     * has it), then records it in the index. Returns true on success. A single failed item is
     * logged and skipped (returns false) so it never aborts the whole backup; being absent from
     * this run's index, it is retried on the next full run.
     */
    private fun sinkAndIndex(r: MediaRecord, writer: MediaIndexXmlWriter): Boolean {
        val relDir = (r.relativePath ?: "").trim('/')
        val name = r.displayName?.takeIf { it.isNotBlank() } ?: r.id.toString()
        val backupPath = "media/" + (if (relDir.isEmpty()) name else "$relDir/$name")
        return try {
            mediaSink.put(r, backupPath)
            writer.writeMedia(r, backupPath)
            true
        } catch (e: Exception) {
            Log.w(TAG, "media ${r.stableId} (gen ${r.generationModified}) skipped, will retry: ${e.message}")
            false
        }
    }

    private companion object {
        const val TAG = "DiffuseBackup"
    }
}
