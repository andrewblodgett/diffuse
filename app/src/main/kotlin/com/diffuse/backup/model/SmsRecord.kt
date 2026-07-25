package com.diffuse.backup.model


/**
 * One SMS message from `content://sms`, mapped to the fields the SMS Backup &
 * Restore `<sms>` element expects (see docs/backup-format.md).
 *
 * `sms.date` is already epoch **milliseconds** on-device (13 digits) — no conversion.
 */
data class SmsRecord(
    val id: Long,
    val address: String?,
    /** epoch ms; native units of the `date` column (no conversion needed). */
    val dateEpochMs: Long,
    /** epoch ms; `date_sent`, 0 when unknown. */
    val dateSentEpochMs: Long,
    /** 1=received, 2=sent, 3=draft, 4=outbox, 5=failed, 6=queued. */
    val type: Int,
    val body: String?,
    val subject: String?,
    /** 0=unread, 1=read. */
    val read: Int,
    /** -1=none, 0=complete, 32=pending, 64=failed. */
    val status: Int,
    val protocol: String?,
    val serviceCenter: String?,
    val locked: Int,
    /** subscription / SIM id, -1 when single-SIM or unknown. */
    val subId: Int,
) : BackupItem {
    override val sourceId: String get() = "sms"
    override val stableId: String get() = id.toString()
    override val updatedAtEpochMs: Long get() = dateEpochMs
}
