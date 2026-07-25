package com.diffuse.backup

/**
 * A single unit to be backed up. Deliberately minimal for Phase 1 — the concrete
 * shape (message rows with parts, media files with byte streams) is fleshed out by
 * Phase 2's extractors. [stableId] + [sourceId] dedupe across incremental runs.
 */
data class BackupItem(
    val sourceId: String,
    val stableId: String,
    val updatedAtEpochMs: Long,
)
