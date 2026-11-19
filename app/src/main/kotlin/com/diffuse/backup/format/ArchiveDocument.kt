package com.diffuse.backup.format

import java.io.File
import java.io.OutputStreamWriter
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * A finished archive document on local disk, ready to upload.
 *
 * @property name the file name on Drive, e.g. `sms-2026-11.xml`.
 * @property recordCount how many records were actually written (what the header claims).
 * @property fingerprint SHA-256 (hex) of the record section only. The header carries the
 *   run's `backup_date` and a random `backup_set`, so hashing the whole file would differ on
 *   every run; hashing just the records lets the uploader skip a document whose content
 *   hasn't changed since the last upload.
 */
data class ArchiveDocument(
    val file: File,
    val name: String,
    val recordCount: Int,
    val fingerprint: String,
)

/**
 * Builds [ArchiveDocument]s in [dir] in two steps: records are streamed into a temporary
 * body file while being counted, then the final file is assembled as header (with the exact
 * count) + body + footer, hashing the body on the way through.
 *
 * Counting while writing matters because the provider can change between a `count()` query
 * and the read that follows (a message arrives mid-backup), and a restore tool that trusts
 * the header's `count` should never be told a wrong number.
 */
class ArchiveDocumentBuilder(private val dir: File) {

    /**
     * Writes document [name]. [writeRecords] writes every record to the given [Appendable]
     * and returns how many it wrote. Returns null, leaving no file behind, when it wrote
     * none: an empty export is never uploaded, so it can't replace a useful one.
     */
    suspend fun build(
        name: String,
        header: (out: Appendable, count: Int) -> Unit,
        footer: (out: Appendable) -> Unit,
        writeRecords: suspend (out: Appendable) -> Int,
    ): ArchiveDocument? {
        dir.mkdirs()
        val body = File(dir, "$name.body")
        val target = File(dir, name)
        try {
            val count = body.bufferedWriter(Charsets.UTF_8).use { writeRecords(it) }
            if (count == 0) return null

            val digest = MessageDigest.getInstance("SHA-256")
            target.outputStream().buffered().use { os ->
                val text = OutputStreamWriter(os, Charsets.UTF_8)
                header(text, count)
                text.flush() // switch from the writer to raw bytes for the body
                DigestInputStream(body.inputStream(), digest).use { it.copyTo(os) }
                footer(text)
                text.flush()
            }
            return ArchiveDocument(target, name, count, digest.digest().toHex())
        } finally {
            body.delete()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
