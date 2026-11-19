package com.diffuse.drive

import com.diffuse.backup.MediaResult
import com.diffuse.backup.MediaSink
import com.diffuse.backup.archiveFileName
import com.diffuse.backup.archiveFolder
import com.diffuse.backup.model.MediaRecord
import java.io.InputStream
import java.time.LocalDate

/**
 * A [MediaSink] that streams each photo/video **straight from the content provider to Drive**,
 * with no local copy; peak memory is one copy buffer.
 *
 * An item is recognised by its MediaStore identity (see [DriveInventory]), not its file name,
 * and its version is its real byte length:
 *  - same item, same length → already backed up, nothing is read or sent;
 *  - same item, new length (the photo was edited, or a previous upload caught it while it was
 *    still being written) → the new version is uploaded **next to** the old one as
 *    `name (edited <date>).ext`, so a backup never loses the original;
 *  - two different items with the same name → the second gets its MediaStore id appended,
 *    so both survive and neither hides the other.
 *
 * The length always comes from [lengthOf] (the file descriptor), never MediaStore's `_size`:
 * on the Light Phone III fresh captures report `_size=0`, and a stale non-zero `_size` would
 * declare a wrong Content-Length and make that item fail on every run.
 *
 * The Android coupling is two seams: [openStream] turns a record's `contentUri` into a
 * read-only [InputStream] and [lengthOf] returns its real byte length (or a negative number if
 * unknown). Both keep this class pure and unit-testable.
 *
 * READ-ONLY: [openStream]/[lengthOf] open the provider in "r" mode; nothing here writes back.
 */
class DriveMediaSink(
    private val openStream: (contentUri: String) -> InputStream,
    private val lengthOf: (contentUri: String) -> Long,
    private val drive: DriveApi,
    private val folders: DriveFolderTree,
    private val inventory: DriveInventory,
    private val today: () -> LocalDate = LocalDate::now,
) : MediaSink {

    override fun put(record: MediaRecord): MediaResult {
        val length = lengthOf(record.contentUri)
        if (length == 0L) return MediaResult.Empty
        require(length > 0) { "can't determine the size of ${record.contentUri}" }

        val key = DriveInventory.mediaKey(record.stableId)
        val version = length.toString()
        val folder = record.archiveFolder
        val existing = inventory[key]

        if (existing != null && existing.version == version) {
            return MediaResult.Stored("$folder/${existing.name}", existing.fileId, uploadedNow = false)
        }
        if (existing == null) adoptLegacyUpload(key, version, record)?.let { return it }

        val name = chooseName(record, isNewVersion = existing != null)
        val mime = record.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val id = drive.create(
            name = name,
            parentId = folders.folderId(folder),
            mimeType = mime,
            appProperties = DriveInventory.tags(key, version),
            content = UploadContent(length) { openStream(record.contentUri) },
        )
        inventory.recordUpload(key, DriveInventory.Entry(id, name, version))
        return MediaResult.Stored("$folder/$name", id, uploadedNow = true)
    }

    /**
     * If Diffuse 0.1.x already uploaded this item (an untagged file at its natural path), tag
     * that file instead of uploading the bytes again. Those versions keyed purely by path and
     * never re-uploaded a changed file, so the existing bytes are taken to be current.
     */
    private fun adoptLegacyUpload(key: String, version: String, record: MediaRecord): MediaResult? {
        val path = "${record.archiveFolder}/${record.archiveFileName}"
        val legacy = inventory.legacyAt(path) ?: return null
        drive.setAppProperties(legacy.fileId, DriveInventory.tags(key, version))
        inventory.recordUpload(key, legacy.copy(version = version))
        return MediaResult.Stored(path, legacy.fileId, uploadedNow = false)
    }

    /**
     * The Drive name for a new upload: the item's own name, plus " (edited <date>)" for a new
     * version of an item already backed up, plus " (<MediaStore id>)" if that name is taken.
     */
    private fun chooseName(record: MediaRecord, isNewVersion: Boolean): String {
        val (stem, ext) = splitExtension(record.archiveFileName)
        val label = if (isNewVersion) " (edited ${today()})" else ""
        val preferred = "$stem$label$ext"
        return if (inventory.isNameTaken(preferred)) "$stem$label (${record.id})$ext" else preferred
    }

    /** `IMG_1.jpg` → (`IMG_1`, `.jpg`); a name without an extension keeps it all as the stem. */
    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }
}
