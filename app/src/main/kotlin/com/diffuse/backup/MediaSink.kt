package com.diffuse.backup

import com.diffuse.backup.model.MediaRecord

/**
 * Where a media original's bytes go during a backup. Keeps [BackupRunner] free of any Drive
 * dependency, so it stays pure and unit-testable: the runner writes the media *index* and
 * hands each record to a sink, which decides what to do with the bytes (stream them to
 * Drive in production via [com.diffuse.drive.DriveMediaSink], or nothing in tests).
 *
 * READ-ONLY: a sink only ever *reads* the provider stream for a record (via its
 * `contentUri` in "r" mode) and writes to our own destinations.
 */
fun interface MediaSink {

    /**
     * Make sure [record]'s bytes are backed up, uploading them only if the destination
     * doesn't already hold this exact version. Throws if the item couldn't be backed up;
     * the runner counts that as a failure and the next run tries again.
     */
    fun put(record: MediaRecord): MediaResult

    companion object {
        /** A sink that discards the bytes (tests that only care about the index). */
        val NONE = MediaSink { record ->
            MediaResult.Stored(record.archiveFolder + "/" + record.archiveFileName, remoteId = null, uploadedNow = false)
        }
    }
}

/** What happened to one media item. */
sealed interface MediaResult {
    /**
     * The bytes are backed up at archive-relative [backupPath], as remote file [remoteId].
     * [uploadedNow] is false when that exact version was already there.
     */
    data class Stored(val backupPath: String, val remoteId: String?, val uploadedNow: Boolean) : MediaResult

    /** The file is empty (0 bytes), so there is nothing to back up. Not an error. */
    data object Empty : MediaResult
}

/** Archive folder for a media item: `media/<relative_path>` (e.g. `media/DCIM/Camera`). */
val MediaRecord.archiveFolder: String
    get() {
        // Drop empty segments so "DCIM//Camera/" can't create a folder with no name.
        val relDir = relativePath.orEmpty().split('/').filter { it.isNotBlank() }.joinToString("/")
        return if (relDir.isEmpty()) "media" else "media/$relDir"
    }

/** The item's own file name, falling back to its MediaStore id when it has none. */
val MediaRecord.archiveFileName: String
    get() = displayName?.takeIf { it.isNotBlank() } ?: id.toString()
