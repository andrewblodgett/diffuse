package com.diffuse.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Replace [file]'s contents with whatever [write] produces, all-or-nothing: the bytes go to a
 * temporary sibling, are flushed to disk, and only then renamed over [file]. If the process
 * dies part-way (WorkManager stopping a job, the battery dying), [file] still holds its
 * previous complete contents instead of a truncated half-write.
 *
 * Writing our own app files is not a content-provider mutation.
 */
fun writeAtomically(file: File, write: (OutputStream) -> Unit) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    FileOutputStream(tmp).use { out ->
        write(out)
        out.flush()
        out.fd.sync()
    }
    if (!tmp.renameTo(file)) {
        tmp.delete()
        throw IOException("could not replace ${file.name}")
    }
}
