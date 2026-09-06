package com.mrojala.quietwrist

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Listens to WhatsApp notifications and re-posts, under QuietWrist's own package
 * name, only the ones WhatsApp meant to alert for.
 *
 * The point of the indirection: Huawei Health decides what reaches the watch per
 * *package*, with no per-chat filtering. Turning WhatsApp off in Huawei Health and
 * QuietWrist on means the watch only buzzes for messages that pass this filter.
 *
 * Every WhatsApp notification that arrives is logged with its verdict and the
 * metadata behind it. Nothing is dropped silently — a message that vanished
 * without a trace is impossible to debug from a phone in your pocket.
 */
class RelayService : NotificationListenerService() {

    /** Last message relayed per notification key, to skip WhatsApp's re-posts. */
    private val lastRelayed = HashMap<String, String>()

    override fun onListenerConnected() {
        Prefs.log(this, "${stamp()}  ——  listener connected")
    }

    override fun onListenerDisconnected() {
        Prefs.log(this, "${stamp()}  ——  listener DISCONNECTED")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        if (sbn.packageName != WHATSAPP_PACKAGE) {
            // Proves the listener is alive without waiting for someone to message you:
            // any notification from any app shows up here.
            if (Prefs.logAllApps(this)) {
                Prefs.log(this, "${stamp()}  other   ${sbn.packageName}")
            }
            return
        }
        val notification = sbn.notification ?: return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?.toString()?.trim().orEmpty()

        val ranking = Ranking()
        val hasRanking = rankingMap?.getRanking(sbn.key, ranking) == true
        val importance = if (hasRanking) ranking.importance else UNKNOWN_IMPORTANCE
        val alertedAt = if (hasRanking) ranking.lastAudiblyAlertedMillis else 0L

        val detail = buildString {
            append("imp=").append(importanceName(importance))
            append(" ch=").append(notification.channelId ?: "-")
            // Informational only. The system stamps this around the time listeners
            // are notified, so it frequently reads false for a notification that
            // did vibrate. Never filter on it.
            append(" audible=").append(alertedAt > 0L)
            append(" flags=").append(flagNames(notification.flags))
        }

        val rejection = reasonToSkip(notification, text, hasRanking, importance)
        if (rejection != null) {
            record("skip  ", "$rejection · $detail", title)
            return
        }

        // WhatsApp updates the same notification key for every new message in a
        // chat, so relay once per distinct message rather than per update.
        val fingerprint = "${notification.`when`}|$title|$text"
        if (lastRelayed.put(sbn.key, fingerprint) == fingerprint) {
            record("dup   ", detail, title)
            return
        }

        Relay.post(this, title.ifEmpty { "WhatsApp" }, text, notification)
        record("RELAY ", detail, title)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn != null) lastRelayed.remove(sbn.key)
    }

    /** @return why this notification is not relayed, or null to relay it. */
    private fun reasonToSkip(
        notification: Notification,
        text: String,
        hasRanking: Boolean,
        importance: Int,
    ): String? {
        // The group summary duplicates the per-chat notifications underneath it.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return "group summary"
        // "Checking for new messages…", backup progress, and similar service notices.
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return "ongoing"
        if (text.isEmpty()) return "no text"

        // The debug bypass: relay everything that carries a message, so the rest of
        // the chain (posting, Huawei Health, the watch) can be tested on its own.
        if (Prefs.relayEverything(this)) return null

        if (!hasRanking) return "no ranking"
        // A chat muted in WhatsApp is posted on a low-importance channel, so it
        // never alerts. That importance is the signal we filter on.
        if (importance < NotificationManager.IMPORTANCE_DEFAULT) return "silent channel"
        return null
    }

    private fun record(verdict: String, detail: String, title: String) {
        Prefs.log(this, "${stamp()}  $verdict  ${title.ifEmpty { "(no title)" }}  —  $detail")
    }

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"

        private const val UNKNOWN_IMPORTANCE = Int.MIN_VALUE
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

        private fun stamp(): String = TIME_FORMAT.format(Date(System.currentTimeMillis()))

        private fun importanceName(importance: Int): String = when (importance) {
            UNKNOWN_IMPORTANCE -> "?"
            NotificationManager.IMPORTANCE_NONE -> "none"
            NotificationManager.IMPORTANCE_MIN -> "min"
            NotificationManager.IMPORTANCE_LOW -> "low"
            NotificationManager.IMPORTANCE_DEFAULT -> "default"
            NotificationManager.IMPORTANCE_HIGH -> "high"
            NotificationManager.IMPORTANCE_MAX -> "max"
            else -> importance.toString()
        }

        private fun flagNames(flags: Int): String {
            val names = buildList {
                if (flags and Notification.FLAG_GROUP_SUMMARY != 0) add("summary")
                if (flags and Notification.FLAG_ONGOING_EVENT != 0) add("ongoing")
                if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) add("fgs")
                if (flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) add("alert-once")
                if (flags and Notification.FLAG_LOCAL_ONLY != 0) add("local-only")
                if (flags and Notification.FLAG_AUTO_CANCEL != 0) add("auto-cancel")
            }
            return if (names.isEmpty()) "-" else names.joinToString("+")
        }
    }
}
