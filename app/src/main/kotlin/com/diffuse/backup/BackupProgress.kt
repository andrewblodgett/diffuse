package com.diffuse.backup

/**
 * Coarse stages of a backup run, in the exact order they're both announced AND actually
 * uploaded in: call log, then messages, then photos, then videos.
 */
enum class BackupStage { Calls, Messages, Photos, Videos }

/**
 * A backup run reports its progress through this listener so the UI and the worker's
 * foreground notification can show what's happening during a long run. A plain callback
 * with no-op defaults keeps [BackupRunner] free of Android types.
 */
interface BackupProgress {
    /** Entered a new [stage]. */
    fun onStage(stage: BackupStage) {}
    /** Processed [done] of roughly [total] photos so far this run. */
    fun onPhotoProgress(done: Int, total: Int) {}
    /** Processed [done] of roughly [total] videos so far this run. */
    fun onVideoProgress(done: Int, total: Int) {}

    companion object {
        val NONE: BackupProgress = object : BackupProgress {}
    }
}
