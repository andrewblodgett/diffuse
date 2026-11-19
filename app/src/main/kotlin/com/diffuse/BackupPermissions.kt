package com.diffuse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.diffuse.backup.BackupContent

/**
 * Which runtime permissions each backup category needs. A category is backed up only when
 * its permissions are granted, and a missing permission skips just that category, so
 * denying photo (or call log) access never stops messages from being backed up.
 */
object BackupPermissions {

    private val MESSAGES = listOf(Manifest.permission.READ_SMS)
    private val CALLS = listOf(Manifest.permission.READ_CALL_LOG)
    private val PICTURES = listOf(Manifest.permission.READ_MEDIA_IMAGES)
    private val VIDEOS = listOf(Manifest.permission.READ_MEDIA_VIDEO)

    /**
     * Nice to have, never required. Location lets photos and videos keep their GPS metadata
     * (without it Android strips location from the bytes we read); notifications show
     * progress and problems.
     */
    private const val MEDIA_LOCATION = Manifest.permission.ACCESS_MEDIA_LOCATION
    private const val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    /** The permissions [content] can't be backed up without. */
    fun requiredFor(content: BackupContent): List<String> = buildList {
        if (content.messages) addAll(MESSAGES)
        if (content.calls) addAll(CALLS)
        if (content.pictures) addAll(PICTURES)
        if (content.videos) addAll(VIDEOS)
    }

    /** Everything worth asking for before backing up [content]: its required permissions plus the optional ones. */
    fun toRequest(content: BackupContent): Array<String> = buildList {
        addAll(requiredFor(content))
        if (content.pictures || content.videos) add(MEDIA_LOCATION)
        add(NOTIFICATIONS)
    }.toTypedArray()

    /** The categories whose required permissions are all granted. */
    fun permitted(context: Context): BackupContent = BackupContent(
        messages = MESSAGES.all { context.isGranted(it) },
        calls = CALLS.all { context.isGranted(it) },
        pictures = PICTURES.all { context.isGranted(it) },
        videos = VIDEOS.all { context.isGranted(it) },
    )

    fun canReadPhotoLocation(context: Context): Boolean =
        context.isGranted(MEDIA_LOCATION)

    private fun Context.isGranted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
