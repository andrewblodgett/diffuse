package com.diffuse.backup

import android.content.ContentResolver
import android.net.Uri
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
 *   media-<ts>.xml   # <medias> index
 *   media/<relative_path>/<name>   # photo/video originals, copied byte-for-byte
 * ```
 *
 * READ-ONLY: every source reads its provider; the only writes are to files under
 * [outputDir] (our own sandbox), which is not a provider mutation.
 */
class BackupRunner(
    private val resolver: ContentResolver,
    private val outputDir: File,
    private val tokens: TokenStore,
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

        val nSms: Int
        val nMms: Int
        val nCall: Int
        val nImg: Int
        val nVid: Int

        // --- Messages: SMS + MMS share one <smses> document ---------------------
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

        // --- Call log -----------------------------------------------------------
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

        // --- Media: copy originals + write an index -----------------------------
        val mediaDir = File(outputDir, "media").apply { mkdirs() }
        File(outputDir, "media-$stamp.xml").bufferedWriter().use { w ->
            val imgSince = if (incremental) tokens.get(images.id) else null
            val vidSince = if (incremental) tokens.get(video.id) else null
            val nextImgToken = images.currentToken()
            val nextVidToken = video.currentToken()
            nImg = images.countSince(imgSince)
            nVid = video.countSince(vidSince)
            val writer = MediaIndexXmlWriter(w, backupDateMs, backupSet)
            writer.start(nImg + nVid)
            images.itemsSince(imgSince).collect { copyAndIndex(it as MediaRecord, mediaDir, writer) }
            video.itemsSince(vidSince).collect { copyAndIndex(it as MediaRecord, mediaDir, writer) }
            writer.finish()
            tokens.put(images.id, nextImgToken)
            tokens.put(video.id, nextVidToken)
        }

        BackupSummary(nSms, nMms, nCall, nImg, nVid, outputDir.absolutePath)
    }

    /** Copies one media file's bytes into [mediaDir] (READ-ONLY read of the provider). */
    private fun copyAndIndex(r: MediaRecord, mediaDir: File, writer: MediaIndexXmlWriter) {
        val relDir = (r.relativePath ?: "").trim('/')
        val name = r.displayName?.takeIf { it.isNotBlank() } ?: r.id.toString()
        val dest = File(File(mediaDir, relDir), name)
        val backupPath = "media/" + (if (relDir.isEmpty()) name else "$relDir/$name")
        try {
            dest.parentFile?.mkdirs()
            resolver.openInputStream(Uri.parse(r.contentUri))?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            writer.writeMedia(r, backupPath)
        } catch (e: Exception) {
            // A single unreadable item shouldn't abort the whole backup; skip and continue.
        }
    }
}
