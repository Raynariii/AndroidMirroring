package com.androidmirroring.activation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import com.androidmirroring.MainActivity

/**
 * Pairing has to happen while Settings shows the pairing code, so the code is typed into a
 * notification reply instead of this app's own screen.
 */
object Notifications {
    private const val CHANNEL = "activation"
    private const val ID_PAIRING = 1
    private const val ID_START = 2
    const val KEY_CODE = "pairing_code"

    private fun manager(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Setup and reconnect", NotificationManager.IMPORTANCE_HIGH),
        )
        return nm
    }

    private fun base(context: Context, title: String, text: String): Notification.Builder =
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )

    fun showPairingInput(context: Context, message: String? = null) {
        val reply = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, PairingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(null, "Enter pairing code", reply)
            .addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel("6-digit code").build())
            .build()
        val text = message
            ?: "In Wireless debugging, tap \"Pair device with pairing code\", then enter the code here."
        manager(context).notify(
            ID_PAIRING,
            base(context, "Pair with this phone", text).addAction(action).setOngoing(true).build(),
        )
    }

    fun showPairingProgress(context: Context, text: String) {
        manager(context).notify(ID_PAIRING, base(context, "Pairing", text).setOngoing(true).build())
    }

    fun showPairingDone(context: Context) {
        manager(context).notify(
            ID_PAIRING,
            base(context, "Paired", "You can now start mirroring from the app.").setAutoCancel(true).build(),
        )
    }

    fun showStartPrompt(context: Context, text: String) {
        val start = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, StartHelperReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager(context).notify(
            ID_START,
            base(context, "Reconnect Android Mirroring", text)
                .addAction(Notification.Action.Builder(null, "Start", start).build())
                .setAutoCancel(true)
                .build(),
        )
    }

    fun showStartResult(context: Context, title: String, text: String) {
        manager(context).notify(ID_START, base(context, title, text).setAutoCancel(true).build())
    }
}
