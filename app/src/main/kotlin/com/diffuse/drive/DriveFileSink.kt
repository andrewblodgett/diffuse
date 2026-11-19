package com.diffuse.drive

import com.diffuse.backup.DocumentUpload
import com.diffuse.backup.FileSink
import com.diffuse.backup.format.ArchiveDocument

/**
 * Uploads each finished archive document (`calls-2026-11.xml`, `sms-2026-11.xml`, ...) to the
 * root of the Diffuse folder the moment [com.diffuse.backup.BackupRunner] hands it over.
 *
 * A document's Drive identity is this phone plus its name (two phones backing up to one Drive
 * keep separate files), and its version is the fingerprint of its records:
 *  - not on Drive yet → created;
 *  - on Drive with the same fingerprint → nothing to do (no 100 MB re-upload of an unchanged
 *    message archive every night);
 *  - on Drive with different content → overwritten in place, keeping the same file id.
 *
 * Because names carry the month, "overwritten in place" only ever touches the current month's
 * snapshot; earlier months are never modified again.
 *
 * READ-ONLY: reads the already-written local file and uploads it; touches no content provider.
 */
class DriveFileSink(
    private val drive: DriveApi,
    private val folders: DriveFolderTree,
    private val inventory: DriveInventory,
    /** This phone's id (see [com.diffuse.BackupEngine]); documents belong to one phone. */
    private val deviceId: String,
) : FileSink {

    override fun put(document: ArchiveDocument): DocumentUpload {
        val key = DriveInventory.documentKey(deviceId, document.name)
        val tags = DriveInventory.tags(key, document.fingerprint)
        val existing = inventory[key]
        if (existing != null && existing.version == document.fingerprint) return DocumentUpload.Unchanged

        if (existing != null) {
            try {
                drive.updateContent(existing.fileId, XML_MIME, tags, UploadContent.of(document.file))
                inventory.recordUpload(key, existing.copy(version = document.fingerprint))
                return DocumentUpload.Updated
            } catch (e: DriveHttpException) {
                if (e.code != 404) throw e
                // Deleted from Drive since the listing was taken: fall through and re-create it.
            }
        }
        val name = driveName(document.name)
        val id = drive.create(name, folders.folderId(""), XML_MIME, tags, UploadContent.of(document.file))
        inventory.recordUpload(key, DriveInventory.Entry(id, name, document.fingerprint))
        return DocumentUpload.Created
    }

    /**
     * The document's own name, unless another phone already uploaded one by that name, in
     * which case this phone's copy gets its id: `sms-2026-11-1a2b3c4d.xml`.
     */
    private fun driveName(name: String): String {
        if (!inventory.isNameTaken(name)) return name
        return "${name.substringBeforeLast('.')}-$deviceId.${name.substringAfterLast('.')}"
    }

    private companion object {
        const val XML_MIME = "application/xml"
    }
}
