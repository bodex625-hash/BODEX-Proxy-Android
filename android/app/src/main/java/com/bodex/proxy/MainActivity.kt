package com.bodex.proxy

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32) }
        fun field(label: String, secret: Boolean = false): EditText {
            return EditText(this).apply {
                hint = label
                if (secret) inputType = 129
                root.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        root.addView(TextView(this).apply { text = "BODEX Proxy\nResidential SOCKS5"; textSize = 24f })
        host = field("SOCKS5 server address")
        port = field("Port")
        username = field("Username (optional)")
        password = field("Password (optional)", true)
        root.addView(Button(this).apply { text = "Connect VPN"; setOnClickListener { requestVpn() } })
        root.addView(Button(this).apply {
            text = "Disconnect"
            setOnClickListener {
                startService(Intent(this@MainActivity, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP))
                Toast.makeText(this@MainActivity, "Disconnect requested", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(root)
    }

    private fun requestVpn() {
        val address = host.text.toString().trim()
        val number = port.text.toString().toIntOrNull()
        if (address.isBlank() || number == null || number !in 1..65535) {
            Toast.makeText(this, "Enter a valid SOCKS5 address and port", Toast.LENGTH_LONG).show()
            return
        }
        val permission = VpnService.prepare(this)
        if (permission != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(permission, VPN_REQUEST)
        } else startTunnel()
    }

    @Deprecated("Legacy callback for Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) startTunnel()
    }

    private fun startTunnel() {
        val intent = Intent(this, ProxyVpnService::class.java).apply {
            putExtra(ProxyVpnService.EXTRA_HOST, host.text.toString().trim())
            putExtra(ProxyVpnService.EXTRA_PORT, port.text.toString().toInt())
            putExtra(ProxyVpnService.EXTRA_USERNAME, username.text.toString())
            putExtra(ProxyVpnService.EXTRA_PASSWORD, password.text.toString())
        }
        startService(intent)
        Toast.makeText(this, "VPN starting", Toast.LENGTH_SHORT).show()
    }

    companion object { private const val VPN_REQUEST = 701 }
}
