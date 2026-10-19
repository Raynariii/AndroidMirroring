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
    @Suppress("DEPRECATION")
    fun findLocalPort(context: Context, type: String, timeoutMs: Long = 10_000): Int? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val localAddresses = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress }
            .toSet()
        val found = CountDownLatch(1)
        val port = AtomicInteger(-1)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress
                        if (host == null || host in localAddresses) {
                            port.compareAndSet(-1, info.port)
                            found.countDown()
                        }
                    }
                })
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = found.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            found.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                // Discovery failed to start, nothing to stop.
            }
        }
        return port.get().takeIf { it > 0 }
    }
}
