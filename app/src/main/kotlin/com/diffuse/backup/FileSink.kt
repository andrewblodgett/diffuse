package com.diffuse.backup

import com.diffuse.backup.format.ArchiveDocument

/**
 * Where a finished archive document goes. [BackupRunner] hands each one over the moment it's
 * written, so documents upload in the same order the progress UI announces them.
 *
 * READ-ONLY: implementations only read the already-written local file and upload its bytes;
 * they never touch a content provider.
 */
fun interface FileSink {

    /** Upload [document] (or recognise that the destination already has this content). */
    fun put(document: ArchiveDocument): DocumentUpload

    companion object {
        /** A sink that keeps nothing (tests that only care about the local file). */
        val NONE = FileSink { DocumentUpload.Unchanged }
    }
}

/** What a [FileSink] did with one document. */
enum class DocumentUpload { Created, Updated, Unchanged }
