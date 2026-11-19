package com.diffuse.drive.store

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.diffuse.drive.auth.DriveCredentialStore
import java.security.KeyStore

/**
 * [DriveCredentialStore] backed by [EncryptedSharedPreferences], so the Google refresh
 * token is encrypted at rest with a key held in the Android Keystore (hardware-backed
 * where available). This is the only place the raw tokens live on the device.
 *
 * READ-ONLY: writes to our own app preferences, never to a content provider, so it does
 * not touch the read-only invariant.
 */
class EncryptedDriveCredentialStore(context: Context) : DriveCredentialStore {

    private val prefs: SharedPreferences = openOrReset(context.applicationContext)

    /**
     * Open the encrypted prefs. If that fails (the Keystore key was lost or the file no longer
     * decrypts, which happens on some devices after OS updates or restores), wipe both and
     * start empty: the user has to reconnect Drive once, instead of the app crashing on every
     * launch.
     */
    private fun openOrReset(context: Context): SharedPreferences = try {
        open(context)
    } catch (e: Exception) {
        Log.w(TAG, "credential store unreadable; resetting it", e)
        context.deleteSharedPreferences(PREFS_NAME)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
        open(context)
    }

    private fun open(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
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

    override var accountEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit().putStringOrRemove(KEY_EMAIL, value).apply()

    @SuppressLint("ApplySharedPref") // deliberate, see below
    override fun save(refreshToken: String?, accessToken: String?, expiryMs: Long) {
        prefs.edit()
            .putStringOrRemove(KEY_REFRESH, refreshToken)
            .putStringOrRemove(KEY_ACCESS, accessToken)
            .putLong(KEY_EXPIRY, expiryMs)
            .commit() // synchronous: a fresh sign-in must not be lost if the process dies next
    }

    @SuppressLint("ApplySharedPref") // a sign-out must stick even if the process dies next
    override fun clear() {
        prefs.edit().clear().commit()
    }

    private fun SharedPreferences.Editor.putStringOrRemove(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)

    private companion object {
        const val TAG = "DiffuseCredentials"
        const val PREFS_NAME = "diffuse_drive_credentials"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ACCESS = "access_token"
        const val KEY_EXPIRY = "access_token_expiry_ms"
        const val KEY_EMAIL = "account_email"
    }
}
