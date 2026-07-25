package com.diffuse.backup.store

import java.io.File
import java.util.Properties

/** The outcome of the most recent backup run, shown on the home screen across launches. */
data class LastRun(
    val timestampMs: Long,
    val success: Boolean,
    /** Human-readable one-line summary (counts on success, error on failure). */
    val summary: String,
)

/**
 * Persists the [LastRun] to a `java.util.Properties` file so the home screen can show
 * "last backed up …" after the process is gone. Pure JVM (no Android), mirroring
 * [FilePropertiesTokenStore], so it unit-tests directly. Writing our own app file is not a
 * provider mutation and doesn't touch the read-only invariant.
 */
class LastRunStore(private val file: File) {

    fun get(): LastRun? {
        if (!file.exists()) return null
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val ts = props.getProperty(KEY_TS)?.toLongOrNull() ?: return null
        return LastRun(
            timestampMs = ts,
            success = props.getProperty(KEY_OK)?.toBoolean() ?: false,
            summary = props.getProperty(KEY_SUMMARY).orEmpty(),
        )
    }

    fun put(run: LastRun) {
        val props = Properties().apply {
            setProperty(KEY_TS, run.timestampMs.toString())
            setProperty(KEY_OK, run.success.toString())
            setProperty(KEY_SUMMARY, run.summary)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Diffuse last backup run") }
    }

    private companion object {
        const val KEY_TS = "last.timestamp"
        const val KEY_OK = "last.success"
        const val KEY_SUMMARY = "last.summary"
    }
}
