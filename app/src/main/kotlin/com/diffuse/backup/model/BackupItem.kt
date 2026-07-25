package com.diffuse.backup.model

/**
 * A single unit of on-device data to be backed up.
 *
 * Phase 1 left this deliberately minimal; Phase 2 fleshes it out into a sealed
 * hierarchy of concrete records — one per content provider — while keeping the
 * common metadata ([sourceId], [stableId], [updatedAtEpochMs]) that the
 * [com.diffuse.backup.BackupSource] contract and incremental de-duplication rely on.
 * Because it stays a [BackupItem], the Phase 1 signature
 * `BackupSource.itemsSince(): Flow<BackupItem>` is unchanged.
 *
 * Sealed, so the set of backup item types is closed and lives with the records in
 * this package (Kotlin requires sealed implementations to share the package).
 *
 * READ-ONLY: these are plain immutable snapshots of provider rows. They carry no
 * write surface — an extractor reads a cursor (and, for binary MMS parts and media,
 * an input stream) and materialises one of these; nothing here can mutate the device.
 */
sealed interface BackupItem {
    /** Source identifier, e.g. "sms", "mms", "call_log", "images", "video". */
    val sourceId: String

    /** Provider-stable identity used to dedupe across incremental runs. */
    val stableId: String

    /**
     * When the item was last added or modified, in **epoch milliseconds**, already
     * normalised from whatever unit the source provider uses (see the per-provider
     * epoch-unit gotchas in docs/phase0-findings.md). Used only for reporting/ordering,
     * never as the incremental change-token — tokens stay in each provider's native
     * column units (see the concrete `BackupSource` implementations).
     */
    val updatedAtEpochMs: Long
}
