package com.mrojala.quietwrist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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

    /** Relays waiting out [COALESCE_MS], keyed by notification key. */
    private val pending = HashMap<String, Pending>()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Clears the relays once the phone is unlocked: from then on WhatsApp's own
     * notification is in front of you, so the copy on the wrist is redundant.
     *
     * Registered here rather than in the manifest — `ACTION_USER_PRESENT` is not
     * deliverable to manifest receivers since Android 8. This service is already
     * bound by the system for the app's whole life, so there is nothing extra to
     * keep alive and no polling: it is one more callback.
     */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!Prefs.clearOnUnlock(this@RelayService)) return
            val waiting = pending.size
            pending.values.forEach { handler.removeCallbacks(it.post) }
            pending.clear()
            NotificationManagerCompat.from(this@RelayService).cancelAll()
            val dropped = if (waiting > 0) ", $waiting pending dropped" else ""
            Prefs.log(this@RelayService, "${stamp()}  ——  cleared on unlock$dropped")
        }
    }

    override fun onListenerConnected() {
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Prefs.log(this, "${stamp()}  ——  listener connected")
    }

    override fun onListenerDisconnected() {
        runCatching { unregisterReceiver(unlockReceiver) }
        pending.values.forEach { handler.removeCallbacks(it.post) }
        pending.clear()
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
        // WhatsApp gives every chat with custom notification settings its own
        // channel, named after the chat's JID, so this is per-chat.
        val channel = if (hasRanking) ranking.channel else null

        val detail = buildString {
            append("imp=").append(importanceName(importance))
            append(" ch=").append(notification.channelId ?: "-")
            append(" vib=").append(channel?.shouldVibrate() ?: "?")
            // Logged, never filtered on: on this phone it has read false for every
            // notification observed, including ones that demonstrably alerted. Why
            // is unconfirmed — plausibly the system stamps it around the moment it
            // notifies listeners, so we read it before it is written.
            append(" audible=").append(alertedAt > 0L)
            append(" flags=").append(flagNames(notification.flags))
        }

        val rejection = reasonToSkip(notification, text, hasRanking, importance, channel)
        if (rejection != null) {
            record("skip  ", "$rejection · $detail", title)
            return
        }

        schedule(sbn.key, title, text, notification, detail)
    }

    /**
     * Holds a relay briefly so a burst of updates to one chat becomes one buzz.
     *
     * WhatsApp re-posts the same notification key as a message lands, as its text
     * grows (a streaming bot reply arrives in pieces), and as delivery state
     * changes. Each of those carries different text, so a content fingerprint alone
     * sees three new messages and buzzes three times.
     *
     * Waiting [COALESCE_MS] and relaying only the settled version costs an
     * imperceptible delay and no battery — it is one main-looper message, not a
     * wake lock or an alarm.
     */
    private fun schedule(
        key: String,
        title: String,
        text: String,
        notification: Notification,
        detail: String,
    ) {
        val existing = pending[key]
        if (existing != null) {
            handler.removeCallbacks(existing.post)
            existing.title = title
            existing.text = text
            existing.notification = notification
            existing.detail = detail
            existing.updates++
            handler.postDelayed(existing.post, COALESCE_MS)
            return
        }

        val entry = Pending(title, text, notification, detail, Runnable { fire(key) })
        pending[key] = entry
        handler.postDelayed(entry.post, COALESCE_MS)
    }

    private fun fire(key: String) {
        val entry = pending.remove(key) ?: return

        // Guards against WhatsApp re-posting an identical notification later, which
        // the coalescing window is too short to catch.
        val fingerprint = "${entry.notification.`when`}|${entry.title}|${entry.text}"
        if (lastRelayed.put(key, fingerprint) == fingerprint) {
            record("dup   ", entry.detail, entry.title)
            return
        }

        // Posting re-uses WhatsApp's MessagingStyle, which carries its contact
        // avatars and message history. That is another app's data of unknown size
        // and shape, and notify() parcels the lot — so a bad notification must not
        // be allowed to take the listener down with it. Whatever it throws is
        // logged with its type, which is the only way to identify it from a phone.
        try {
            Relay.post(this, entry.title.ifEmpty { "WhatsApp" }, entry.text, entry.notification)
        } catch (e: Throwable) {
            record("FAILED", "${e.javaClass.simpleName}: ${e.message} · ${entry.detail}", entry.title)
            return
        }

        scheduleOriginalCleanup(key)
        val collapsed = if (entry.updates > 1) " ·${entry.updates} updates coalesced" else ""
        record("RELAY ", entry.detail + collapsed, entry.title)
    }

    /**
     * Clears WhatsApp's own notification on the same delay as the relay, so one
     * message doesn't sit in the shade twice.
     *
     * Best effort by design: a plain delayed message on the main looper, so it is
     * simply lost if the process is killed first. Using an alarm to guarantee it
     * would trade the app's zero background cost for tidying up a notification.
     */
    private fun scheduleOriginalCleanup(key: String) {
        if (!Prefs.clearOriginal(this)) return
        val minutes = Prefs.dismissMinutes(this)
        if (minutes <= 0) return
        handler.postDelayed({ cancelNotification(key) }, minutes * 60_000L)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val key = sbn?.key ?: return
        lastRelayed.remove(key)
        // A notification dismissed inside the window was read elsewhere; don't buzz.
        pending.remove(key)?.let { handler.removeCallbacks(it.post) }
    }

    private class Pending(
        var title: String,
        var text: String,
        var notification: Notification,
        var detail: String,
        val post: Runnable,
    ) {
        var updates: Int = 1
    }

    /** @return why this notification is not relayed, or null to relay it. */
    private fun reasonToSkip(
        notification: Notification,
        text: String,
        hasRanking: Boolean,
        importance: Int,
        channel: NotificationChannel?,
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

        // Importance alone misses a chat left audible but with vibration switched
        // off: it still arrives at IMPORTANCE_DEFAULT. Vibration is a per-channel
        // setting, and the point of this app is to relay only what buzzes.
        if (channel != null && !channel.shouldVibrate()) return "vibration off"
        return null
    }

    private fun record(verdict: String, detail: String, title: String) {
        Prefs.log(this, "${stamp()}  $verdict  ${title.ifEmpty { "(no title)" }}  —  $detail")
    }

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"

        /** Long enough to swallow a burst of updates, short enough to feel instant. */
        private const val COALESCE_MS = 900L

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
