package com.bodex.proxy

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = TextView(this).apply {
            text = "BODEX Proxy\n\nResidential SOCKS5 • GB packages\n\nStarter interface only — VPN tunnel and account services are not connected yet."
            textSize = 18f
            setPadding(32, 48, 32, 32)
        }
        setContentView(message)
    }
}
