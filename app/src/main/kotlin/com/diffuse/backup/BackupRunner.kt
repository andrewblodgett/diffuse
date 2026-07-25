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
 * Restore-compatible archive under [outputDir]. This is the end of Phase 2: it produces
 * the on-disk standard format and touches **nothing** in the cloud — Phase 3 uploads
 * [outputDir] to Drive.
 *
 * Layout produced:
 * ```
 * outputDir/
 *   sms-<ts>.xml     # <smses> with <sms> + <mms> (attachments inline base64)
 *   calls-<ts>.xml   # <calls>
 *   media-<ts>.xml   # <medias> index (references the media/<relative_path>/<name> paths)
 * ```
 *
 * Media *originals* are no longer copied under [outputDir] — each is handed to [mediaSink],
 * which in production streams it straight provider→Drive (see
 * [com.diffuse.drive.DriveMediaSink]); only the small index XML is written locally. This keeps
 * peak on-device disk to a copy buffer instead of a second copy of the whole media library.
 *
 * READ-ONLY: every source reads its provider; the only writes are to files under
 * [outputDir] (our own sandbox), which is not a provider mutation.
 */
class BackupRunner(
    private val resolver: ContentResolver,
    private val outputDir: File,
    private val tokens: TokenStore,
    private val mediaSink: MediaSink = MediaSink.NONE,
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

        // --- Messages + calls: gated together by the "text & call history" choice ----
        if (content.messages) {
            // SMS + MMS share one <smses> document.
            progress.onStage(BackupStage.Messages)
            val smsSince = if (incremental) tokens.get(sms.id) else null
            val mmsSince = if (incremental) tokens.get(mms.id) else null
            val nextSmsToken = sms.currentToken()
            val nextMmsToken = mms.currentToken()
            nSms = sms.countSince(smsSince)
            nMms = mms.countSince(mmsSince)
            File(outputDir, "sms-$stamp.xml").bufferedWriter().use { w ->
                val writer = SmsBackupXmlWriter(w, backupDateMs, backupSet)
                writer.start(nSms + nMms)
                sms.itemsSince(smsSince).collect { writer.writeSms(it as SmsRecord) }
                mms.itemsSince(mmsSince).collect { writer.writeMms(it as MmsRecord) }
                writer.finish()
            }
            tokens.put(sms.id, nextSmsToken)
            tokens.put(mms.id, nextMmsToken)

            // Call log.
            progress.onStage(BackupStage.Calls)
            val callSince = if (incremental) tokens.get(calls.id) else null
            val nextCallToken = calls.currentToken()
            nCall = calls.countSince(callSince)
            File(outputDir, "calls-$stamp.xml").bufferedWriter().use { w ->
                val writer = CallLogXmlWriter(w, backupDateMs, backupSet)
                writer.start(nCall)
                calls.itemsSince(callSince).collect { writer.writeCall(it as CallRecord) }
                writer.finish()
            }
            tokens.put(calls.id, nextCallToken)
        }

        // --- Media: stream originals to the sink + write an index -------------------
        // Each media kind is gated independently; the index is written only when at least one is on.
        if (content.pictures || content.videos) {
            File(outputDir, "media-$stamp.xml").bufferedWriter().use { w ->
                val imgSince = if (incremental) tokens.get(images.id) else null
                val vidSince = if (incremental) tokens.get(video.id) else null
                nImg = if (content.pictures) images.countSince(imgSince) else 0
                nVid = if (content.videos) video.countSince(vidSince) else 0
                progress.onStage(BackupStage.Media)
                val mediaTotal = nImg + nVid
                var mediaDone = 0
                val writer = MediaIndexXmlWriter(w, backupDateMs, backupSet)
                writer.start(mediaTotal)
                val onOne = { progress.onMediaProgress(++mediaDone, mediaTotal) }
                if (content.pictures) backupMediaSource(images, imgSince, writer, onOne)
                if (content.videos) backupMediaSource(video, vidSince, writer, onOne)
                writer.finish()
            }
        }

        progress.onStage(BackupStage.UploadingIndex)
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
