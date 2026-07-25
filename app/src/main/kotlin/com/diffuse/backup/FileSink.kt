package com.diffuse.backup

import java.io.File

/**
 * Where a completed archive document (e.g. `calls-<ts>.xml`) goes once [BackupRunner] finishes
 * writing it. Mirrors [MediaSink]'s role for media originals: it lets the runner upload each
 * document the moment it's ready — right after announcing that stage — instead of a single
 * batch sweep at the end that uploads everything in whatever order files happen to sort in.
 * That mismatch was a real bug: the progress UI said "backing up messages" while the batch
 * sweep was actually uploading calls/media/sms alphabetically, sms last.
 *
 * READ-ONLY: implementations only read [file] (already fully written to our own sandbox) and
 * upload its bytes; they never touch a content provider.
 */
fun interface FileSink {

    /** Upload [file], which the runner will refer to by archive-relative [backupPath]. */
    fun put(file: File, backupPath: String)

    companion object {
        /** A sink that does nothing — used when only the local file matters (tests). */
        val NONE = FileSink { _, _ -> }
    }
}
