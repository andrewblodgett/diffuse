package com.diffuse.drive

import com.diffuse.drive.store.RecentUploads

/**
 * What Diffuse has on Drive right now, built fresh from a Drive listing at the start of every
 * run. Drive is the source of truth, not a local cache, which is what makes the backup heal
 * itself:
 *  - files the user deleted from Drive are simply absent, so they get uploaded again;
 *  - after a reinstall or on a new phone, everything already on Drive is recognised, so
 *    nothing is uploaded twice;
 *  - there is no local manifest that a crash could corrupt.
 *
 * Every file Diffuse uploads is tagged with two private `appProperties`: [KEY_PROPERTY] says
 * *what* it is (a media item's [com.diffuse.backup.model.MediaRecord.stableId], or a
 * document's name) and [VERSION_PROPERTY] says *which version* (a media file's byte length,
 * a document's content fingerprint). When several files share a key, the newest wins.
 *
 * Two extra inputs fill gaps in the listing: [RecentUploads] (uploads so new the listing may
 * not show them yet) and the 0.1.x manifest's paths (files uploaded before tagging existed).
 */
class DriveInventory(
    files: List<DriveFile>,
    private val recentUploads: RecentUploads,
    legacyMediaPaths: Map<String, String> = emptyMap(),
) {
    data class Entry(val fileId: String, val name: String, val version: String?)

    private val byKey = HashMap<String, Entry>()
    private val legacyByPath = HashMap<String, Entry>()
    private val namesInUse = HashSet<String>()

    init {
        val legacyPathById = legacyMediaPaths.entries.associate { (path, id) -> id to path }
        for (file in files) { // oldest first, so a newer file with the same key replaces an older one
            namesInUse += file.name
            val key = file.appProperties[KEY_PROPERTY]
            if (key != null) {
                byKey[key] = Entry(file.id, file.name, file.appProperties[VERSION_PROPERTY])
            } else {
                legacyPathById[file.id]?.let { path -> legacyByPath[path] = Entry(file.id, file.name, null) }
            }
        }
        val listedIds = files.mapTo(HashSet()) { it.id }
        for (recent in recentUploads.load()) {
            if (recent.fileId !in listedIds) {
                byKey[recent.key] = Entry(recent.fileId, recent.name, recent.version)
                namesInUse += recent.name
            }
        }
    }

    operator fun get(key: String): Entry? = byKey[key]

    /** Note a file just uploaded (or updated) under [key], here and in the recent-uploads journal. */
    fun recordUpload(key: String, entry: Entry) {
        byKey[key] = entry
        namesInUse += entry.name
        recentUploads.record(RecentUploads.Entry(key, entry.fileId, entry.name, entry.version.orEmpty()))
    }

    /** True if some Diffuse file on Drive already has this name (in any folder). */
    fun isNameTaken(name: String): Boolean = name in namesInUse

    /** An untagged file uploaded by Diffuse 0.1.x at archive path [path], if one is still on Drive. */
    fun legacyAt(path: String): Entry? = legacyByPath[path]

    companion object {
        const val KEY_PROPERTY = "diffuseKey"
        const val VERSION_PROPERTY = "diffuseVersion"

        fun mediaKey(stableId: String) = "media:$stableId"
        fun documentKey(name: String) = "doc:$name"
        fun tags(key: String, version: String) = mapOf(KEY_PROPERTY to key, VERSION_PROPERTY to version)

        /** List Drive and build the inventory. */
        fun load(drive: DriveApi, recentUploads: RecentUploads, legacyMediaPaths: Map<String, String>) =
            DriveInventory(drive.listFiles(), recentUploads, legacyMediaPaths)
    }
}
