package com.androidmirroring.activation

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Receives the pairing code typed into the notification and pairs in the background. */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notifications.KEY_CODE)?.toString()?.trim()
        if (code.isNullOrEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "adb-pairing") {
            try {
                Notifications.showPairingProgress(app, "Looking for the pairing service…")
                val port = AdbMdns.findLocalPort(app, AdbMdns.PAIRING)
                if (port == null) {
                    Notifications.showPairingInput(
                        app,
                        "The pairing dialog isn't open. Open \"Pair device with pairing code\" and enter the new code.",
                    )
                    return@thread
                }
                Notifications.showPairingProgress(app, "Pairing…")
                AdbPairingClient("127.0.0.1", port, AdbKey.get(app)).pair(code)
                ActivationState.setPaired(app)
                Notifications.showPairingDone(app)
            } catch (e: WrongPairingCodeException) {
                Notifications.showPairingInput(app, "That code didn't match. Enter the code shown now.")
            } catch (e: Exception) {
                Notifications.showPairingInput(app, "Pairing failed: ${e.message ?: e}. Try again.")
            } finally {
                pending.finish()
            }
        }
    }
}

/** "Start" on the reconnect notification. */
class StartHelperReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "helper-start") {
            try {
                when (val result = HelperLauncher.start(app, ActivationState.turnOffAfterStart(app))) {
                    is HelperLauncher.Result.Started ->
                        Notifications.showStartResult(app, "Ready to mirror", "Open the app on your Mac.")
                    is HelperLauncher.Result.Failed ->
                        Notifications.showStartPrompt(app, result.reason)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** After a restart the helper is gone; remind the user to re-arm it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ActivationState.isPaired(context)) return
        Notifications.showStartPrompt(
            context,
            "Your phone restarted. Turn on Wireless debugging (Quick Settings tile), then tap Start.",
        )
    }
}

object ActivationState {
    private const val PREFS = "activation"

    fun isPaired(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("paired", false)

    fun setPaired(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("paired", true).apply()

    fun turnOffAfterStart(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("turn_off_after_start", true)

    fun setTurnOffAfterStart(context: Context, value: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("turn_off_after_start", value).apply()
}
