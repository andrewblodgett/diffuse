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
import com.diffuse.backup.store.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
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
 * Runs a full or incremental extraction of every source into a local SMS Backup &
 * Restore-compatible archive under [outputDir], uploading each document via [fileSink]
 * (production: straight to Drive) the moment it's finished — always in the same order:
 * **call log, then messages, then photos, then videos**. That fixed order is what the
 * progress UI announces via [progress], so what it says and what's actually uploading always
 * agree (a real bug: an earlier version wrote all the XML docs first and uploaded them in a
 * separate alphabetical-filename sweep afterward, so the UI said "messages" while the network
 * was actually sending something else, and text data uploaded last regardless of the order
 * announced).
 *
 * Layout produced:
 * ```
 * outputDir/
 *   calls-<ts>.xml    # <calls>
 *   sms-<ts>.xml      # <smses> with <sms> + <mms> (attachments inline base64)
 *   photos-<ts>.xml   # <medias> index for images (references media/<relative_path>/<name>)
 *   videos-<ts>.xml   # <medias> index for video, same shape
 * ```
 *
 * Media *originals* are no longer copied under [outputDir] — each is handed to [mediaSink],
 * which in production streams it straight provider→Drive (see
 * [com.diffuse.drive.DriveMediaSink]); only the small index XML is written locally. This keeps
 * peak on-device disk to a copy buffer instead of a second copy of the whole media library.
 *
 * READ-ONLY: every source reads its provider; the only local writes are to files under
 * [outputDir] (our own sandbox), which is not a provider mutation.
 */
class BackupRunner(
    private val resolver: ContentResolver,
    private val outputDir: File,
    private val tokens: TokenStore,
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
    /**
     * @param incremental when true, each source resumes from its persisted token;
     *   when false, everything is re-extracted (a full backup).
     */
    suspend fun run(incremental: Boolean = true): BackupSummary = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(System.currentTimeMillis())
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
            val callSince = if (incremental) tokens.get(calls.id) else null
            val nextCallToken = calls.currentToken()
            nCall = calls.countSince(callSince)
            val callsFile = File(outputDir, "calls-$stamp.xml")
            callsFile.bufferedWriter().use { w ->
                val writer = CallLogXmlWriter(w, backupDateMs, backupSet)
                writer.start(nCall)
                calls.itemsSince(callSince).collect { writer.writeCall(it as CallRecord) }
                writer.finish()
            }
            tokens.put(calls.id, nextCallToken)
            fileSink.put(callsFile, callsFile.name)

            progress.onStage(BackupStage.Messages)
            val smsSince = if (incremental) tokens.get(sms.id) else null
            val mmsSince = if (incremental) tokens.get(mms.id) else null
            val nextSmsToken = sms.currentToken()
            val nextMmsToken = mms.currentToken()
            nSms = sms.countSince(smsSince)
            nMms = mms.countSince(mmsSince)
            val smsFile = File(outputDir, "sms-$stamp.xml")
            smsFile.bufferedWriter().use { w ->
                val writer = SmsBackupXmlWriter(w, backupDateMs, backupSet)
                writer.start(nSms + nMms)
                sms.itemsSince(smsSince).collect { writer.writeSms(it as SmsRecord) }
                mms.itemsSince(mmsSince).collect { writer.writeMms(it as MmsRecord) }
                writer.finish()
            }
            tokens.put(sms.id, nextSmsToken)
            tokens.put(mms.id, nextMmsToken)
            fileSink.put(smsFile, smsFile.name)
        }

        // Photos, then videos — each kind gated independently, own document, own progress.
        if (content.pictures) {
            progress.onStage(BackupStage.Photos)
            val imgSince = if (incremental) tokens.get(images.id) else null
            nImg = images.countSince(imgSince)
            val photosFile = File(outputDir, "photos-$stamp.xml")
            photosFile.bufferedWriter().use { w ->
                val writer = MediaIndexXmlWriter(w, backupDateMs, backupSet)
                writer.start(nImg)
                var done = 0
                backupMediaSource(images, imgSince, writer) { progress.onPhotoProgress(++done, nImg) }
                writer.finish()
            }
            fileSink.put(photosFile, photosFile.name)
        }

        if (content.videos) {
            progress.onStage(BackupStage.Videos)
            val vidSince = if (incremental) tokens.get(video.id) else null
            nVid = video.countSince(vidSince)
            val videosFile = File(outputDir, "videos-$stamp.xml")
            videosFile.bufferedWriter().use { w ->
                val writer = MediaIndexXmlWriter(w, backupDateMs, backupSet)
                writer.start(nVid)
                var done = 0
                backupMediaSource(video, vidSince, writer) { progress.onVideoProgress(++done, nVid) }
                writer.finish()
            }
            fileSink.put(videosFile, videosFile.name)
        }

        progress.onStage(BackupStage.Complete)
        BackupSummary(nSms, nMms, nCall, nImg, nVid, outputDir.absolutePath)
    }

    /**
     * Streams every new item of one media [source] to the sink, indexing each, then advances the
     * source's token **only through the contiguous run of successes**. Items arrive in
     * `generation_modified` ASC order, so on the first failure we stop advancing: everything from
     * that item onward is retried next run instead of being silently skipped forever (the token
     * used to jump to the current max regardless, which permanently lost any failed upload). The
     * upload manifest makes the retried successes idempotent, so this never re-uploads.
     */
    private suspend fun backupMediaSource(
        source: MediaSource,
        since: Long?,
        writer: MediaIndexXmlWriter,
        onProgress: () -> Unit,
    ) {
        val fullyCaughtUp = source.currentToken() // max generation right now
        var highWater: Long? = since
        var failed = false
        source.itemsSince(since).collect { item ->
            val r = item as MediaRecord
            if (sinkAndIndex(r, writer)) {
                if (!failed) highWater = r.generationModified
            } else {
                failed = true
            }
            onProgress()
        }
        val newToken = if (!failed) fullyCaughtUp else highWater
        if (newToken != null) tokens.put(source.id, newToken)
    }

    /**
     * Hands one media original's bytes to [mediaSink] (which reads the provider stream
     * READ-ONLY and, in production, streams it to Drive), then records it in the index.
     * Returns true on success. A single failed item is logged and skipped (returns false) so it
     * never aborts the whole backup and, via [backupMediaSource], gets retried next run instead
     * of being lost.
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
