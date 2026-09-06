package com.mrojala.quietwrist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/** Posts QuietWrist's own notifications — the ones Huawei Health is set to forward. */
object Relay {
    const val CHANNEL_SILENT = "relay_silent"
    const val CHANNEL_ALERT = "relay_alert"

    private const val TIMEOUT_MS = 10 * 60 * 1000L

    private var nextId = 1_000

    /**
     * @param replyAction WhatsApp's own reply action, forwarded verbatim when present.
     *   Its [android.app.PendingIntent] is just a token, so firing it from our
     *   notification sends the message as WhatsApp. This makes the relay repliable
     *   from the phone's shade; whether it also reaches the watch depends on the
     *   companion app, which typically whitelists quick reply per package name.
     */
    fun post(
        context: Context,
        title: String,
        text: String,
        replyAction: Notification.Action? = null,
    ) {
        createChannels(context)

        val channelId = if (Prefs.vibratePhone(context)) CHANNEL_ALERT else CHANNEL_SILENT
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            // Relays are transient nudges; don't let them pile up in the shade.
            .setTimeoutAfter(TIMEOUT_MS)

        context.packageManager.getLaunchIntentForPackage(RelayService.WHATSAPP_PACKAGE)?.let {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }

        replyAction?.let { addReplyAction(builder, it) }

        NotificationManagerCompat.from(context).notify(nextId++, builder.build())
    }

    private fun addReplyAction(
        builder: NotificationCompat.Builder,
        source: Notification.Action,
    ) {
        val remoteInput = source.remoteInputs?.firstOrNull() ?: return
        val intent = source.actionIntent ?: return

        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_stat_relay,
                source.title?.toString() ?: "Reply",
                intent,
            )
                .addRemoteInput(
                    RemoteInput.Builder(remoteInput.resultKey)
                        .setLabel(remoteInput.label)
                        .setAllowFreeFormInput(true)
                        .build()
                )
                .setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build()
        )
    }

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Visible in the shade and to Huawei Health, but makes no noise itself —
        // so only the watch buzzes.
        val silent = NotificationChannel(
            CHANNEL_SILENT,
            "Relayed messages (phone silent)",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        val alert = NotificationChannel(
            CHANNEL_ALERT,
            "Relayed messages (phone vibrates)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(silent, alert))
    }
}
