package com.diffuse.backup

/**
 * Which categories of data a backup run should include: [messages] (SMS and MMS), [calls]
 * (the call log), and [pictures] / [videos] (their MediaStore sources). Settings offers
 * messages and calls as one "Messages & calls" choice, so [calls] defaults to [messages];
 * they're separate here because each needs its own permission, and denying one must not
 * stop the other from being backed up.
 *
 * Turning a category off simply skips it. Because every run re-exports each enabled category
 * in full, turning one back on picks up everything, including whatever was created while it
 * was off. Default: everything on.
 */
data class BackupContent(
    val pictures: Boolean = true,
    val videos: Boolean = true,
    val messages: Boolean = true,
    val calls: Boolean = messages,
) {
    val isEmpty: Boolean get() = !pictures && !videos && !messages && !calls

    /** The categories enabled in both this and [other]. */
    infix fun intersect(other: BackupContent) = BackupContent(
        pictures = pictures && other.pictures,
        videos = videos && other.videos,
        messages = messages && other.messages,
        calls = calls && other.calls,
    )

    /** The categories enabled here but not in [other]. */
    operator fun minus(other: BackupContent) = BackupContent(
        pictures = pictures && !other.pictures,
        videos = videos && !other.videos,
        messages = messages && !other.messages,
        calls = calls && !other.calls,
    )

    companion object {
        val NONE = BackupContent(pictures = false, videos = false, messages = false, calls = false)
    }
}
