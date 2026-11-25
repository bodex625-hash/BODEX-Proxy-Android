package com.bodex.proxy

import android.net.VpnService

/**
 * Does not establish a VPN interface yet.
 * Integrate and test a real SOCKS5 tunnel engine, including UDP ASSOCIATE,
 * before routing device traffic.
 */
class ProxyVpnService : VpnService()
