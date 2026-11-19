package com.xboxaudiobridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Atualização pelo próprio app, usando a release "latest" do GitHub. */
object Updater {
    private const val RELEASE_URL = "https://api.github.com/repos/SaturnLucas/xbox-audio-bridge/releases/tags/latest"

    class Info(val build: Int, val url: String)

    fun currentBuild(a: Activity): Long =
        a.packageManager.getPackageInfo(a.packageName, 0).longVersionCode

    fun currentName(a: Activity): String =
        a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "?"

    /** Devolve a versão mais recente publicada, ou null se não der para ler. */
    fun fetchLatest(): Info? {
        val r = Http.request("GET", RELEASE_URL, mapOf("Accept" to "application/vnd.github+json"))
        if (r.code !in 200..299) throw Exception("GitHub respondeu ${r.code}")
        val j = JSONObject(r.body)
        val build = Regex("build=(\\d+)").find(j.optString("body"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) return Info(build, a.getString("browser_download_url"))
        }
        return null
    }

    fun download(a: Activity, url: String, onProgress: (Int) -> Unit): File {
        val dir = File(a.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        if (out.exists()) out.delete()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            if (c.responseCode !in 200..299) throw Exception("Download falhou (${c.responseCode})")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    /** Abre o instalador do Android. Devolve false se faltar a permissão de instalar apps. */
    fun install(a: Activity, apk: File): Boolean {
        if (!a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
            )
            return false
        }
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.fileprovider", apk)
        a.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        return true
    }
}
