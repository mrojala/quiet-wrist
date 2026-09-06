package com.mrojala.quietwrist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/** Posts QuietWrist's own notifications — the ones Huawei Health is set to forward. */
object Relay {
    const val CHANNEL_SILENT = "relay_silent"
    const val CHANNEL_ALERT = "relay_alert"

    private const val TIMEOUT_MS = 10 * 60 * 1000L

    private var nextId = 1_000

    /**
     * Re-posts [text] under QuietWrist's package name.
     *
     * [source] is the original WhatsApp notification, when there is one. As much of
     * it as possible is carried over — the MessagingStyle conversation and the reply
     * action — because companion apps decide whether to offer quick reply by
     * inspecting the notification, and the closer the relay looks to a real chat
     * message the better its odds.
     */
    fun post(
        context: Context,
        title: String,
        text: String,
        source: Notification? = null,
    ) {
        createChannels(context)

        val channelId = if (Prefs.vibratePhone(context)) CHANNEL_ALERT else CHANNEL_SILENT
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            // Relays are transient nudges; don't let them pile up in the shade.
            .setTimeoutAfter(TIMEOUT_MS)
            .setStyle(messagingStyle(source, title, text))
            .setShowWhen(true)

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

        source?.actions
            ?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
            ?.let { addReplyAction(builder, it) }

        NotificationManagerCompat.from(context).notify(nextId++, builder.build())
    }

    /**
     * WhatsApp posts chats as MessagingStyle. Reusing the original style keeps the
     * sender, the group name, and the message history intact; otherwise we
     * synthesise a single-message conversation from what we have.
     */
    private fun messagingStyle(
        source: Notification?,
        title: String,
        text: String,
    ): NotificationCompat.MessagingStyle {
        val original = source?.let {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)
        }
        if (original != null && original.messages.isNotEmpty()) return original

        val self = Person.Builder().setName("Me").build()
        val sender = Person.Builder().setName(title).build()
        return NotificationCompat.MessagingStyle(self)
            .addMessage(text, System.currentTimeMillis(), sender)
    }

    /**
     * WhatsApp's reply action, forwarded verbatim. Its [PendingIntent] is only a
     * token, so firing it from our notification sends the message as WhatsApp.
     */
    private fun addReplyAction(
        builder: NotificationCompat.Builder,
        source: Notification.Action,
    ) {
        val remoteInput = source.remoteInputs?.firstOrNull() ?: return
        val intent = source.actionIntent ?: return

        val action = NotificationCompat.Action.Builder(
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

        builder.addAction(action)
        // Some companion apps read only the wearable action list.
        builder.extend(NotificationCompat.WearableExtender().addAction(action))
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
