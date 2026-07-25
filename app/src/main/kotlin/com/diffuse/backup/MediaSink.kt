package com.diffuse.backup

import com.diffuse.backup.model.MediaRecord

/**
 * Where a media original's bytes go during a backup. Keeps [BackupRunner] free of any Drive
 * dependency (a Phase-2 property that lets it stay pure and unit-testable): the runner writes
 * the media *index* XML and hands each record to a sink, which decides what to do with the
 * bytes — stream straight to Drive ([com.diffuse.drive.DriveMediaSink], the shipping path) or
 * a no-op/local sink in tests.
 *
 * READ-ONLY: a sink only ever *reads* the provider stream for [record] (via its `contentUri`
 * in "r" mode) and writes to our own destinations; it never mutates a content provider.
 */
fun interface MediaSink {

    /**
     * Handle the bytes for [record], which the runner will index at archive-relative
     * [backupPath] (e.g. `media/DCIM/Camera/IMG_0001.jpg`). Implementations may skip work
     * already done (e.g. an upload manifest) and should not throw for a single unreadable
     * item — the runner treats media best-effort.
     */
    fun put(record: MediaRecord, backupPath: String)

    companion object {
        /** A sink that discards the bytes — used when only the index matters (tests). */
        val NONE = MediaSink { _, _ -> }
    }
}
