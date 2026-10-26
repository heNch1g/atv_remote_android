package com.example.appletvremote

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The phone's own name, as the Apple TV should list it under Remotes.
 *
 * This is the name set in Settings -> About phone; it can be unset on a fresh
 * device, in which case the model number stands in.
 */
fun Context.ownDeviceName(): String =
    Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        ?.trim()?.takeIf { it.isNotEmpty() }
        ?: Build.MODEL
