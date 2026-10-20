package com.androidmirroring.activation

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds this phone's own Wireless debugging ports. adbd advertises them over mDNS:
 * `_adb-tls-pairing._tcp` while the pairing-code dialog is open, `_adb-tls-connect._tcp`
 * while Wireless debugging is on.
 */
object AdbMdns {
    const val PAIRING = "_adb-tls-pairing._tcp"
    const val CONNECT = "_adb-tls-connect._tcp"

    /** Returns the port of the first service of [type] advertised by this device, or null on timeout. */
    fun findLocalPort(context: Context, type: String, timeoutMs: Long = 10_000): Int? =
        findLocalPorts(context, type, timeoutMs).firstOrNull()

    /**
     * Returns every port of [type] this device advertises, newest first. Once one is found,
     * waits [settleMs] more for others: after Wireless debugging restarts, Android can still
     * report the old, closed port next to the new one.
     */
    @Suppress("DEPRECATION")
    fun findLocalPorts(context: Context, type: String, timeoutMs: Long = 10_000, settleMs: Long = 1_500): List<Int> {
        val nsd = context.getSystemService(NsdManager::class.java)
        val localAddresses = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress }
            .toSet()
        val first = CountDownLatch(1)
        val ports = java.util.concurrent.CopyOnWriteArrayList<Int>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress
                        if ((host == null || host in localAddresses) && info.port > 0) {
                            ports.remove(info.port)
                            ports.add(0, info.port)
                            first.countDown()
                        }
                    }
                })
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = first.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            if (first.await(timeoutMs, TimeUnit.MILLISECONDS) && ports.isNotEmpty()) Thread.sleep(settleMs)
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                // Discovery failed to start, nothing to stop.
            }
        }
        return ports.toList()
    }
}
