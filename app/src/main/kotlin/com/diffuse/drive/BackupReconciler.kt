package com.diffuse.drive

import com.diffuse.backup.store.TokenStore
import com.diffuse.drive.store.UploadManifest

/**
 * Keeps the local upload manifest and incremental tokens honest against what's actually on
 * Drive. Both are caches built for speed — "this path is already uploaded", "this source is
 * caught up to token X" — and neither one is ever re-checked against reality on its own. If the
 * user empties (or partly empties) the "Diffuse" folder on Drive, the manifest still says
 * everything is uploaded and the tokens still say every source is caught up, so a subsequent
 * backup silently treats deleted files as already backed up and skips them forever.
 *
 * [reconcile] closes that gap: it lists every file id Drive actually still has (cheap under
 * `drive.file` scope — see [DriveApi.listAllFileIds]) and prunes any manifest entry that no
 * longer matches. If anything was pruned, we can no longer trust *any* source's token — we don't
 * know whether only media was deleted or messages/calls too — so every token is cleared and the
 * next run re-derives everything from scratch. The (now-pruned) manifest still keeps that re-run
 * cheap: files still present on Drive are recognized and skipped, only the missing ones re-upload.
 *
 * Call once at the start of every backup run, before extraction begins.
 */
class BackupReconciler(
    private val drive: DriveApi,
    private val manifest: UploadManifest,
    private val tokens: TokenStore,
    private val sourceIds: List<String>,
) {
    fun reconcile() {
        val liveFileIds = drive.listAllFileIds()
        val pruned = manifest.retain(liveFileIds)
        if (pruned) sourceIds.forEach { tokens.clear(it) }
    }
}
