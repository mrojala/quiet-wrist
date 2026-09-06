package com.mrojala.quietwrist

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings plus a small on-device decision log.
 *
 * The log is the only practical way to tune the filter: notification metadata
 * differs between WhatsApp versions and Android builds, and `adb logcat` is not
 * available when the phone is out in the wild.
 */
object Prefs {
    private const val FILE = "quietwrist"
    private const val KEY_VIBRATE_PHONE = "vibrate_phone"
    private const val KEY_RELAY_EVERYTHING = "relay_everything"
    private const val KEY_LOG_ALL_APPS = "log_all_apps"
    private const val KEY_DISMISS_MINUTES = "dismiss_minutes"
    private const val KEY_CLEAR_ORIGINAL = "clear_original"

    /** Selectable auto-dismiss delays; 0 means never. */
    val DISMISS_CHOICES = listOf(1, 5, 10, 30, 60, 0)
    private const val KEY_LOG = "log"
    private const val LOG_LIMIT = 80
    private const val SEPARATOR = "\n"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * When off, relayed notifications are posted on a silent channel: the watch
     * still buzzes (Huawei Health reads the notification, not its sound), while
     * the phone does not double-alert.
     */
    fun vibratePhone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBRATE_PHONE, false)

    fun setVibratePhone(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_VIBRATE_PHONE, value).apply()

    /**
     * Debug bypass: relay every WhatsApp notification that carries a message,
     * whatever its importance. Isolates "the filter rejected it" from "the relay
     * never reached the watch", which are otherwise indistinguishable.
     */
    fun relayEverything(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RELAY_EVERYTHING, false)

    fun setRelayEverything(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_RELAY_EVERYTHING, value).apply()

    /**
     * Debug: also log notifications from every other app (never relaying them).
     * Confirms the listener is bound and receiving without needing someone to send
     * you a WhatsApp message.
     */
    fun logAllApps(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOG_ALL_APPS, false)

    fun setLogAllApps(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_LOG_ALL_APPS, value).apply()

    /**
     * Minutes after which a relayed notification clears itself, or 0 to keep it.
     *
     * Huawei Health mirrors dismissals, so clearing the relay also clears it from
     * the watch — which is why this is a delay rather than an immediate dismissal.
     */
    fun dismissMinutes(context: Context): Int =
        prefs(context).getInt(KEY_DISMISS_MINUTES, 10)

    fun setDismissMinutes(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_DISMISS_MINUTES, value).apply()

    /**
     * Also clear WhatsApp's own notification on the same delay, so a message does
     * not sit in the shade twice. Off by default: it removes the copy you would
     * otherwise catch up on from the phone.
     */
    fun clearOriginal(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLEAR_ORIGINAL, false)

    fun setClearOriginal(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_CLEAR_ORIGINAL, value).apply()

    fun log(context: Context, line: String) {
        val existing = readLog(context)
        val trimmed = (listOf(line) + existing).take(LOG_LIMIT)
        prefs(context).edit().putString(KEY_LOG, trimmed.joinToString(SEPARATOR)).apply()
    }

    /** Newest first. */
    fun readLog(context: Context): List<String> =
        prefs(context).getString(KEY_LOG, "")
            .orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }

    fun clearLog(context: Context) = prefs(context).edit().remove(KEY_LOG).apply()
}
