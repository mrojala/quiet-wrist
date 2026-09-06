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
 */
class RelayService : NotificationListenerService() {

    /** Last message relayed per notification key, to skip WhatsApp's re-posts. */
    private val lastRelayed = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null || sbn.packageName != WHATSAPP_PACKAGE) return

        val notification = sbn.notification ?: return
        val flags = notification.flags

        // The group summary duplicates the per-chat notifications underneath it.
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // "Checking for new messages…", backup progress, and similar service notices.
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        val ranking = Ranking()
        if (rankingMap == null || !rankingMap.getRanking(sbn.key, ranking)) {
            record("?", "no ranking", title)
            return
        }

        val importance = ranking.importance
        val alertedAt = ranking.lastAudiblyAlertedMillis
        val channelId = notification.channelId ?: "-"
        val detail = "imp=${importanceName(importance)} ch=$channelId audible=${alertedAt > 0}"

        // A chat muted in WhatsApp is posted on a low-importance channel, so it
        // never alerts. That importance is the signal we filter on.
        if (importance < NotificationManager.IMPORTANCE_DEFAULT) {
            record("skip", detail, title)
            return
        }
        if (Prefs.requireAudible(this) && alertedAt <= 0L) {
            record("skip", "$detail (require-audible)", title)
            return
        }

        // WhatsApp updates the same notification key for every new message in a
        // chat, so relay once per distinct message rather than per update.
        val fingerprint = "${notification.`when`}|$title|$text"
        if (lastRelayed.put(sbn.key, fingerprint) == fingerprint) return

        Relay.post(this, title.ifEmpty { "WhatsApp" }, text, notification)
        record("relay", detail, title)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn != null) lastRelayed.remove(sbn.key)
    }

    private fun record(verdict: String, detail: String, title: String) {
        val stamp = TIME_FORMAT.format(Date(System.currentTimeMillis()))
        Prefs.log(this, "$stamp  $verdict  ${title.ifEmpty { "(no title)" }}  —  $detail")
    }

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"

        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

        private fun importanceName(importance: Int): String = when (importance) {
            NotificationManager.IMPORTANCE_NONE -> "none"
            NotificationManager.IMPORTANCE_MIN -> "min"
            NotificationManager.IMPORTANCE_LOW -> "low"
            NotificationManager.IMPORTANCE_DEFAULT -> "default"
            NotificationManager.IMPORTANCE_HIGH -> "high"
            NotificationManager.IMPORTANCE_MAX -> "max"
            else -> importance.toString()
        }
    }
}
