package com.xboxaudiobridge

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private var source: AudioSource? = null
    private var sink: AudioSink? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Parado"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val tone = Button(this).apply {
            text = "Testar áudio (tom)"
            setOnClickListener { begin(ToneSource()) }
        }
        val xbox = Button(this).apply {
            text = "Conectar ao Xbox"
            setOnClickListener { begin(NanoAudioSource()) }
        }
        val stop = Button(this).apply {
            text = "Parar"
            setOnClickListener { end() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(TextView(context).apply {
                text = "Xbox Audio Bridge"
                textSize = 24f
                gravity = Gravity.CENTER
            })
            addView(status)
            addView(tone)
            addView(xbox)
            addView(stop)
        })
    }

    private fun begin(newSource: AudioSource) {
        end()
        val newSink = AudioSink().also { it.start() }
        sink = newSink
        source = newSource
        newSource.start(
            onPcm = { pcm, len -> newSink.write(pcm, len) },
            onStatus = { msg -> runOnUiThread { status.text = msg } }
        )
    }

    private fun end() {
        source?.stop(); source = null
        sink?.stop(); sink = null
        status.text = "Parado"
    }

    override fun onDestroy() {
        end()
        super.onDestroy()
    }
}
