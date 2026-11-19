package com.diffuse.drive.store

import com.diffuse.util.writeAtomically
import java.io.File
import java.io.FileOutputStream

/**
 * A short-lived local journal of what was uploaded in the last [windowMs] (default a day).
 *
 * Drive itself is the record of what's backed up (see [com.diffuse.drive.DriveInventory]), but
 * a Drive listing can lag a few moments behind a brand-new upload. Without this journal, a run
 * that starts right after another could miss those newest files in its listing and upload them
 * a second time. Entries older than the window are dropped, because by then the listing is
 * authoritative.
 *
 * Append-only, one line per upload, so recording an upload costs O(1) no matter how many
 * files exist; a line cut short by a crash is simply ignored when reading.
 */
class RecentUploads(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = 24 * 60 * 60 * 1000L,
) {
    data class Entry(
        val key: String,
        val fileId: String,
        val name: String,
        val version: String,
        val origin: String = "",
        val folder: String = "",
    )

    /** Entries still inside the window; also rewrites the journal without the expired ones. */
    fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        val cutoff = now() - windowMs
        val kept = file.readLines().mapNotNull { line -> parse(line)?.takeIf { it.first >= cutoff } }
        writeAtomically(file) { out -> kept.forEach { (time, e) -> out.write(format(time, e).toByteArray()) } }
        return kept.map { it.second }
    }

    fun record(entry: Entry) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { it.write(format(now(), entry).toByteArray()) }
    }

    fun clear() {
        file.delete()
    }

    private fun format(time: Long, e: Entry) =
        listOf(time.toString(), e.key, e.fileId, e.version, e.origin, e.folder, e.name)
            .joinToString(SEP) { clean(it) } + "\n"

    private fun parse(line: String): Pair<Long, Entry>? {
        val f = line.split(SEP)
        if (f.size != 7) return null
        val time = f[0].toLongOrNull() ?: return null
        return time to Entry(key = f[1], fileId = f[2], version = f[3], origin = f[4], folder = f[5], name = f[6])
    }

    /** Field values never contain the separator or a newline (names and folders are free text). */
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private companion object {
        const val SEP = "\t"
    }
}
