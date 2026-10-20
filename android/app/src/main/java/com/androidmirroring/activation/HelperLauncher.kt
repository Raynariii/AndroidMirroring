package com.androidmirroring.activation

import android.content.Context
import java.net.ConnectException
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
    // The brackets keep the pattern from matching the shell that runs this command.
    private const val STOP_COMMAND = "pkill -f 'com[.]androidmirroring[.]helper[.]HelperMai[n]'; sleep 1"
    private const val WIRELESS_DEBUGGING_OFF =
        "Can't reach Wireless debugging. Make sure it's on and you're on Wi-Fi. If it is, turn it off and on again, then tap Start helper."
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
        val ports = AdbMdns.findLocalPorts(context, AdbMdns.CONNECT)
        if (ports.isEmpty()) {
            // Without Wireless debugging a running helper can't be replaced, so keep it.
            if (isHelperRunning()) return Result.Started(alreadyRunning = true)
            return Result.Failed(WIRELESS_DEBUGGING_OFF)
        }

        return try {
            connectAny(ports, AdbKey.get(context)).use { adb ->
                val apk = context.applicationInfo.sourceDir
                // Replace any helper already running, which may be from an older version of the app.
                adb.shell(STOP_COMMAND)
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
        } catch (e: ConnectException) {
            if (isHelperRunning()) Result.Started(alreadyRunning = true) else Result.Failed(WIRELESS_DEBUGGING_OFF)
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.toString())
        }
    }

    /**
     * Tries each advertised port, newest first. A refused one is left over from an earlier
     * Wireless debugging session (it gets a new port every time it is turned on).
     */
    private fun connectAny(ports: List<Int>, key: AdbKey): AdbConnection {
        var refused: ConnectException? = null
        for (port in ports) {
            try {
                return AdbConnection.connect("127.0.0.1", port, key)
            } catch (e: ConnectException) {
                refused = e
            }
        }
        throw refused!!
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
