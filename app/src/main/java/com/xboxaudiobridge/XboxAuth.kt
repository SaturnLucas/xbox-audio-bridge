package com.xboxaudiobridge

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

class NeedLoginException(msg: String) : Exception(msg)

data class GsInfo(val token: String, val baseUri: String)

/** Login Microsoft/Xbox (fluxo SISU) e obtenção do token de streaming (xHome). */
class XboxAuth(ctx: Context, private val log: (String) -> Unit) {
    companion object {
        const val APP_ID = "000000004c20a908"
        const val TITLE_ID = "328178078"
        const val REDIRECT = "ms-xal-000000004c20a908://auth"
        const val SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        private const val B64U = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    }

    class PendingLogin(val url: String, val state: String, val verifier: String)

    private val prefs = ctx.getSharedPreferences("xab_auth", Context.MODE_PRIVATE)
    private val keyPair: KeyPair = loadKey()
    private val rnd = SecureRandom()

    fun hasLogin(): Boolean = prefs.getString("refresh", null) != null

    fun logout() {
        prefs.edit().remove("refresh").remove("sisu").apply()
    }

    // ---------- chaves ----------
    private fun loadKey(): KeyPair {
        val priv = prefs.getString("key_priv", null)
        val pub = prefs.getString("key_pub", null)
        if (priv != null && pub != null) {
            try {
                val kf = KeyFactory.getInstance("EC")
                return KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                    kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP)))
                )
            } catch (_: Exception) {
            }
        }
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        val kp = g.generateKeyPair()
        prefs.edit()
            .putString("key_priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("key_pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun proofKey(): JSONObject {
        val w = (keyPair.public as ECPublicKey).w
        return JSONObject()
            .put("use", "sig").put("alg", "ES256").put("kty", "EC").put("crv", "P-256")
            .put("x", Base64.encodeToString(fixed32(w.affineX), B64U))
            .put("y", Base64.encodeToString(fixed32(w.affineY), B64U))
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var off = 2
        if ((der[1].toInt() and 0x80) != 0) off = 2 + (der[1].toInt() and 0x7f)
        val rLen = der[off + 1].toInt() and 0xff
        val r = der.copyOfRange(off + 2, off + 2 + rLen)
        off += 2 + rLen
        val sLen = der[off + 1].toInt() and 0xff
        val s = der.copyOfRange(off + 2, off + 2 + sLen)
        val out = ByteArray(64)
        fun put(src: ByteArray, dst: Int) {
            var start = 0
            while (start < src.size - 1 && src[start].toInt() == 0) start++
            val len = src.size - start
            System.arraycopy(src, start, out, dst + 32 - len, len)
        }
        put(r, 0)
        put(s, 32)
        return out
    }

    // ---------- POST assinado ----------
    private fun postSigned(url: String, body: String): Http.Resp {
        val ts = (System.currentTimeMillis() / 1000 + 11644473600L) * 10000000L
        val path = URL(url).path
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeInt(1); d.writeByte(0); d.writeLong(ts); d.writeByte(0)
        d.write("POST".toByteArray()); d.writeByte(0)
        d.write(path.toByteArray()); d.writeByte(0)
        d.writeByte(0) // authorization token vazio
        d.write(body.toByteArray(Charsets.UTF_8)); d.writeByte(0)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(bos.toByteArray())
        val raw = derToRaw(sig.sign())
        val hb = ByteArrayOutputStream()
        val hd = DataOutputStream(hb)
        hd.writeInt(1); hd.writeLong(ts); hd.write(raw)
        val headers = mapOf(
            "Content-Type" to "application/json",
            "x-xbl-contract-version" to "1",
            "Cache-Control" to "no-store, must-revalidate, no-cache",
            "Signature" to Base64.encodeToString(hb.toByteArray(), Base64.NO_WRAP)
        )
        val r = Http.request("POST", url, headers, body)
        if (r.code !in 200..299) throw Exception("HTTP ${r.code} em ${URL(url).host}: ${r.body.take(300)}")
        return r
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    // ---------- passos ----------
    private fun deviceToken(): String {
        val id = "{" + UUID.randomUUID().toString() + "}"
        val body = JSONObject()
            .put(
                "Properties", JSONObject()
                    .put("AuthMethod", "ProofOfPossession")
                    .put("Id", id)
                    .put("DeviceType", "Android")
                    .put("SerialNumber", id)
                    .put("Version", "15.0")
                    .put("ProofKey", proofKey())
            )
            .put("RelyingParty", "http://auth.xboxlive.com")
            .put("TokenType", "JWT")
        return JSONObject(postSigned("https://device.auth.xboxlive.com/device/authenticate", body.toString()).body)
            .getString("Token")
    }

    /** Passo 1: devolve a URL de login da Microsoft para abrir no WebView. */
    fun beginLogin(): PendingLogin {
        val dev = deviceToken()
        val verifier = Base64.encodeToString(randomBytes(32), B64U)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), B64U
        )
        val state = Base64.encodeToString(randomBytes(64), B64U)
        val body = JSONObject()
            .put("AppId", APP_ID)
            .put("TitleId", TITLE_ID)
            .put("RedirectUri", REDIRECT)
            .put("DeviceToken", dev)
            .put("Sandbox", "RETAIL")
            .put("TokenType", "code")
            .put("Offers", JSONArray().put(SCOPE))
            .put(
                "Query", JSONObject()
                    .put("display", "android_phone")
                    .put("code_challenge", challenge)
                    .put("code_challenge_method", "S256")
                    .put("state", state)
            )
        val r = postSigned("https://sisu.xboxlive.com/authenticate", body.toString())
        val url = JSONObject(r.body).getString("MsaOauthRedirect")
        return PendingLogin(url, state, verifier)
    }

    private fun form(vararg kv: Pair<String, String>): String =
        kv.joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }

    private fun tokenRequest(body: String): JSONObject {
        val r = Http.request(
            "POST", "https://login.live.com/oauth20_token.srf",
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Cache-Control" to "no-store, must-revalidate, no-cache"
            ), body
        )
        if (r.code !in 200..299) throw Exception("HTTP ${r.code} no login.live.com: ${r.body.take(200)}")
        return JSONObject(r.body)
    }

    /** Passo 2: recebe a URL ms-xal-...://auth?code=... e conclui o login. */
    fun completeLogin(redirect: String, p: PendingLogin) {
        val uri = Uri.parse(redirect)
        val code = uri.getQueryParameter("code")
            ?: throw Exception("Login sem código (${uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")})")
        if (uri.getQueryParameter("state") != p.state) throw Exception("State do login não confere")
        val t = tokenRequest(
            form(
                "client_id" to APP_ID, "code" to code, "code_verifier" to p.verifier,
                "grant_type" to "authorization_code", "redirect_uri" to REDIRECT, "scope" to SCOPE
            )
        )
        prefs.edit().putString("refresh", t.getString("refresh_token")).apply()
        authorizeSisu(t.getString("access_token"))
    }

    private fun refreshAccess(refresh: String): String {
        val t = tokenRequest(
            form(
                "client_id" to APP_ID, "grant_type" to "refresh_token",
                "refresh_token" to refresh, "scope" to SCOPE
            )
        )
        prefs.edit().putString("refresh", t.getString("refresh_token")).apply()
        return t.getString("access_token")
    }

    private fun authorizeSisu(access: String): JSONObject {
        val dev = deviceToken()
        val body = JSONObject()
            .put("AccessToken", "t=$access")
            .put("AppId", APP_ID)
            .put("DeviceToken", dev)
            .put("Sandbox", "RETAIL")
            .put("SiteName", "user.auth.xboxlive.com")
            .put("UseModernGamertag", true)
            .put("ProofKey", proofKey())
        val j = JSONObject(postSigned("https://sisu.xboxlive.com/authorize", body.toString()).body)
        // a resposta pode não repetir o DeviceToken: usa o que foi enviado
        if (!j.has("DeviceToken") || j.optString("DeviceToken").isEmpty()) j.put("DeviceToken", dev)
        log("Campos da resposta SISU: " + j.keys().asSequence().toList())
        prefs.edit().putString("sisu", j.toString()).apply()
        return j
    }

    private fun xsts(sisu: JSONObject, relyingParty: String): String {
        val body = JSONObject()
            .put(
                "Properties", JSONObject()
                    .put("SandboxId", "RETAIL")
                    .put("DeviceToken", sisu.getString("DeviceToken"))
                    .put("TitleToken", sisu.getJSONObject("TitleToken").getString("Token"))
                    .put("UserTokens", JSONArray().put(sisu.getJSONObject("UserToken").getString("Token")))
            )
            .put("RelyingParty", relyingParty)
            .put("TokenType", "JWT")
        return JSONObject(postSigned("https://xsts.auth.xboxlive.com/xsts/authorize", body.toString()).body)
            .getString("Token")
    }

    private fun xhomeLogin(xstsToken: String): GsInfo {
        val body = JSONObject().put("token", xstsToken).put("offeringId", "xhome").toString()
        val r = Http.request(
            "POST", "https://xhome.gssv-play-prod.xboxlive.com/v2/login/user",
            mapOf(
                "Content-Type" to "application/json",
                "Cache-Control" to "no-store, must-revalidate, no-cache",
                "x-gssv-client" to "XboxComBrowser"
            ), body
        )
        if (r.code !in 200..299) throw Exception("xHome login HTTP ${r.code}: ${r.body.take(300)}")
        val j = JSONObject(r.body)
        val regions = j.getJSONObject("offeringSettings").getJSONArray("regions")
        var base: String? = null
        for (i in 0 until regions.length()) {
            val reg = regions.getJSONObject(i)
            if (base == null || reg.optBoolean("isDefault", false)) base = reg.getString("baseUri")
        }
        return GsInfo(j.getString("gsToken"), base ?: throw Exception("Nenhuma região de streaming"))
    }

    /** Renova tudo a partir do refresh token salvo e devolve o token de streaming. */
    fun getGsInfo(): GsInfo {
        val refresh = prefs.getString("refresh", null) ?: throw NeedLoginException("Faça login primeiro")
        val access = try {
            refreshAccess(refresh)
        } catch (e: Exception) {
            throw NeedLoginException("Sessão expirou, entre de novo (${e.message})")
        }
        val sisu = authorizeSisu(access)
        val xsts = xsts(sisu, "http://gssv.xboxlive.com/")
        val gs = xhomeLogin(xsts)
        val tag = sisu.optJSONObject("AuthorizationToken")?.optJSONObject("DisplayClaims")
            ?.optJSONArray("xui")?.optJSONObject(0)?.optString("gtg", "") ?: ""
        if (tag.isNotEmpty()) log("Conta Xbox: $tag")
        return gs
    }
}
