package com.androidmirroring

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.androidmirroring.activation.ActivationState
import com.androidmirroring.activation.HelperLauncher
import com.androidmirroring.activation.Notifications
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * M0 spike screen for self-activation: pair with this phone's Wireless debugging, then start the
 * mirroring helper without a computer. The real onboarding flow replaces this in M1.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply {
            this.text = value
            textSize = size
            setPadding(0, pad / 2, 0, pad / 2)
        }.also { column.addView(it) }
        fun button(label: String, onClick: (View) -> Unit) = Button(this).apply {
            text = label
            setOnClickListener(onClick)
        }.also { column.addView(it) }

        text("Android Mirroring (spike)", 22f)
        text("1. Enable Developer options (tap Build number 7 times in About phone), then turn on Wireless debugging.")
        button("Open Developer options") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        text("2. Pair once: tap below, open \"Pair device with pairing code\" in Wireless debugging, and type the code into the notification.")
        button("Show pairing notification") { Notifications.showPairingInput(this) }
        text("3. Start the mirroring helper. Do this again after every restart.")
        val turnOff = CheckBox(this).apply {
            text = "Turn Wireless debugging off after starting"
            isChecked = ActivationState.turnOffAfterStart(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> ActivationState.setTurnOffAfterStart(this@MainActivity, checked) }
        }
        column.addView(turnOff)
        startButton = button("Start helper") { startHelper() }
        status = text("", 15f)

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun startHelper() {
        startButton.isEnabled = false
        status.text = "Starting…"
        thread(name = "helper-start") {
            val result = HelperLauncher.start(applicationContext, ActivationState.turnOffAfterStart(this))
            runOnUiThread {
                startButton.isEnabled = true
                if (result is HelperLauncher.Result.Failed) {
                    val paired = if (ActivationState.isPaired(this)) "yes" else "no"
                    status.text = "Paired: $paired\n\n${result.reason}"
                } else {
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        thread(name = "status") {
            val running = HelperLauncher.isHelperRunning()
            val paired = ActivationState.isPaired(this)
            val ip = wifiAddress() ?: "not on Wi-Fi"
            val token = HelperLauncher.token(this)
            runOnUiThread {
                status.text = buildString {
                    appendLine("Paired: ${if (paired) "yes" else "no"}")
                    appendLine("Helper: ${if (running) "running" else "not running"}")
                    if (running) {
                        appendLine()
                        appendLine("On the Mac run:")
                        append("swift run -c release MirrorSpike --host $ip --port ${HelperLauncher.HELPER_PORT} --token $token")
                    }
                }
            }
        }
    }

    private fun wifiAddress(): String? {
        @Suppress("DEPRECATION")
        val wifi = getSystemService(WifiManager::class.java)?.connectionInfo?.ipAddress ?: 0
        if (wifi != 0) {
            return listOf(wifi and 0xff, wifi shr 8 and 0xff, wifi shr 16 and 0xff, wifi shr 24 and 0xff).joinToString(".")
        }
        return NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.name.startsWith("wlan") || it.name.startsWith("ap") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
