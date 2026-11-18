package com.xboxaudiobridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Toca PCM 16-bit estéreo 48 kHz com o modo de menor latência do Android. */
class AudioSink(private val sampleRate: Int = 48000) {
    private var track: AudioTrack? = null

    fun start() {
        val mask = AudioFormat.CHANNEL_OUT_STEREO
        val enc = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, enc)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(enc)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(minBuf)
            .build()
            .also { it.play() }
    }

    fun write(pcm: ShortArray, length: Int) {
        track?.write(pcm, 0, length, AudioTrack.WRITE_BLOCKING)
    }

    fun stop() {
        track?.run { stop(); release() }
        track = null
    }
}
