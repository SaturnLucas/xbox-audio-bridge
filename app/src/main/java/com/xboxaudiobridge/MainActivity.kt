package com.xboxaudiobridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var auth: XboxAuth
    private lateinit var mainView: View
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private val logBuf = StringBuilder()
    private var webView: WebView? = null
    private var client: StreamClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = XboxAuth(applicationContext) { log(it) }
        mainView = buildMain()
        setContentView(mainView)
        log(if (auth.hasLogin()) "Login Xbox salvo. Toque em Conectar." else "Toque em Entrar com conta Xbox.")
    }

    private fun buildMain(): View {
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(TextView(context).apply { text = "Xbox Audio Bridge"; textSize = 22f })
            addView(btn("1. Entrar com conta Xbox") { startLogin() })
            addView(btn("2. Conectar e tocar áudio") { connect() })
            addView(btn("Parar") { stopStream() })
            addView(btn("Copiar log") { copyLog() })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun log(msg: String) {
        Log.d("XAB", msg)
        runOnUiThread {
            logBuf.append(msg).append('\n')
            if (logBuf.length > 30000) logBuf.delete(0, 8000)
            logView.text = logBuf.toString()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", logBuf.toString()))
        log("Log copiado.")
    }

    // ---------- login ----------
    private fun startLogin() {
        log("Preparando login...")
        Thread {
            try {
                val pending = auth.beginLogin()
                runOnUiThread { showLoginWebView(pending) }
            } catch (e: Throwable) {
                log("Erro ao preparar login: ${e.message}")
            }
        }.start()
    }

    private fun showLoginWebView(pending: XboxAuth.PendingLogin) {
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = wv.settings.userAgentString.replace("; wv", "")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("ms-xal-")) {
                    closeWebView()
                    finishLogin(url, pending)
                    return true
                }
                return false
            }
        }
        webView = wv
        setContentView(wv)
        wv.loadUrl(pending.url)
    }

    private fun closeWebView() {
        webView?.destroy()
        webView = null
        setContentView(mainView)
    }

    private fun finishLogin(url: String, pending: XboxAuth.PendingLogin) {
        log("Concluindo login...")
        Thread {
            try {
                auth.completeLogin(url, pending)
                log("Login OK. Agora toque em Conectar.")
            } catch (e: Throwable) {
                log("Erro no login: ${e.message}")
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView != null) closeWebView() else super.onBackPressed()
    }

    // ---------- streaming ----------
    private fun connect() {
        stopStream()
        Thread {
            try {
                log("Autenticando...")
                val gs = auth.getGsInfo()
                val xhome = XHome(gs)
                val consoles = xhome.listConsoles()
                if (consoles.isEmpty()) {
                    log("Nenhum console encontrado na conta. Ative 'Recursos remotos' no Xbox.")
                    return@Thread
                }
                for (c in consoles) log("Console: ${c.name} (${c.id}) estado=${c.power}")
                runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                val sc = StreamClient(applicationContext, xhome) { log(it) }
                client = sc
                sc.start(consoles.first().id)
            } catch (e: NeedLoginException) {
                log("${e.message}")
            } catch (e: Throwable) {
                log("Erro: ${e.message}")
            }
        }.start()
    }

    private fun stopStream() {
        val c = client ?: return
        client = null
        Thread { c.stop() }.start()
        runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    override fun onDestroy() {
        stopStream()
        super.onDestroy()
    }
}
