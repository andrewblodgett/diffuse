package com.diffuse

import com.diffuse.backup.BackupStage

/**
 * A progress line: a stage [label], plus "done of total" with a bar while photos or videos
 * are streaming ([total] 0 = no count, no bar).
 */
data class ProgressLine(val label: String, val done: Int = 0, val total: Int = 0) {
    val text: String get() = if (total > 0) "$label: $done of $total" else label

    /** Whole percent complete, or null when there's no count. */
    val percent: Int? get() = if (total > 0) done * 100 / total else null
}

/**
 * What to show for this progress. Shared by the home screen and the notification so they
 * always say the same thing.
 */
fun RunProgress.describe(): ProgressLine = when (stage) {
    null -> ProgressLine("Starting…")
    BackupStage.Calls -> ProgressLine("Backing up call log…")
    BackupStage.Messages -> ProgressLine("Backing up messages…")
    // The library can grow mid-run, so never show more done than the total.
    BackupStage.Photos -> ProgressLine("Backing up photos", photoDone.coerceAtMost(photoTotal), photoTotal)
    BackupStage.Videos -> ProgressLine("Backing up videos", videoDone.coerceAtMost(videoTotal), videoTotal)
}
