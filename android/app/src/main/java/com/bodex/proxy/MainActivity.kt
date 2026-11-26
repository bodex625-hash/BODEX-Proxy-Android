package com.bodex.proxy

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var receiverRegistered = false
    private var pendingLogin = ""
    private var pendingPassword = ""

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ProxyVpnService.ACTION_STATS) return
            val up = intent.getLongExtra(ProxyVpnService.EXTRA_UPLOADED, 0L)
            val down = intent.getLongExtra(ProxyVpnService.EXTRA_DOWNLOADED, 0L)
            web.evaluateJavascript(
                "window.bodexVpnStats && window.bodexVpnStats($up,$down)", null
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (url.startsWith(WORKER_URL)) installVpnControls()
                }
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return !url.startsWith(WORKER_URL)
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(NativeBridge(), "BodexNative")
        }
        setContentView(web)
        if (savedInstanceState == null) web.loadUrl("$WORKER_URL/shop")
        else web.restoreState(savedInstanceState)
    }

    private fun installVpnControls() {
        val script = """
            (function(){
              if(window.__bodexNativeInstalled) return;
              window.__bodexNativeInstalled=true;
              var panel=document.createElement('section');
              panel.id='bodex-native-vpn';
              panel.className='panel';
              panel.innerHTML='<h2>اتصال VPN للجهاز</h2><div id="bodex-vpn-state" class="muted">سجّل الدخول واشترِ باقة أولاً، ثم افتح طلباتي.</div><div id="bodex-vpn-stats" class="muted"></div><button id="bodex-vpn-connect" type="button">اتصال بآخر بروكسي نشط</button><button id="bodex-vpn-disconnect" type="button">قطع الاتصال</button>';
              var main=document.querySelector('main');
              if(main) main.insertBefore(panel,main.firstChild);
              function say(t){var e=document.getElementById('bodex-vpn-state');if(e)e.textContent=t;}
              window.bodexVpnStats=function(up,down){
                var e=document.getElementById('bodex-vpn-stats');
                if(e)e.textContent='رفع: '+(up/1048576).toFixed(1)+' MB | تنزيل: '+(down/1048576).toFixed(1)+' MB';
              };
              document.getElementById('bodex-vpn-connect').onclick=async function(){
                try{
                  say('جارٍ جلب بيانات آخر طلب مكتمل...');
                  var r=await fetch('/api/orders',{credentials:'same-origin'});
                  var d=await r.json();
                  if(!r.ok)throw Error(d.error||'تعذر جلب الطلبات');
                  var o=(d.orders||[]).find(x=>x.proxy_login&&x.proxy_password&&x.status==='active');
                  if(!o)throw Error('لا يوجد بروكسي نشط جاهز. تأكد من اكتمال الدفع والتجهيز.');
                  window.BodexNative.connect(o.proxy_login,o.proxy_password);
                  say('تم إرسال طلب تشغيل اتصال SOCKS5...');
                }catch(e){say(e.message||'حدث خطأ');}
              };
              document.getElementById('bodex-vpn-disconnect').onclick=function(){
                window.BodexNative.disconnect();say('تم طلب قطع اتصال VPN');
              };
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun connect(login: String, password: String) {
            runOnUiThread {
                if (login.isBlank() || password.isBlank()) {
                    Toast.makeText(this@MainActivity, "بيانات البروكسي غير مكتملة", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                pendingLogin = login
                pendingPassword = password
                val permission = VpnService.prepare(this@MainActivity)
                if (permission != null) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(permission, VPN_REQUEST)
                } else startTunnel()
            }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread {
                startService(Intent(this@MainActivity, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP))
                Toast.makeText(this@MainActivity, "تم طلب قطع اتصال VPN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Legacy callback for Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) startTunnel()
            else Toast.makeText(this, "لم يتم منح إذن VPN", Toast.LENGTH_LONG).show()
        }
    }

    private fun startTunnel() {
        val intent = Intent(this, ProxyVpnService::class.java).apply {
            putExtra(ProxyVpnService.EXTRA_HOST, SOCKS_HOST)
            putExtra(ProxyVpnService.EXTRA_PORT, SOCKS_PORT)
            putExtra(ProxyVpnService.EXTRA_USERNAME, pendingLogin)
            putExtra(ProxyVpnService.EXTRA_PASSWORD, pendingPassword)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "جارٍ تشغيل اتصال SOCKS5", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            val filter = IntentFilter(ProxyVpnService.ACTION_STATS)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                registerReceiver(statsReceiver, filter)
            }
            receiverRegistered = true
        }
        if (::web.isInitialized) web.onResume()
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(statsReceiver)
            receiverRegistered = false
        }
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Legacy callback for Android compatibility")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.removeJavascriptInterface("BodexNative")
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val VPN_REQUEST = 701
        private const val WORKER_URL = "https://bodex-proxy-api.bodex625.workers.dev"
        private const val SOCKS_HOST = "103.152.136.28"
        private const val SOCKS_PORT = 10000
    }
}
