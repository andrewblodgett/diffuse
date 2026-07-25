package com.diffuse.drive.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.diffuse.drive.auth.DriveCredentialStore

/**
 * [DriveCredentialStore] backed by [EncryptedSharedPreferences], so the Google refresh
 * token is encrypted at rest with a key held in the Android Keystore (hardware-backed
 * where available). This is the only place the raw tokens live on the device.
 *
 * READ-ONLY: writes to our own app preferences, never to a content provider, so it does
 * not touch the read-only invariant.
 */
class EncryptedDriveCredentialStore(context: Context) : DriveCredentialStore {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "diffuse_drive_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) = prefs.edit().putStringOrRemove(KEY_REFRESH, value).apply()

    override var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) = prefs.edit().putStringOrRemove(KEY_ACCESS, value).apply()

    override var accessTokenExpiryMs: Long
        get() = prefs.getLong(KEY_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPIRY, value).apply()

    override fun save(refreshToken: String?, accessToken: String?, expiryMs: Long) {
        prefs.edit()
            .putStringOrRemove(KEY_REFRESH, refreshToken)
            .putStringOrRemove(KEY_ACCESS, accessToken)
            .putLong(KEY_EXPIRY, expiryMs)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun SharedPreferences.Editor.putStringOrRemove(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)

    private companion object {
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ACCESS = "access_token"
        const val KEY_EXPIRY = "access_token_expiry_ms"
    }
}
