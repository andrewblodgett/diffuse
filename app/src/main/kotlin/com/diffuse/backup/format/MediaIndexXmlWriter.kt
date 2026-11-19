package com.diffuse.backup.format

import com.diffuse.backup.model.MediaRecord

/**
 * Writes a `photos-*.xml` / `videos-*.xml` index for the uploaded media. This is
 * Diffuse's own (not SMS Backup & Restore) format: the media bytes live as original files
 * under `media/<relative_path>/`, and each `<media>` row records the metadata plus
 * `backup_path` (where its bytes are in the archive) and `drive_file_id` (the exact Drive
 * file, which disambiguates two files that ended up with the same name).
 */
class MediaIndexXmlWriter(
    private val backupDateMs: Long,
    private val backupSet: String,
) {
    fun header(out: Appendable, count: Int) {
        out.append(Xml.DECLARATION).append('\n')
        val header = StringBuilder("<medias")
            .attr("count", count)
            .attr("backup_set", backupSet)
            .attr("backup_date", backupDateMs)
            .attr("type", "full")
            .append('>')
        out.append(header).append('\n')
    }

    fun media(out: Appendable, r: MediaRecord, backupPath: String, driveFileId: String?) {
        val sb = StringBuilder("  <media")
            .attr("kind", r.kind.sourceId)
            .attr("id", r.id)
            .attr("display_name", r.displayName)
            .attr("relative_path", r.relativePath)
            .attr("mime_type", r.mimeType)
            .attr("size", r.sizeBytes)
            .attr("date_taken", r.dateTakenEpochMs?.toString())
            .attr("date_added", r.dateAddedEpochMs)
            .attr("date_modified", r.dateModifiedEpochMs)
            .attr("generation_modified", r.generationModified)
            .attr("backup_path", backupPath)
            .attr("drive_file_id", driveFileId)
            .append(" />")
        out.append(sb).append('\n')
    }

    fun footer(out: Appendable) {
        out.append("</medias>").append('\n')
    }
}
