package com.bodex.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File

class ProxyVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        val username = intent?.getStringExtra(EXTRA_USERNAME).orEmpty()
        val password = intent?.getStringExtra(EXTRA_PASSWORD).orEmpty()
        if (host.isBlank() || port !in 1..65535) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        Thread {
            try { startTunnel(host, port, username, password) }
            catch (_: Exception) { stopTunnel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }.start()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "BODEX Proxy", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("BODEX Proxy")
            .setContentText("SOCKS5 VPN connection is starting")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
    }

    @Synchronized
    private fun startTunnel(host: String, port: Int, username: String, password: String) {
        if (started) return
        val builder = Builder().setSession("BODEX Proxy").setMtu(1500)
            .addAddress("10.77.0.2", 32).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1")
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(true)
        val pfd = builder.establish() ?: error("VPN establish failed")
        tun = pfd
        val config = buildString {
            append("tunnel:\n  mtu: 1500\n  ipv4: 10.77.0.1\n")
            append("socks5:\n  address: \"").append(yaml(host)).append("\"\n")
            append("  port: ").append(port).append("\n  udp: 'udp'\n")
            if (username.isNotEmpty()) append("  username: \"").append(yaml(username)).append("\"\n")
            if (password.isNotEmpty()) append("  password: \"").append(yaml(password)).append("\"\n")
            append("misc:\n  log-level: warn\n")
        }
        val file = File(cacheDir, "hev-session.yml")
        file.writeText(config)
        check(TProxyService.TProxyStartService(file.absolutePath, pfd.fd)) { "Native tunnel failed to start" }
        started = true
    }

    private fun yaml(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "")

    @Synchronized
    private fun stopTunnel() {
        if (started) {
            try { TProxyService.TProxyStopService() } catch (_: Throwable) { }
            started = false
        }
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        File(cacheDir, "hev-session.yml").delete()
    }

    override fun onRevoke() { stopTunnel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); super.onRevoke() }
    override fun onDestroy() { stopTunnel(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "com.bodex.proxy.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        private const val CHANNEL_ID = "bodex_proxy_vpn"
        private const val NOTIFICATION_ID = 4101
    }
}
