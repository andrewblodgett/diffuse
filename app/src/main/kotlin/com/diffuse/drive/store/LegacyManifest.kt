package com.diffuse.drive.store

import java.io.File
import java.util.Properties

/**
 * Reads the upload manifest written by Diffuse 0.1.x (`upload-manifest.properties`:
 * archive path → Drive file id), so an upgraded install recognises the media it already
 * uploaded instead of uploading the whole library again. Files from that version carry no
 * Diffuse tags on Drive, so this is the only link between them and the phone's media.
 *
 * Read-only and transitional: once a run completes cleanly, every item still on the phone
 * has been matched and tagged on Drive, and the file is deleted ([retire]).
 */
class LegacyManifest(private val file: File) {

    /** Archive path (e.g. `media/DCIM/Camera/a.jpg`) → Drive file id; empty if none or unreadable. */
    fun mediaPaths(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val props = runCatching { Properties().apply { file.inputStream().use { load(it) } } }
            .getOrElse { return emptyMap() }
        return props.stringPropertyNames()
            .filter { it.startsWith("media/") }
            .associateWith { props.getProperty(it) }
    }

    fun retire() {
        file.delete()
    }
}
