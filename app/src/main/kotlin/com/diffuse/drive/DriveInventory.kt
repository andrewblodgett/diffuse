package com.diffuse.drive

import com.diffuse.drive.store.RecentUploads

/**
 * What Diffuse has on Drive right now, built fresh from a Drive listing at the start of every
 * run. Drive is the source of truth, not a local cache, which is what makes the backup heal
 * itself:
 *  - files the user deleted from Drive are simply absent, so they get uploaded again;
 *  - after a reinstall on the same phone, everything already on Drive is recognised, so
 *    nothing is uploaded twice;
 *  - there is no local manifest that a crash could corrupt.
 *
 * Every file Diffuse uploads is tagged with private `appProperties`: [KEY_PROPERTY] says
 * *what* it is (a media item's [com.diffuse.backup.model.MediaRecord.stableId], or a
 * document's name) and [VERSION_PROPERTY] says *which version* (a media file's byte length,
 * a document's content fingerprint). Media also carries [ORIGIN_PROPERTY], which pins the key
 * to one particular item: MediaStore ids restart on a new phone or a rebuilt media database,
 * so `images:42` alone could name an unrelated photo. [FOLDER_PROPERTY] records where on
 * Drive the bytes went, when it fits Drive's size limit for a property.
 *
 * Two extra inputs fill gaps in the listing: [RecentUploads] (uploads so new the listing may
 * not show them yet) and the 0.1.x manifest's paths (files uploaded before tagging existed).
 */
class DriveInventory(
    files: List<DriveFile>,
    private val recentUploads: RecentUploads,
    legacyMediaPaths: Map<String, String> = emptyMap(),
) {
    data class Entry(
        val fileId: String,
        val name: String,
        val version: String?,
        /** Media only: which item this is, beyond its MediaStore id. */
        val origin: String? = null,
        /** Media only: the archive folder the file was uploaded to, when recorded. */
        val folder: String? = null,
        /** The Drive file's size in bytes, when the listing reported it. */
        val size: Long? = null,
    )

    /** Every file per key, oldest first (several phones, or several versions of one item). */
    private val byKey = HashMap<String, MutableList<Entry>>()
    private val legacyByPath = HashMap<String, Entry>()
    private val namesInUse = HashSet<String>()

    init {
        val legacyPathById = legacyMediaPaths.entries.associate { (path, id) -> id to path }
        for (file in files) { // oldest first
            namesInUse += file.name
            val key = file.appProperties[KEY_PROPERTY]
            if (key != null) {
                add(key, Entry(
                    fileId = file.id,
                    name = file.name,
                    version = file.appProperties[VERSION_PROPERTY],
                    origin = file.appProperties[ORIGIN_PROPERTY],
                    folder = file.appProperties[FOLDER_PROPERTY],
                    size = file.size,
                ))
            } else {
                legacyPathById[file.id]?.let { path ->
                    legacyByPath[path] = Entry(file.id, file.name, version = null, size = file.size)
                }
            }
        }
        val listedIds = files.mapTo(HashSet()) { it.id }
        for (recent in recentUploads.load()) {
            if (recent.fileId !in listedIds) {
                add(recent.key, Entry(
                    fileId = recent.fileId,
                    name = recent.name,
                    version = recent.version,
                    origin = recent.origin.ifEmpty { null },
                    folder = recent.folder.ifEmpty { null },
                ))
                namesInUse += recent.name
            }
        }
    }

    private fun add(key: String, entry: Entry) {
        byKey.getOrPut(key) { mutableListOf() } += entry
    }

    /** The newest file under [key] (documents, which have no origin). */
    operator fun get(key: String): Entry? = byKey[key]?.lastOrNull()

    /** The newest file under [key] that belongs to the item identified by [origin]. */
    fun find(key: String, origin: String): Entry? = byKey[key]?.lastOrNull { it.origin == origin }

    /** Note a file just uploaded (or updated) under [key], here and in the recent-uploads journal. */
    fun recordUpload(key: String, entry: Entry) {
        add(key, entry)
        namesInUse += entry.name
        recentUploads.record(
            RecentUploads.Entry(
                key = key,
                fileId = entry.fileId,
                name = entry.name,
                version = entry.version.orEmpty(),
                origin = entry.origin.orEmpty(),
                folder = entry.folder.orEmpty(),
            ),
        )
    }

    /** True if some Diffuse file on Drive already has this name (in any folder). */
    fun isNameTaken(name: String): Boolean = name in namesInUse

    /**
     * Claim the untagged file Diffuse 0.1.x uploaded at archive path [path], if it's still on
     * Drive. Each legacy file can be claimed once, so two items that share a path can't both
     * be matched to the one file 0.1.x uploaded for them.
     */
    fun takeLegacy(path: String): Entry? = legacyByPath.remove(path)

    companion object {
        const val KEY_PROPERTY = "diffuseKey"
        const val VERSION_PROPERTY = "diffuseVersion"
        const val ORIGIN_PROPERTY = "diffuseOrigin"
        const val FOLDER_PROPERTY = "diffuseFolder"

        /** Drive's limit for one property: key plus value, in UTF-8 bytes. */
        private const val MAX_PROPERTY_BYTES = 124

        fun mediaKey(stableId: String) = "media:$stableId"
        fun documentKey(deviceId: String, name: String) = "doc:$deviceId:$name"
        fun tags(key: String, version: String) = mapOf(KEY_PROPERTY to key, VERSION_PROPERTY to version)

        /** Tags for a media file. The folder is left out if it's too long for one property. */
        fun mediaTags(key: String, version: String, origin: String, folder: String): Map<String, String> {
            val fits = (FOLDER_PROPERTY + folder).toByteArray(Charsets.UTF_8).size <= MAX_PROPERTY_BYTES
            return tags(key, version) + (ORIGIN_PROPERTY to origin) +
                (if (fits) mapOf(FOLDER_PROPERTY to folder) else emptyMap())
        }

        /** List Drive and build the inventory. */
        fun load(drive: DriveApi, recentUploads: RecentUploads, legacyMediaPaths: Map<String, String>) =
            DriveInventory(drive.listFiles(), recentUploads, legacyMediaPaths)
    }
}
