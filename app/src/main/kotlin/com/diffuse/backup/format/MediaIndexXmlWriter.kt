package com.diffuse.backup.format

import com.diffuse.backup.model.MediaRecord

/**
 * Streams a `media-*.xml` index for the copied photos/videos. This is Diffuse's own
 * (not SMS Backup & Restore) format: the media bytes live as original files under
 * `media/<relative_path>/<display_name>`, and each `<media>` row records the metadata
 * plus the `backup_path` where its bytes were written.
 */
class MediaIndexXmlWriter(
    private val out: Appendable,
    private val backupDateMs: Long,
    private val backupSet: String,
) {
    fun start(count: Int) {
        out.append(Xml.DECLARATION).append('\n')
        val header = StringBuilder("<medias")
            .attr("count", count)
            .attr("backup_set", backupSet)
            .attr("backup_date", backupDateMs)
            .attr("type", "full")
            .append('>')
        out.append(header).append('\n')
    }

    fun writeMedia(r: MediaRecord, backupPath: String) {
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
            .append(" />")
        out.append(sb).append('\n')
    }

    fun finish() {
        out.append("</medias>").append('\n')
    }
}
