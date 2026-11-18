package com.xboxaudiobridge

import org.json.JSONArray
import org.json.JSONObject

class XConsole(val name: String, val id: String, val power: String)

/** API REST de streaming local (xHome). */
class XHome(private val gs: GsInfo) {
    private val base = gs.baseUri.trimEnd('/')

    private val deviceInfo =
        """{"appInfo":{"env":{"clientAppId":"Microsoft.GamingApp","clientAppType":"native","clientAppVersion":"2203.1001.4.0","clientSdkVersion":"8.5.2","httpEnvironment":"prod","sdkInstallId":""}},"dev":{"hw":{"make":"Microsoft","model":"Surface Pro","sdktype":"native"},"os":{"name":"Windows 11","ver":"22631.2715","platform":"desktop"},"displayInfo":{"dimensions":{"widthInPixels":1920,"heightInPixels":1080},"pixelDensity":{"dpiX":1,"dpiY":1}}}}"""

    private fun headers() = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "Authorization" to "Bearer ${gs.token}",
        "X-MS-Device-Info" to deviceInfo
    )

    private fun check(r: Http.Resp, what: String): Http.Resp {
        if (r.code !in 200..299) throw Exception("$what: HTTP ${r.code} ${r.body.take(300)}")
        return r
    }

    fun listConsoles(): List<XConsole> {
        val r = check(Http.request("GET", "$base/v6/servers/home", headers()), "Listar consoles")
        val arr = JSONObject(r.body).optJSONArray("results") ?: JSONArray()
        val out = ArrayList<XConsole>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(XConsole(o.optString("deviceName", "Xbox"), o.getString("serverId"), o.optString("powerState", "?")))
        }
        return out
    }

    fun startSession(serverId: String): String {
        val body = JSONObject()
            .put("clientSessionId", "")
            .put("titleId", "")
            .put("systemUpdateGroup", "")
            .put(
                "settings", JSONObject()
                    .put("nanoVersion", "V3;WebrtcTransport.dll")
                    .put("enableTextToSpeech", false)
                    .put("highContrast", 0)
                    .put("locale", "en-US")
                    .put("useIceConnection", false)
                    .put("timezoneOffsetMinutes", 120)
                    .put("sdkType", "web")
                    .put("osName", "windows")
            )
            .put("serverId", serverId)
            .put("fallbackRegionNames", JSONArray())
        val r = check(Http.request("POST", "$base/v5/sessions/home/play", headers(), body.toString()), "Iniciar sessão")
        return JSONObject(r.body).getString("sessionId")
    }

    fun waitProvisioned(id: String, log: (String) -> Unit) {
        var last = ""
        for (i in 0 until 90) {
            val r = check(Http.request("GET", "$base/v5/sessions/home/$id/state", headers()), "Estado da sessão")
            val j = JSONObject(r.body)
            val st = j.optString("state")
            if (st != last) { log("Estado da sessão: $st"); last = st }
            if (st == "Provisioned" || st == "ReadyToConnect") return
            if (st == "Failed") throw Exception("Sessão falhou: ${j.optJSONObject("errorDetails")}")
            Thread.sleep(1000)
        }
        throw Exception("Tempo esgotado esperando o console")
    }

    private fun pollExchange(path: String, timeoutMs: Long, what: String): String {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = Http.request("GET", "$base$path", headers())
            if (r.code == 200 && r.body.isNotBlank()) {
                val j = JSONObject(r.body)
                val ex = j.optString("exchangeResponse", "")
                if (ex.isNotBlank()) return ex
                val err = j.optJSONObject("errorDetails")
                if (err != null && !err.isNull("code")) throw Exception("$what: $err")
            } else if (r.code != 200 && r.code != 204) {
                throw Exception("$what: HTTP ${r.code} ${r.body.take(200)}")
            }
            Thread.sleep(500)
        }
        throw Exception("$what: tempo esgotado")
    }

    fun sendOffer(id: String, sdp: String) {
        val cfg = JSONObject()
            .put(
                "chatConfiguration", JSONObject()
                    .put("bytesPerSample", 2).put("expectedClipDurationMs", 20)
                    .put("format", JSONObject().put("codec", "opus").put("container", "webm"))
                    .put("numChannels", 1).put("sampleFrequencyHz", 24000)
            )
            .put("chat", JSONObject().put("minVersion", 1).put("maxVersion", 1))
            .put("control", JSONObject().put("minVersion", 1).put("maxVersion", 3))
            .put("input", JSONObject().put("minVersion", 1).put("maxVersion", 8))
            .put("message", JSONObject().put("minVersion", 1).put("maxVersion", 1))
        val body = JSONObject().put("messageType", "offer").put("sdp", sdp).put("configuration", cfg)
        check(Http.request("POST", "$base/v5/sessions/home/$id/sdp", headers(), body.toString()), "Enviar SDP")
    }

    fun getAnswerSdp(id: String): String {
        val ex = pollExchange("/v5/sessions/home/$id/sdp", 30000, "Resposta SDP")
        return JSONObject(ex).getString("sdp")
    }

    fun sendIce(id: String, candidates: JSONArray) {
        val body = JSONObject().put("iceCandidates", candidates)
        check(Http.request("POST", "$base/v5/sessions/home/$id/ice", headers(), body.toString()), "Enviar ICE")
    }

    fun getRemoteIce(id: String): JSONArray {
        val ex = pollExchange("/v5/sessions/home/$id/ice", 20000, "ICE remoto")
        return JSONArray(ex)
    }

    fun deleteSession(id: String) {
        try { Http.request("DELETE", "$base/v5/sessions/home/$id", headers()) } catch (_: Exception) {}
    }
}
