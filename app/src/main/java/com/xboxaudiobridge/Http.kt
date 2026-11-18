package com.xboxaudiobridge

import java.net.HttpURLConnection
import java.net.URL

object Http {
    class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>)

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 30000
    ): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val h = HashMap<String, List<String>>()
            for ((k, v) in c.headerFields) if (k != null) h[k.lowercase()] = v
            return Resp(code, text, h)
        } finally {
            c.disconnect()
        }
    }
}
