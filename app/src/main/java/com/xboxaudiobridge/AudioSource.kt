package com.xboxaudiobridge

import kotlin.math.PI
import kotlin.math.sin

/** Qualquer origem de áudio: entrega PCM 16-bit estéreo 48 kHz via onPcm. */
interface AudioSource {
    fun start(onPcm: (ShortArray, Int) -> Unit, onStatus: (String) -> Unit)
    fun stop()
}

/** Tom de 440 Hz para validar a saída de áudio do celular, sem Xbox. */
class ToneSource : AudioSource {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(onPcm: (ShortArray, Int) -> Unit, onStatus: (String) -> Unit) {
        running = true
        onStatus("Tocando tom de teste (440 Hz)")
        thread = Thread {
            val frames = 480 // 10 ms a 48 kHz
            val buf = ShortArray(frames * 2)
            var phase = 0.0
            val step = 2 * PI * 440.0 / 48000.0
            while (running) {
                for (i in 0 until frames) {
                    val s = (sin(phase) * 0.2 * Short.MAX_VALUE).toInt().toShort()
                    buf[i * 2] = s
                    buf[i * 2 + 1] = s
                    phase += step
                }
                onPcm(buf, buf.size)
            }
        }.also { it.start() }
    }

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}

/**
 * TODO: conexão real com o Xbox Series S.
 * Passos a implementar (estudando o projeto open source XStreaming):
 *  1. Autenticação Microsoft/Xbox Live (tokens).
 *  2. Descobrir o console e abrir sessão de streaming (protocolo Nano).
 *  3. Negociar a sessão e receber só o canal de áudio (Opus); descartar o vídeo.
 *  4. Decodificar Opus para PCM e chamar onPcm.
 */
class NanoAudioSource : AudioSource {
    override fun start(onPcm: (ShortArray, Int) -> Unit, onStatus: (String) -> Unit) {
        onStatus("Conexão com o Xbox ainda não implementada (veja NanoAudioSource.kt)")
    }

    override fun stop() {}
}
