package com.diffuse.backup.model


/**
 * One row from `content://call_log/calls`, mapped to the SMS Backup & Restore
 * `<call>` element. `call_log.date` is already epoch **milliseconds**.
 */
data class CallRecord(
    val id: Long,
    val number: String?,
    /** duration in **seconds** (the provider's native unit). */
    val durationSec: Long,
    /** epoch ms; native units of the `date` column. */
    val dateEpochMs: Long,
    /** 1=incoming, 2=outgoing, 3=missed, 4=voicemail, 5=rejected, 6=blocked. */
    val type: Int,
    /** number presentation: 1=allowed, 2=restricted, 3=unknown, 4=payphone. */
    val presentation: Int,
    val subscriptionId: String?,
) : BackupItem {
    override val sourceId: String get() = "call_log"
    override val stableId: String get() = id.toString()
    override val updatedAtEpochMs: Long get() = dateEpochMs
}
