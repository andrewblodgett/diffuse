package com.diffuse.drive

import com.diffuse.drive.store.UploadManifest

/**
 * Keeps the local upload manifest honest against what's actually on Drive. The manifest is a
 * cache built for speed — "this path is already uploaded, skip its bytes" — and it is never
 * re-checked against reality on its own. If the user empties (or partly empties) the "Diffuse"
 * folder on Drive, the manifest still says everything is uploaded, so a subsequent backup would
 * silently treat deleted media as already backed up and skip it forever.
 *
 * [reconcile] closes that gap: it lists every file id Drive actually still has (cheap under
 * `drive.file` scope — see [DriveApi.listAllFileIds]) and prunes any manifest entry that no
 * longer matches. Because every run re-extracts and re-walks the whole library, pruning is all
 * that's needed — a pruned media path is missing from the manifest, so the next run re-uploads
 * its bytes; a pruned document path makes [DriveFileSink] re-create the doc instead of trying to
 * overwrite a file that's gone. (Documents are rewritten every run regardless, so a deleted doc
 * that was *not* pruned would simply be overwritten back into existence.)
 *
 * Call once at the start of every backup run, before extraction begins.
 */
class BackupReconciler(
    private val drive: DriveApi,
    private val manifest: UploadManifest,
) {
    fun reconcile() {
        manifest.retain(drive.listAllFileIds())
    }
}
