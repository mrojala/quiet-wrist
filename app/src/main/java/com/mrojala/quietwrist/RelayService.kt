package com.mrojala.quietwrist

import android.app.Notification
import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
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

    /** Our notification id per source key, so each relay can be cancelled alone. */
    private val relayIds = HashMap<String, Int>()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * `ACTION_USER_PRESENT` alone was not clearing anything in practice, and there
     * is no way to find out why from a phone. So instead of one trigger that has to
     * work, there are several independent ones, and the log names whichever fired.
     *
     * `SCREEN_ON` arrives while the keyguard may still be up, so every trigger goes
     * through the same [phoneInUse] test rather than assuming what it implies.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            clearIfInUse(intent?.action?.substringAfterLast('.') ?: "broadcast")
        }
    }

    /**
     * The direct signal, where the platform offers it: a callback for the keyguard
     * being dismissed, rather than a broadcast that may or may not be delivered.
     *
     * Assigned only on API 33+, so the type is never loaded on older devices.
     */
    private var keyguardListener: KeyguardManager.KeyguardLockedStateListener? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun armKeyguardListener(): Boolean {
        val listener = KeyguardManager.KeyguardLockedStateListener { locked ->
            if (!locked) clearIfInUse("keyguard")
        }
        val manager = getSystemService(KeyguardManager::class.java) ?: return false
        manager.addKeyguardLockedStateListener(mainExecutor, listener)
        keyguardListener = listener
        return true
    }

    // Registered in onCreate rather than onListenerConnected: onCreate runs whenever
    // the system instantiates the service, so these cannot be left unarmed by a
    // binding callback that did not fire. Logged so the log can prove which it was.
    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter(Intent.ACTION_USER_PRESENT).apply {
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // RECEIVER_EXPORTED, not NOT_EXPORTED: both actions are protected broadcasts
        // that only the system can send, so this grants nothing, and it removes one
        // candidate explanation for the delivery never happening.
        val armed = runCatching {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.isSuccess

        val listening = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { armKeyguardListener() }.getOrDefault(false)

        Prefs.log(
            this,
            "${stamp()}  ——  clear triggers: broadcasts=$armed keyguard=$listening",
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            keyguardListener?.let { listener ->
                runCatching {
                    getSystemService(KeyguardManager::class.java)
                        ?.removeKeyguardLockedStateListener(listener)
                }
            }
        }
        super.onDestroy()
    }

    /**
     * Drops every relay, if the phone is genuinely in use and the setting allows it.
     *
     * Silent when there is nothing to clear — several triggers fire for the same
     * unlock, and the log is only 80 lines.
     */
    private fun clearIfInUse(trigger: String) {
        if (!Prefs.quietWhenUnlocked(this) || !phoneInUse()) return
        val waiting = pending.size
        if (waiting == 0 && relayIds.isEmpty()) return

        pending.values.forEach { handler.removeCallbacks(it.post) }
        pending.clear()
        relayIds.clear()
        NotificationManagerCompat.from(this).cancelAll()

        val dropped = if (waiting > 0) ", $waiting pending dropped" else ""
        Prefs.log(this, "${stamp()}  ——  cleared ($trigger)$dropped")
    }

    override fun onListenerConnected() {
        Prefs.log(this, "${stamp()}  ——  listener connected")
    }

    // Fires on shade interaction and other reshuffles, so it catches an unlock that
    // produced no new notification of its own.
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        clearIfInUse("in use")
    }

    override fun onListenerDisconnected() {
        pending.values.forEach { handler.removeCallbacks(it.post) }
        pending.clear()
        Prefs.log(this, "${stamp()}  ——  listener DISCONNECTED")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        // The backstop, and the one trigger that cannot fail to be delivered: this
        // callback fires for every app on the phone, so if relays are still showing
        // while you are using it, the next notification from anything clears them.
        clearIfInUse("in use")
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
        val id = try {
            Relay.post(this, entry.title.ifEmpty { "WhatsApp" }, entry.text, entry.notification)
        } catch (e: Throwable) {
            record("FAILED", "${e.javaClass.simpleName}: ${e.message} · ${entry.detail}", entry.title)
            return
        }

        // Remembered so that when WhatsApp's own notification goes away — you read
        // the chat, on the phone or anywhere else — the relay goes with it.
        relayIds.put(key, id)?.let { NotificationManagerCompat.from(this).cancel(it) }

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
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        clearIfInUse("in use")
        val key = sbn?.key ?: return
        lastRelayed.remove(key)
        // Dismissed inside the coalescing window: it was read elsewhere, don't buzz.
        pending.remove(key)?.let { handler.removeCallbacks(it.post) }
        // WhatsApp withdrew the original, so the relay has nothing left to stand for.
        relayIds.remove(key)?.let { NotificationManagerCompat.from(this).cancel(it) }
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

        // Nothing on the wrist while you are holding the phone: WhatsApp's own
        // notification is already in front of you. Checked here rather than cleared
        // afterwards, because ACTION_USER_PRESENT only fires on the unlock itself —
        // a message arriving during use would otherwise sit there indefinitely.
        if (Prefs.quietWhenUnlocked(this) && phoneInUse()) return "phone in use"
        return null
    }

    /**
     * Screen on and past the lock screen — you are looking at the phone.
     *
     * A device with no secure lock never reports the keyguard as locked, so there
     * the screen being awake is the whole signal, which is the same intent.
     */
    private fun phoneInUse(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return false
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return false
        return power.isInteractive && !keyguard.isKeyguardLocked
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
