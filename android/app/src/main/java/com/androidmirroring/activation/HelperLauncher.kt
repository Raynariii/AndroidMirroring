package com.androidmirroring.activation

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLException

/**
 * Starts the mirroring helper (com.androidmirroring.helper.HelperMain) as the shell user through
 * this phone's own Wireless debugging, with no computer involved. The helper keeps running after
 * Wireless debugging is turned off, until the phone restarts.
 */
object HelperLauncher {
    const val HELPER_PORT = 27183
    private const val LOG_PATH = "/data/local/tmp/androidmirroring-helper.log"
    private const val PREFS = "activation"
    private const val NOT_PAIRED =
        "Wireless debugging doesn't recognize this app yet. Pair first (step 2) and wait for the \"Paired\" notification, then tap Start helper again."

    sealed class Result {
        data class Started(val alreadyRunning: Boolean) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun token(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("token", token).apply()
        return token
    }

    fun isHelperRunning(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", HELPER_PORT), 300) }
        true
    } catch (e: Exception) {
        false
    }

    /** Blocking; call off the main thread. */
    fun start(context: Context, turnOffWirelessDebugging: Boolean): Result {
        if (isHelperRunning()) return Result.Started(alreadyRunning = true)

        val port = AdbMdns.findLocalPort(context, AdbMdns.CONNECT)
            ?: return Result.Failed("Wireless debugging is off. Turn it on, then try again.")

        return try {
            AdbConnection.connect("127.0.0.1", port, AdbKey.get(context)).use { adb ->
                val apk = context.applicationInfo.sourceDir
                adb.shell(launchCommand(apk, token(context)))
                // Give app_process a moment to bind its port before reporting.
                repeat(20) {
                    if (isHelperRunning()) {
                        if (turnOffWirelessDebugging) adb.shell("settings put global adb_wifi_enabled 0")
                        return Result.Started(alreadyRunning = false)
                    }
                    Thread.sleep(250)
                }
                Result.Failed("The helper did not start.\n\n" + adb.shell(diagnosticCommand(apk)))
            }
        } catch (e: SSLException) {
            // adbd answers an unknown client key with a certificate_unknown alert.
            if (e.message?.contains("CERTIFICATE_UNKNOWN") == true) {
                ActivationState.clearPaired(context)
                Result.Failed(NOT_PAIRED)
            } else {
                Result.Failed(e.message ?: e.toString())
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.toString())
        }
    }

    /**
     * Collects why the background launch produced nothing: the log, whether app_process is alive,
     * a short foreground run on a spare port (its errors print straight back), and recent crash or
     * SELinux lines from logcat.
     */
    fun diagnosticCommand(apkPath: String): String =
        "echo '--- log'; ls -l $LOG_PATH; tail -n 20 $LOG_PATH; " +
            "echo '--- processes'; ps -A -o PID,USER,NAME | grep -E 'app_process|androidmirroring'; " +
            "echo '--- foreground run'; CLASSPATH='$apkPath' timeout 4 app_process / " +
            "com.androidmirroring.helper.HelperMain token=probe port=${HELPER_PORT + 1} </dev/null 2>&1 | head -n 25; " +
            "echo '--- logcat'; logcat -d -t 400 2>&1 | grep -iE 'app_process|androidmirroring|AndroidRuntime|avc: denied' | tail -n 25"

    /**
     * setsid detaches the helper from adbd's session so it survives the shell stream closing and
     * Wireless debugging being turned off; nohup also makes it ignore the hangup. The shell then
     * stays alive for a moment, because adbd kills what is left of the shell's process group as
     * soon as the shell exits, which on some phones (seen on HyperOS) is before the background job
     * has detached.
     */
    fun launchCommand(apkPath: String, token: String): String =
        "if command -v setsid >/dev/null; then D='setsid nohup'; else D=nohup; fi; " +
            "CLASSPATH='$apkPath' \$D app_process / com.androidmirroring.helper.HelperMain " +
            "token=$token port=$HELPER_PORT </dev/null >$LOG_PATH 2>&1 & sleep 2"
}
