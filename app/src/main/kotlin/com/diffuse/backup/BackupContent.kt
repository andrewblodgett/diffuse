package com.diffuse.backup

/**
 * Which categories of data a backup run should include. Each flag gates a whole section of
 * [BackupRunner]: [messages] covers SMS, MMS, and call history together (the "text & call history"
 * choice), while [pictures] and [videos] gate their respective MediaStore sources.
 *
 * Turning a category off simply skips it — its incremental token is left untouched, so anything
 * created while it was off is still picked up if the user turns it back on. Default: everything on.
 */
data class BackupContent(
    val pictures: Boolean = true,
    val videos: Boolean = true,
    val messages: Boolean = true,
)
