package com.xboxaudiobridge

import android.content.Context
import android.media.AudioAttributes
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Sessão WebRTC com o Xbox: recebe o áudio (o vídeo é ignorado) e toca no celular. */
class StreamClient(
    private val ctx: Context,
    private val xhome: XHome,
    private val log: (String) -> Unit
) {
    @Volatile private var stopped = false
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var egl: EglBase? = null
    private var adm: JavaAudioDeviceModule? = null
    private var sessionId: String? = null
    private val localCandidates = CopyOnWriteArrayList<IceCandidate>()
    private val gatherDone = CountDownLatch(1)
    private val connected = CountDownLatch(1)
    private val channels = CopyOnWriteArrayList<DataChannel>()
    private var statsThread: Thread? = null

    fun start(serverId: String) {
        log("Abrindo sessão com o console...")
        val id = xhome.startSession(serverId)
        sessionId = id
        xhome.waitProvisioned(id, log)

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions()
        )
        val eglBase = EglBase.create()
        egl = eglBase
        val module = JavaAudioDeviceModule.builder(ctx.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .createAudioDeviceModule()
        adm = module
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(module)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        factory = f

        val cfg = PeerConnection.RTCConfiguration(emptyList<PeerConnection.IceServer>())
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        val p = f.createPeerConnection(cfg, observer) ?: throw Exception("Falha ao criar PeerConnection")
        pc = p

        val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
        p.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        channels.add(makeChannel("input", "1.0"))
        channels.add(makeChannel("control", "controlV1"))
        channels.add(makeChannel("message", "messageV1"))
        channels.add(makeChannel("chat", "chatV1"))

        val offer = createOffer(p)
        setLocal(p, offer)
        log("Oferta SDP criada, enviando...")
        xhome.sendOffer(id, offer.description)
        val answer = xhome.getAnswerSdp(id)
        setRemote(p, SessionDescription(SessionDescription.Type.ANSWER, answer))
        log("Resposta do Xbox recebida")

        gatherDone.await(6, TimeUnit.SECONDS)
        val arr = JSONArray()
        for (c in localCandidates) {
            arr.put(JSONObject().put("candidate", c.sdp).put("sdpMLineIndex", c.sdpMLineIndex).put("sdpMid", c.sdpMid))
        }
        log("Enviando ${arr.length()} candidatos ICE")
        xhome.sendIce(id, arr)
        val remote = xhome.getRemoteIce(id)
        for (i in 0 until remote.length()) {
            val o = remote.getJSONObject(i)
            var cand = o.getString("candidate")
            if (cand.startsWith("a=")) cand = cand.substring(2)
            p.addIceCandidate(IceCandidate(o.optString("sdpMid", "0"), o.optInt("sdpMLineIndex", 0), cand))
        }
        log("Recebidos ${remote.length()} candidatos ICE do Xbox")

        if (connected.await(30, TimeUnit.SECONDS)) log("Conectado ao Xbox") else log("Aviso: conexão não confirmou em 30s")
        startStats()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        statsThread?.interrupt()
        sessionId?.let { xhome.deleteSession(it) }
        try { channels.forEach { it.close() } } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
        try { pc?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { adm?.release() } catch (_: Exception) {}
        try { egl?.release() } catch (_: Exception) {}
        log("Sessão encerrada")
    }

    // ---------- SDP síncrono ----------
    private fun createOffer(p: PeerConnection): SessionDescription {
        var result: SessionDescription? = null
        var err: String? = null
        val latch = CountDownLatch(1)
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { result = d; latch.countDown() }
            override fun onCreateFailure(e: String?) { err = e; latch.countDown() }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }, MediaConstraints())
        latch.await(10, TimeUnit.SECONDS)
        return result ?: throw Exception("createOffer falhou: $err")
    }

    private fun setLocal(p: PeerConnection, d: SessionDescription) = setDesc(p, d, true)
    private fun setRemote(p: PeerConnection, d: SessionDescription) = setDesc(p, d, false)

    private fun setDesc(p: PeerConnection, d: SessionDescription, local: Boolean) {
        var err: String? = null
        var ok = false
        val latch = CountDownLatch(1)
        val obs = object : SdpObserver {
            override fun onCreateSuccess(x: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() { ok = true; latch.countDown() }
            override fun onSetFailure(e: String?) { err = e; latch.countDown() }
        }
        if (local) p.setLocalDescription(obs, d) else p.setRemoteDescription(obs, d)
        latch.await(10, TimeUnit.SECONDS)
        if (!ok) throw Exception("setDescription falhou: $err")
    }

    // ---------- canais de dados ----------
    private fun makeChannel(label: String, protocol: String): DataChannel {
        val init = DataChannel.Init()
        init.ordered = true
        init.protocol = protocol
        val dc = pc!!.createDataChannel(label, init)
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                log("canal $label: ${dc.state()}")
                if (dc.state() == DataChannel.State.OPEN) onChannelOpen(label, dc)
            }
            override fun onMessage(b: DataChannel.Buffer) {
                val bytes = ByteArray(b.data.remaining())
                b.data.get(bytes)
                if (b.binary) {
                    val hex = bytes.take(24).joinToString("") { "%02x".format(it) }
                    log("[$label] bin ${bytes.size}B: $hex")
                } else {
                    log("[$label] txt: ${String(bytes).take(200)}")
                }
            }
        })
        return dc
    }

    private fun sendText(dc: DataChannel, text: String) {
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false))
    }

    private fun onChannelOpen(label: String, dc: DataChannel) {
        when (label) {
            "control" -> sendText(
                dc,
                JSONObject().put("message", "authorizationRequest")
                    .put("accessKey", "4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E").toString()
            )
            "message" -> sendText(
                dc,
                JSONObject().put("type", "Handshake").put("version", "messageV1")
                    .put("id", UUID.randomUUID().toString()).put("cv", "").toString()
            )
        }
    }

    // ---------- observer ----------
    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            log("ICE: $s")
            if (s == PeerConnection.IceConnectionState.CONNECTED || s == PeerConnection.IceConnectionState.COMPLETED) connected.countDown()
        }
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gatherDone.countDown()
        }
        override fun onIceCandidate(c: IceCandidate) { localCandidates.add(c) }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onTrack(t: RtpTransceiver) {
            val track = t.receiver.track()
            log("Trilha recebida: ${track?.kind()}")
            if (track is VideoTrack) track.setEnabled(false) // não queremos imagem
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
            log("Conexão: $s")
        }
    }

    private fun startStats() {
        statsThread = Thread {
            try {
                while (!stopped) {
                    Thread.sleep(4000)
                    pc?.getStats(RTCStatsCollectorCallback { report ->
                        for (s in report.statsMap.values) {
                            if (s.type == "inbound-rtp") {
                                val kind = s.members["kind"] ?: s.members["mediaType"]
                                log("rtp $kind: pacotes=${s.members["packetsReceived"]} bytes=${s.members["bytesReceived"]}")
                            }
                        }
                    })
                }
            } catch (_: InterruptedException) {
            }
        }.also { it.isDaemon = true; it.start() }
    }
}
