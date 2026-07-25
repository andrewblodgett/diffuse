package com.diffuse.backup

/**
 * Coarse stages of a backup run, in the exact order they're both announced AND actually
 * uploaded in — call log, then messages, then photos, then videos. Keeping one fixed order for
 * both matters: the progress UI reporting a stage the runner isn't really uploading yet is
 * confusing and was a real bug.
 */
enum class BackupStage { Calls, Messages, Photos, Videos, Complete }

/**
 * A backup run reports its progress through this listener so the UI (and the scheduled
 * worker's foreground notification) can show what's happening during a long run — media
 * upload can take many minutes. Kept a plain callback with no-op defaults so [BackupRunner]
 * stays decoupled from any UI/Android type; the default [NONE] means "report nothing".
 */
interface BackupProgress {
    /** Entered a new [stage]. */
    fun onStage(stage: BackupStage) {}
    /** Streamed [done] of [total] photos so far this run. */
    fun onPhotoProgress(done: Int, total: Int) {}
    /** Streamed [done] of [total] videos so far this run. */
    fun onVideoProgress(done: Int, total: Int) {}

    companion object {
        val NONE: BackupProgress = object : BackupProgress {}
    }
}
