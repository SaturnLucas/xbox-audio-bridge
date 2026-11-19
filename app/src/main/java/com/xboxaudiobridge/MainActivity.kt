package com.xboxaudiobridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

private val BG = 0xFF0B0F14.toInt()
private val CARD = 0xFF121A24.toInt()
private val STROKE = 0xFF1F2C3A.toInt()
private val TEXT = 0xFFE6EDF3.toInt()
private val MUTED = 0xFF8B98A5.toInt()
private val GREEN = 0xFF22E06B.toInt()
private val RED = 0xFFFF5C5C.toInt()
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private lateinit var auth: XboxAuth
    private lateinit var root: FrameLayout
    private lateinit var orb: OrbView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var accountName: TextView
    private lateinit var accountBtn: Button
    private lateinit var updateStatus: TextView
    private lateinit var updateBtn: Button
    private lateinit var updatePill: TextView
    private lateinit var logView: TextView
    private lateinit var logBox: LinearLayout
    private lateinit var logScroll: ScrollView
    private val logBuf = StringBuilder()
    private var webView: WebView? = null
    private var client: StreamClient? = null
    private var pendingUpdate: Updater.Info? = null
    private var splash: View? = null
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        volumeControlStream = AudioManager.STREAM_MUSIC
        auth = XboxAuth(applicationContext) { log(it) }

        root = FrameLayout(this).apply {
            setBackgroundColor(BG)
            fitsSystemWindows = true
            addView(buildMain(), FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val sp = buildSplash()
        splash = sp
        root.addView(sp, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        playSplash(sp)

        refreshAccount()
        setOrb(OrbView.S.IDLE, "Pronto para conectar", if (auth.hasLogin()) "Toque no botão para ouvir o Xbox" else "Entre com sua conta Xbox para começar")
        checkUpdate(false)
    }

    // ---------- visual ----------
    private fun roundRect(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        setColor(fill)
        if (stroke != 0) setStroke(dp(1), stroke)
        cornerRadius = radius
    }

    private fun tv(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(t: String, filled: Boolean) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 14f
        stateListAnimator = null
        setTextColor(if (filled) BG else TEXT)
        background = roundRect(if (filled) GREEN else 0x00000000, if (filled) 0 else STROKE, dp(14).toFloat())
        minHeight = dp(46)
        minimumHeight = dp(46)
        setPadding(dp(16), 0, dp(16), 0)
    }

    private fun card(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(CARD, STROKE, dp(20).toFloat())
        setPadding(dp(18), dp(16), dp(18), dp(16))
        for (c in children) addView(c)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) }
    }

    private fun cardTitle(t: String) = tv(t, 11f, MUTED, true).apply { letterSpacing = 0.1f }

    private fun logo(boxDp: Int): FrameLayout {
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CARD); setStroke(dp(2), GREEN) }
        return FrameLayout(this).apply {
            background = bg
            layoutParams = LinearLayout.LayoutParams(dp(boxDp), dp(boxDp))
            val big = (boxDp * 1.45f).toInt()
            addView(
                ImageView(context).apply { setImageResource(R.drawable.ic_launcher_foreground) },
                FrameLayout.LayoutParams(dp(big), dp(big), Gravity.CENTER)
            )
        }
    }

    private fun buildMain(): View {
        orb = OrbView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(240))
            setOnClickListener { onOrbClick() }
        }
        statusTitle = tv("", 20f, TEXT, true).apply { gravity = Gravity.CENTER }
        statusSub = tv("", 13f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }

        accountName = tv("", 18f, TEXT, true)
        accountBtn = button("", true).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
            setOnClickListener {
                if (auth.hasLogin()) { stopStream(); auth.logout(); refreshAccount(); setOrb(OrbView.S.IDLE, "Pronto para conectar", "Entre com sua conta Xbox para começar") }
                else startLogin()
            }
        }

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val seek = SeekBar(this).apply {
            max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            progressTintList = ColorStateList.valueOf(GREEN)
            thumbTintList = ColorStateList.valueOf(GREEN)
            setPadding(dp(4), dp(12), dp(4), dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        updateStatus = tv("", 13f, MUTED).apply { setPadding(0, dp(4), 0, 0) }
        updateBtn = button("Procurar atualização", false).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
            setOnClickListener { if (pendingUpdate != null) doUpdate() else checkUpdate(true) }
        }

        logView = tv("", 11f, MUTED).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }
        val copy = button("Copiar log", false).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
            setOnClickListener { copyLog() }
        }
        logBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(logScroll, LinearLayout.LayoutParams(MATCH, dp(220)).apply { topMargin = dp(10) })
            addView(copy)
        }
        val logHeader = tv("Detalhes técnicos  ▾", 13f, TEXT, true).apply {
            setOnClickListener { logBox.visibility = if (logBox.visibility == View.GONE) View.VISIBLE else View.GONE }
        }

        updatePill = tv("Atualização", 11f, BG, true).apply {
            background = roundRect(GREEN, 0, dp(12).toFloat())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
            setOnClickListener { doUpdate() }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo(46))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(tv("Xbox Audio Bridge", 19f, TEXT, true))
                    addView(tv("O som do seu Xbox no celular", 12f, MUTED))
                },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) }
            )
            addView(updatePill)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
            addView(header)
            addView(orb)
            addView(statusTitle)
            addView(statusSub)
            addView(card(cardTitle("CONTA XBOX"), accountName, accountBtn).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18) })
            addView(card(cardTitle("VOLUME"), seek))
            addView(
                card(
                    cardTitle("ATUALIZAÇÕES"),
                    tv("Versão ${Updater.currentName(this@MainActivity)}", 16f, TEXT, true).apply { setPadding(0, dp(6), 0, 0) },
                    updateStatus, updateBtn
                )
            )
            addView(card(logHeader, logBox))
            addView(tv("Feito por CyberSaturnX", 12f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(22), 0, 0) })
        }
        return ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false }
    }

    // ---------- introdução ----------
    private fun buildSplash(): View {
        val l = logo(132)
        val title = tv("Xbox Audio Bridge", 26f, TEXT, true).apply { gravity = Gravity.CENTER; alpha = 0f; setPadding(0, dp(24), 0, 0) }
        val by = tv("Feito por CyberSaturnX", 15f, GREEN, true).apply { gravity = Gravity.CENTER; alpha = 0f; letterSpacing = 0.08f; setPadding(0, dp(8), 0, 0) }
        l.alpha = 0f
        l.scaleX = 0.7f
        l.scaleY = 0.7f
        l.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).start()
        title.animate().alpha(1f).setStartDelay(400).setDuration(600).start()
        by.animate().alpha(1f).setStartDelay(900).setDuration(600).start()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            isClickable = true
            addView(l)
            addView(title)
            addView(by)
            setOnClickListener { dismissSplash() }
        }
    }

    private fun playSplash(v: View) {
        ui.postDelayed({ dismissSplash() }, 2800)
    }

    private fun dismissSplash() {
        val s = splash ?: return
        splash = null
        s.animate().alpha(0f).setDuration(450).withEndAction { root.removeView(s) }.start()
    }

    // ---------- estado ----------
    private fun setOrb(state: OrbView.S, title: String, sub: String) {
        runOnUiThread {
            orb.state = state
            statusTitle.text = title
            statusSub.text = sub
            if (state == OrbView.S.CONNECTING || state == OrbView.S.CONNECTED) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun refreshAccount() {
        runOnUiThread {
            if (auth.hasLogin()) {
                accountName.text = auth.gamertag() ?: "Conta conectada"
                accountBtn.text = "Sair da conta"
                accountBtn.setTextColor(TEXT)
                accountBtn.background = roundRect(0x00000000, STROKE, dp(14).toFloat())
            } else {
                accountName.text = "Nenhuma conta"
                accountBtn.text = "Entrar com conta Xbox"
                accountBtn.setTextColor(BG)
                accountBtn.background = roundRect(GREEN, 0, dp(14).toFloat())
            }
        }
    }

    private fun onOrbClick() {
        when (orb.state) {
            OrbView.S.CONNECTING, OrbView.S.CONNECTED -> {
                stopStream()
                setOrb(OrbView.S.IDLE, "Parado", "Toque no botão para conectar")
            }
            else -> if (auth.hasLogin()) connect() else startLogin()
        }
    }

    private fun log(msg: String) {
        Log.d("XAB", msg)
        runOnUiThread {
            logBuf.append(msg).append('\n')
            if (logBuf.length > 30000) logBuf.delete(0, 8000)
            logView.text = logBuf.toString()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", logBuf.toString()))
        log("Log copiado.")
    }

    // ---------- login ----------
    private fun startLogin() {
        setOrb(OrbView.S.CONNECTING, "Abrindo login…", "Conta Microsoft / Xbox")
        Thread {
            try {
                val pending = auth.beginLogin()
                runOnUiThread { showLoginWebView(pending) }
            } catch (e: Throwable) {
                log("Erro ao preparar login: ${e.message}")
                setOrb(OrbView.S.ERROR, "Falha no login", e.message ?: "")
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
        (root.parent as? ViewGroup)?.removeView(root)
        setContentView(root)
        if (orb.state == OrbView.S.CONNECTING && client == null) setOrb(OrbView.S.IDLE, "Pronto para conectar", "Toque no botão para ouvir o Xbox")
    }

    private fun finishLogin(url: String, pending: XboxAuth.PendingLogin) {
        setOrb(OrbView.S.CONNECTING, "Concluindo login…", "")
        Thread {
            try {
                auth.completeLogin(url, pending)
                log("Login OK")
                refreshAccount()
                setOrb(OrbView.S.IDLE, "Login concluído", "Agora toque no botão para conectar")
            } catch (e: Throwable) {
                log("Erro no login: ${e.message}")
                setOrb(OrbView.S.ERROR, "Falha no login", e.message ?: "")
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
        setOrb(OrbView.S.CONNECTING, "Conectando…", "Entrando na sua conta")
        Thread {
            var sc: StreamClient? = null
            try {
                val gs = auth.getGsInfo()
                refreshAccount()
                val xhome = XHome(gs)
                val consoles = xhome.listConsoles()
                if (consoles.isEmpty()) {
                    log("Nenhum console encontrado na conta.")
                    setOrb(OrbView.S.ERROR, "Nenhum Xbox encontrado", "Ative os recursos remotos no console")
                    return@Thread
                }
                for (c in consoles) log("Console: ${c.name} (${c.id}) estado=${c.power}")
                val target = consoles.first()
                setOrb(OrbView.S.CONNECTING, "Conectando ao ${target.name}…", "Abrindo sessão")
                val s = StreamClient(applicationContext, xhome) { log(it) }
                sc = s
                s.onLatency = { ms ->
                    runOnUiThread { if (client === s) statusSub.text = "Atraso do buffer: $ms ms" }
                }
                s.onState = { st ->
                    runOnUiThread {
                        if (client === s) when (st) {
                            "connected" -> setOrb(OrbView.S.CONNECTED, "Tocando áudio do ${target.name}", "Medindo atraso…")
                            "unstable" -> setOrb(OrbView.S.CONNECTING, "Conexão instável…", "Tentando recuperar")
                            "ended" -> { stopStream(); setOrb(OrbView.S.IDLE, "Sessão encerrada", "Toque no botão para reconectar") }
                        }
                    }
                }
                client = s
                s.start(target.id)
            } catch (e: NeedLoginException) {
                log("${e.message}")
                refreshAccount()
                setOrb(OrbView.S.ERROR, "Entre na sua conta", e.message ?: "")
            } catch (e: Throwable) {
                if (sc != null && client !== sc) return@Thread // foi cancelado pelo usuário
                log("Erro: ${e.message}")
                setOrb(OrbView.S.ERROR, "Falha ao conectar", e.message ?: "")
            }
        }.start()
    }

    private fun stopStream() {
        val c = client ?: return
        client = null
        Thread { c.stop() }.start()
    }

    // ---------- atualização ----------
    private fun checkUpdate(manual: Boolean) {
        if (manual) runOnUiThread { updateStatus.text = "Procurando…"; updateStatus.setTextColor(MUTED) }
        Thread {
            try {
                val info = Updater.fetchLatest()
                val cur = Updater.currentBuild(this)
                if (info != null && info.build > cur) {
                    pendingUpdate = info
                    runOnUiThread {
                        updateStatus.text = "Nova versão disponível (build ${info.build})"
                        updateStatus.setTextColor(GREEN)
                        updateBtn.text = "Atualizar agora"
                        updateBtn.setTextColor(BG)
                        updateBtn.background = roundRect(GREEN, 0, dp(14).toFloat())
                        updatePill.visibility = View.VISIBLE
                    }
                } else {
                    runOnUiThread {
                        updateStatus.text = "Você está na versão mais recente"
                        updateStatus.setTextColor(MUTED)
                    }
                }
            } catch (e: Throwable) {
                log("Atualização: ${e.message}")
                if (manual) runOnUiThread { updateStatus.text = "Não deu para verificar: ${e.message}"; updateStatus.setTextColor(RED) }
            }
        }.start()
    }

    private fun doUpdate() {
        val info = pendingUpdate ?: return
        runOnUiThread { updateBtn.isEnabled = false; updateStatus.setTextColor(MUTED); updateStatus.text = "Baixando… 0%" }
        Thread {
            try {
                val f = Updater.download(this, info.url) { p -> runOnUiThread { updateStatus.text = "Baixando… $p%" } }
                runOnUiThread {
                    updateBtn.isEnabled = true
                    updateStatus.text = if (Updater.install(this, f)) "Abrindo o instalador…"
                    else "Permita instalar apps desta fonte, volte e toque em Atualizar de novo"
                }
            } catch (e: Throwable) {
                log("Erro ao atualizar: ${e.message}")
                runOnUiThread { updateBtn.isEnabled = true; updateStatus.text = "Falha no download: ${e.message}"; updateStatus.setTextColor(RED) }
            }
        }.start()
    }

    override fun onDestroy() {
        stopStream()
        super.onDestroy()
    }
}
