package com.diffuse.backup

/** Coarse stages of a backup run, in order, for progress reporting. */
enum class BackupStage { Messages, Calls, Media, UploadingIndex, Complete }

/**
 * A backup run reports its progress through this listener so the UI (and the scheduled
 * worker's foreground notification) can show what's happening during a long run — media
 * upload can take many minutes. Kept a plain callback with no-op defaults so [BackupRunner]
 * stays decoupled from any UI/Android type; the default [NONE] means "report nothing".
 */
interface BackupProgress {
    /** Entered a new [stage]. */
    fun onStage(stage: BackupStage) {}
    /** Streamed [done] of [total] media originals so far this run. */
    fun onMediaProgress(done: Int, total: Int) {}

    companion object {
        val NONE: BackupProgress = object : BackupProgress {}
    }
}
