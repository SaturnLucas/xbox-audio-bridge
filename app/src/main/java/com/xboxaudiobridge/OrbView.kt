package com.xboxaudiobridge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/** Botão circular grande com animação de pulso que mostra o estado da conexão. */
class OrbView(ctx: Context) : View(ctx) {
    enum class S { IDLE, CONNECTING, CONNECTED, ERROR }

    var state: S = S.IDLE
        set(v) { field = v; invalidate() }

    private val green = 0xFF22E06B.toInt()
    private val amber = 0xFFFFB020.toInt()
    private val red = 0xFFFF5C5C.toInt()
    private val dark = 0xFF121A24.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private var phase = 0f
    private val density = resources.displayMetrics.density

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.30f
        val col = when (state) {
            S.IDLE -> green
            S.CONNECTING -> amber
            S.CONNECTED -> green
            S.ERROR -> red
        }
        if (state == S.CONNECTING || state == S.CONNECTED) {
            for (k in 0..1) {
                val p = (phase + k * 0.5f) % 1f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * density
                paint.color = alpha(col, ((1f - p) * 120).toInt())
                c.drawCircle(cx, cy, r + p * r * 0.75f, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = alpha(col, 34)
        c.drawCircle(cx, cy, r * 1.18f, paint)
        paint.color = if (state == S.CONNECTED) col else dark
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = col
        c.drawCircle(cx, cy, r, paint)

        val s = r * 0.42f
        when (state) {
            S.IDLE, S.ERROR -> { // triângulo de "tocar"
                paint.style = Paint.Style.FILL
                paint.color = col
                path.reset()
                path.moveTo(cx - s * 0.55f, cy - s)
                path.lineTo(cx + s * 0.95f, cy)
                path.lineTo(cx - s * 0.55f, cy + s)
                path.close()
                c.drawPath(path, paint)
            }
            S.CONNECTING -> { // arco girando
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 4f * density
                paint.color = col
                oval.set(cx - s, cy - s, cx + s, cy + s)
                c.drawArc(oval, phase * 360f, 250f, false, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }
            S.CONNECTED -> { // quadrado de "parar"
                paint.style = Paint.Style.FILL
                paint.color = 0xFF0B0F14.toInt()
                c.drawRoundRect(cx - s * 0.85f, cy - s * 0.85f, cx + s * 0.85f, cy + s * 0.85f, 8f * density, 8f * density, paint)
            }
        }
    }
}
