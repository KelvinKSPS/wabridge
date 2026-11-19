package com.orangepi.wabridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Notificações do app: a de serviço em primeiro plano e a de chamada
 * entrante (com full screen intent, que é o jeito garantido de abrir a
 * tela de chamada em relógio).
 */
object CallNotifier {

    const val CHANNEL_SERVICE = "wabridge"
    const val CHANNEL_CALL = "wabridge_call"
    const val CHANNEL_QUIET = "wabridge_quiet"
    const val NOTIFICATION_ID = 42
    const val NOTIFICATION_CALL_ID = 43

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_SERVICE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    context.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        if (manager.getNotificationChannel(CHANNEL_CALL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CALL,
                    context.getString(R.string.channel_call_name),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        // Single buzz, like an SMS — no full-screen ring, no auto-opened call screen.
        if (manager.getNotificationChannel(CHANNEL_QUIET) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_QUIET,
                    context.getString(R.string.channel_quiet_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }

    fun serviceNotification(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // speed action: it stays in the service notification (which is always
        // visible), so the speed can be changed without opening the call screen
        val speed = PendingIntent.getBroadcast(
            context,
            4,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_SPEED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_SERVICE)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.speed_value, PlaybackSpeed.label(VoiceSession.current?.speed ?: PlaybackSpeed.DEFAULT)),
                    speed,
                ).build(),
            )
            .build()
    }

    /** Notificação de chamada: abre a tela do app em tela cheia. */
    fun callNotification(context: Context, payload: CallPayload): Notification {
        val full = PendingIntent.getActivity(
            context,
            1,
            CallActivity.intent(context, payload),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val answer = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_ANSWER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reject = PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_REJECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // WhatsApp-style quick reply: sends the text and ends the call
        val quickReply = PendingIntent.getBroadcast(
            context,
            5,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_QUICK_REPLY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val subtitle = if (payload.durationSec > 0) {
            context.getString(
                R.string.incoming_call_duration,
                payload.displayName.ifBlank { payload.senderNumber },
                CallPayload.formatDuration(payload.durationSec),
            )
        } else {
            context.getString(R.string.incoming_call)
        }

        return Notification.Builder(context, CHANNEL_CALL)
            .setContentTitle(payload.displayName.ifBlank { payload.senderNumber })
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.answer), answer).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.reject), reject).build())
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.action_quick_reply),
                    quickReply,
                ).build(),
            )
            .build()
    }

    /**
     * Quiet notification for a single incoming note: buzzes once (like an SMS) and
     * offers a Play action that answers and plays the audio directly. Each note
     * gets its own notification ID so multiple arrivals show up separately.
     */
    fun quietNotification(context: Context, payload: CallPayload): Notification {
        val req = quietRequestCode(payload)
        val playIntent = PendingIntent.getActivity(
            context,
            req,
            CallActivity.intent(context, payload, autoAnswer = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            context,
            req xor 0x5A5A,
            Intent(context, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_DISMISS)
                .putExtra(BridgeExtras.PAYLOAD, payload.toJson()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val subtitle = if (payload.durationSec > 0) {
            "${context.getString(R.string.incoming_call)} · ${CallPayload.formatDuration(payload.durationSec)}"
        } else {
            context.getString(R.string.incoming_call)
        }

        return Notification.Builder(context, CHANNEL_QUIET)
            .setContentTitle(payload.displayName.ifBlank { payload.senderNumber })
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(playIntent)
            .setDeleteIntent(dismiss)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.action_play), playIntent).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.action_dismiss), dismiss).build())
            .build()
    }

    /** Stable, per-note notification ID so each quiet note appears separately. */
    fun quietNotificationId(payload: CallPayload): Int =
        10000 + (payload.audioId.hashCode() and 0xFFFF)

    /** Stable request code for quiet-notification PendingIntents. */
    fun quietRequestCode(payload: CallPayload): Int =
        20000 + (payload.audioId.hashCode() and 0x7FFF)
}
