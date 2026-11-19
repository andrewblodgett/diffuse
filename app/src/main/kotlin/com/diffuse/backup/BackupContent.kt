package com.diffuse.backup

/**
 * Which categories of data a backup run should include. [messages] covers SMS, MMS, and
 * call history together (the "Messages & calls" choice), while [pictures] and [videos] gate
 * their respective MediaStore sources.
 *
 * Turning a category off simply skips it. Because every run re-exports each enabled category
 * in full, turning one back on picks up everything, including whatever was created while it
 * was off. Default: everything on.
 */
data class BackupContent(
    val pictures: Boolean = true,
    val videos: Boolean = true,
    val messages: Boolean = true,
) {
    val isEmpty: Boolean get() = !pictures && !videos && !messages

    /** The categories enabled in both this and [other]. */
    infix fun intersect(other: BackupContent) = BackupContent(
        pictures = pictures && other.pictures,
        videos = videos && other.videos,
        messages = messages && other.messages,
    )

    /** The categories enabled here but not in [other]. */
    operator fun minus(other: BackupContent) = BackupContent(
        pictures = pictures && !other.pictures,
        videos = videos && !other.videos,
        messages = messages && !other.messages,
    )

    companion object {
        val NONE = BackupContent(pictures = false, videos = false, messages = false)
    }
}
