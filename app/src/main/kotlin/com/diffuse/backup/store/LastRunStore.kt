package com.diffuse.backup.store

import com.diffuse.util.writeAtomically
import java.io.File
import java.util.Properties

/** The outcome of the most recent backup run, shown on the home screen across launches. */
data class LastRun(
    val timestampMs: Long,
    /** False when the run as a whole failed (nothing or only part of it was backed up). */
    val success: Boolean,
    /** Human-readable one-line summary (counts on success, the error on failure). */
    val summary: String,
    /** Items that couldn't be backed up even though the run finished; retried next run. */
    val failedItems: Int = 0,
) {
    /** Finished, and every item made it. */
    val isClean: Boolean get() = success && failedItems == 0
}

/**
 * Persists the [LastRun] to a `java.util.Properties` file so the home screen can show
 * "last backed up …" after the process is gone. Writes are atomic and an unreadable file
 * reads as "no record", so a crash mid-write can never break the home screen. Pure JVM
 * (no Android), so it unit-tests directly.
 */
class LastRunStore(private val file: File) {

    fun get(): LastRun? {
        if (!file.exists()) return null
        val props = runCatching { Properties().apply { file.inputStream().use { load(it) } } }
            .getOrElse { return null }
        val ts = props.getProperty(KEY_TS)?.toLongOrNull() ?: return null
        return LastRun(
            timestampMs = ts,
            success = props.getProperty(KEY_OK)?.toBoolean() ?: false,
            summary = props.getProperty(KEY_SUMMARY).orEmpty(),
            failedItems = props.getProperty(KEY_FAILED)?.toIntOrNull() ?: 0,
        )
    }

    fun put(run: LastRun) {
        val props = Properties().apply {
            setProperty(KEY_TS, run.timestampMs.toString())
            setProperty(KEY_OK, run.success.toString())
            setProperty(KEY_SUMMARY, run.summary)
            setProperty(KEY_FAILED, run.failedItems.toString())
        }
        writeAtomically(file) { props.store(it, "Diffuse last backup run") }
    }

    private companion object {
        const val KEY_TS = "last.timestamp"
        const val KEY_OK = "last.success"
        const val KEY_SUMMARY = "last.summary"
        const val KEY_FAILED = "last.failed_items"
    }
}
