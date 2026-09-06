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
    private const val KEY_REQUIRE_AUDIBLE = "require_audible"
    private const val KEY_VIBRATE_PHONE = "vibrate_phone"
    private const val KEY_LOG = "log"
    private const val LOG_LIMIT = 80
    private const val SEPARATOR = "\n"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * When on, a notification is relayed only if the system says it actually made
     * a sound or vibration. Stricter, but relays nothing while the phone's ringer
     * is silenced or Do Not Disturb is on — hence off by default.
     */
    fun requireAudible(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUIRE_AUDIBLE, false)

    fun setRequireAudible(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_REQUIRE_AUDIBLE, value).apply()

    /**
     * When off, relayed notifications are posted on a silent channel: the watch
     * still buzzes (Huawei Health reads the notification, not its sound), while
     * the phone does not double-alert.
     */
    fun vibratePhone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBRATE_PHONE, false)

    fun setVibratePhone(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_VIBRATE_PHONE, value).apply()

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
