package com.diffuse.backup.store

import java.io.File
import java.util.Properties

/**
 * Persists each source's last-seen change token (see [com.diffuse.backup.BackupSource]),
 * so the next run extracts only newer data. Tokens are opaque Longs in each provider's
 * native units.
 */
interface TokenStore {
    fun get(sourceId: String): Long?
    fun put(sourceId: String, token: Long)

    /** Forget [sourceId]'s token, so the next run re-derives it from scratch (as if new). */
    fun clear(sourceId: String)
}

/**
 * [TokenStore] backed by a `java.util.Properties` file (keys `token.<sourceId>`).
 * Pure JVM — no Android or extra dependency — so it unit-tests directly. Writing to
 * our own app file is not a provider mutation, so it doesn't touch the read-only
 * invariant (which is strictly about on-device SMS/MMS/call/media providers).
 */
class FilePropertiesTokenStore(private val file: File) : TokenStore {

    private val props = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    override fun get(sourceId: String): Long? =
        props.getProperty(key(sourceId))?.toLongOrNull()

    override fun put(sourceId: String, token: Long) {
        props.setProperty(key(sourceId), token.toString())
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Diffuse incremental backup tokens") }
    }

    override fun clear(sourceId: String) {
        if (props.remove(key(sourceId)) == null) return
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Diffuse incremental backup tokens") }
    }

    private fun key(sourceId: String) = "token.$sourceId"
}
