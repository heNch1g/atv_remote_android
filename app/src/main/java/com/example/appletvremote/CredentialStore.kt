package com.example.appletvremote

import android.content.Context
import com.example.appletvremote.protocol.discovery.AppleTvDevice
import com.example.appletvremote.protocol.hap.Credentials

/**
 * Persists pairing credentials per device, encrypted by [SecureStore].
 *
 * The key deliberately excludes the port: an Apple TV rotates its ephemeral
 * Companion Link port, so including it would orphan the stored pairing.
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pairings", Context.MODE_PRIVATE)

    fun save(key: String, credentials: Credentials) {
        prefs.edit().putString(key, SecureStore.encrypt(credentials.serialize())).apply()
    }

    fun load(key: String): Credentials? {
        val stored = prefs.getString(key, null) ?: return null

        SecureStore.decrypt(stored)?.let { plaintext ->
            return runCatching { Credentials.parse(plaintext) }.getOrNull()
        }

        // Written by an older build that stored the value unencrypted: parse
        // it, then rewrite it encrypted so the plaintext does not survive.
        val legacy = runCatching { Credentials.parse(stored) }.getOrNull() ?: return null
        save(key, legacy)
        return legacy
    }

    /** The last device connected to, so the list has an entry immediately. */
    fun saveLastDevice(device: AppleTvDevice) {
        prefs.edit()
            .putString("last-name", device.name)
            .putString("last-address", device.address)
            .putInt("last-port", device.port)
            .putString("last-model", device.model)
            .putString("last-identifier", device.identifier)
            .apply()
    }

    fun loadLastDevice(): AppleTvDevice? {
        val name = prefs.getString("last-name", null) ?: return null
        val address = prefs.getString("last-address", null) ?: return null
        return AppleTvDevice(
            name = name,
            address = address,
            port = prefs.getInt("last-port", 0),
            model = prefs.getString("last-model", null),
            identifier = prefs.getString("last-identifier", null),
        )
    }

    fun forget(key: String) {
        // Dropping the Companion pairing makes the AirPlay one useless, and
        // leaving it behind would keep a stale key in the paired-device set.
        prefs.edit()
            .remove(key)
            .remove(key + KEY_AIRPLAY_SUFFIX)
            .apply()
    }

    fun isPaired(key: String): Boolean = prefs.contains(key)

    /**
     * Every device that has a stored Companion pairing. AirPlay credentials
     * live under a separate `<key>-airplay` entry and must not appear as
     * devices of their own.
     */
    fun pairedKeys(): Set<String> = prefs.all.keys
        .filter { !it.endsWith(KEY_AIRPLAY_SUFFIX) && !it.startsWith("last-") }
        .toSet()

    // Now-playing needs a second, independent AirPlay pairing with its own PIN.
    fun saveAirPlay(key: String, credentials: Credentials) =
        save(key + KEY_AIRPLAY_SUFFIX, credentials)

    fun loadAirPlay(key: String): Credentials? = load(key + KEY_AIRPLAY_SUFFIX)

    fun isAirPlayPaired(key: String): Boolean = isPaired(key + KEY_AIRPLAY_SUFFIX)

    fun forgetAirPlay(key: String) {
        prefs.edit().remove(key + KEY_AIRPLAY_SUFFIX).apply()
    }

    private companion object {
        const val KEY_AIRPLAY_SUFFIX = "-airplay"
    }
}
