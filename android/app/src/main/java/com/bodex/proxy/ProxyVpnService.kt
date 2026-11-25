package com.bodex.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.util.Locale
import hev.htproxy.TProxyService
import java.io.File

class ProxyVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var started = false
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsTicker = object : Runnable {
        override fun run() {
            if (!started) return
            try {
                val stats = TProxyService.TProxyGetStats()
                if (stats.size >= 4) {
                    val uploaded = stats[1].coerceAtLeast(0L)
                    val downloaded = stats[3].coerceAtLeast(0L)
                    val total = uploaded + downloaded
                    val statsIntent = Intent(ACTION_STATS).setPackage(packageName).apply {
                        putExtra(EXTRA_UPLOADED, uploaded)
                        putExtra(EXTRA_DOWNLOADED, downloaded)
                    }
                    sendBroadcast(statsIntent)
                    showStatus("Up " + formatBytes(uploaded) + " • Down " + formatBytes(downloaded) + " • Total " + formatBytes(total))
                }
            } catch (_: Throwable) {
                showStatus("VPN connected • traffic stats unavailable")
            }
            if (started) statsHandler.postDelayed(this, 5000)
        }
    }

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
            showStatus("Invalid SOCKS5 server or port")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Preparing VPN"))
        Thread {
            try {
                startTunnel(host, port, username, password)
                showStatus("VPN engine started; checking traffic")
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                stopTunnel()
                showStatus("Connection failed: $message")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun buildNotification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "BODEX Proxy", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("BODEX Proxy")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()
    }

    private fun showStatus(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    @Synchronized
    private fun startTunnel(host: String, port: Int, username: String, password: String) {
        if (started) return

        val builder = Builder()
            .setSession("BODEX Proxy")
            .setBlocking(false)
            .setMtu(8500)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("198.18.0.2")

        // Keep this app's own sockets outside its VPN. The native SOCKS5
        // engine must reach the upstream proxy through the physical network.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            error("Could not exclude BODEX Proxy from its own VPN")
        }

        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(true)
        val pfd = builder.establish() ?: error("Android could not establish the VPN interface")
        tun = pfd

        val logFile = File(cacheDir, "hev-tunnel.log")
        val config = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")
            appendLine("  ipv4: '198.18.0.1'")
            appendLine("  icmp: 'reply'")
            appendLine("socks5:")
            appendLine("  address: '${yamlSingle(host)}'")
            appendLine("  port: $port")
            appendLine("  udp: 'udp'")
            if (username.isNotEmpty()) appendLine("  username: '${yamlSingle(username)}'")
            if (password.isNotEmpty()) appendLine("  password: '${yamlSingle(password)}'")
            appendLine("mapdns:")
            appendLine("  address: 198.18.0.2")
            appendLine("  port: 53")
            appendLine("  network: 240.0.0.0")
            appendLine("  netmask: 240.0.0.0")
            appendLine("  cache-size: 10000")
            appendLine("misc:")
            appendLine("  log-file: '${yamlSingle(logFile.absolutePath)}'")
            appendLine("  log-level: debug")
            appendLine("  connect-timeout: 15000")
            appendLine("  task-stack-size: 86016")
        }
        val file = File(cacheDir, "hev-session.yml")
        file.writeText(config)

        check(TProxyService.TProxyStartService(file.absolutePath, pfd.fd)) {
            "Hev SOCKS5 engine refused to start. Check server, port and credentials."
        }
        started = true
        if (!TProxyService.TProxyIsRunning()) {
            error("Hev engine did not remain running. See hev-tunnel.log")
        }
        statsHandler.removeCallbacks(statsTicker)
        statsHandler.post(statsTicker)
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
        else String.format(Locale.US, "%.1f MB", mb)
    }

    private fun yamlSingle(value: String) = value.replace("'", "''").replace("\\r", "").replace("\\n", "")

    @Synchronized
    private fun stopTunnel() {
        statsHandler.removeCallbacks(statsTicker)
        if (started) {
            try { TProxyService.TProxyStopService() } catch (_: Throwable) { }
            started = false
        }
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        File(cacheDir, "hev-session.yml").delete()
    }

    override fun onRevoke() {
        stopTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.bodex.proxy.STOP"
        const val ACTION_STATS = "com.bodex.proxy.TRAFFIC_STATS"
        const val EXTRA_UPLOADED = "uploaded_bytes"
        const val EXTRA_DOWNLOADED = "downloaded_bytes"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        private const val CHANNEL_ID = "bodex_proxy_vpn"
        private const val NOTIFICATION_ID = 4101
    }
}
