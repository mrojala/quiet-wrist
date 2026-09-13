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
    private const val KEY_QUIET_WHEN_UNLOCKED = "quiet_when_unlocked"
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
     * Stay off the wrist while the phone is unlocked and awake.
     *
     * Covers both halves of that: a message arriving while you are using the phone
     * is never relayed, and relays that arrived while it was locked are cleared the
     * moment you unlock. Either way WhatsApp's own notification is in front of you,
     * so the copy on the wrist has nothing left to do.
     */
    fun quietWhenUnlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_QUIET_WHEN_UNLOCKED, true)

    fun setQuietWhenUnlocked(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_QUIET_WHEN_UNLOCKED, value).apply()

    fun log(context: Context, line: String) {
        prefs(context).edit().putString(KEY_LOG, appended(context, line)).apply()
    }

    /**
     * Like [log], but writes synchronously — for use from a crash handler, where
     * the process is about to die and an async commit would be lost.
     */
    fun logBlocking(context: Context, line: String) {
        prefs(context).edit().putString(KEY_LOG, appended(context, line)).commit()
    }

    private fun appended(context: Context, line: String): String =
        (listOf(line) + readLog(context)).take(LOG_LIMIT).joinToString(SEPARATOR)

    /** Newest first. */
    fun readLog(context: Context): List<String> =
        prefs(context).getString(KEY_LOG, "")
            .orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }

    fun clearLog(context: Context) = prefs(context).edit().remove(KEY_LOG).apply()
}
