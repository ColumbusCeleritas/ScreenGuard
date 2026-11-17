package com.rem.screenguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View

/**
 * v0.19.11 (paradigm 10): the signature motif — concentric
 * shield-rings. ONE implementation feeds the Home hero, the
 * empty states and the block covers, so the whole product reads
 * as one designed thing. The shield outline itself is shared
 * with the icon system (SgIcons).
 */
object SgMotif {

    /** A shield outline centred at (cx, cy) with width w, height h. */
    fun shieldPath(cx: Float, cy: Float, w: Float, h: Float): Path {
        val l = cx - w / 2f
        val r = cx + w / 2f
        val t = cy - h / 2f
        val b = cy + h / 2f
        return Path().apply {
            moveTo(l, t + h * 0.10f)
            quadTo(cx, t - h * 0.03f, r, t + h * 0.10f)
            cubicTo(r, cy + h * 0.12f, cx + w * 0.30f, cy + h * 0.30f, cx, b)
            cubicTo(cx - w * 0.30f, cy + h * 0.30f, l, cy + h * 0.12f, l, t + h * 0.10f)
            close()
        }
    }
}

/**
 * Concentric shield-rings with a filled shield + check at the
 * centre. Colours are set by the caller from the active palette.
 * [pulsing] animates a slow radius/alpha breath — the loop runs
 * ONLY while the view is attached and window-visible, and costs
 * nothing anywhere else (the capture path never sees this view).
 */
class RingsView(ctx: Context) : View(ctx) {

    var ringColor: Int = Color.rgb(56, 189, 248)
    var centerFill: Int = Color.rgb(56, 189, 248)
    var centerMark: Int = Color.rgb(8, 24, 40)
    var pulsing: Boolean = false
        set(value) {
            field = value
            syncPulse()
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pulseRunning = false
    private val pulseTick = object : Runnable {
        override fun run() {
            if (!pulseRunning) return
            invalidate()
            handler.postDelayed(this, 70)
        }
    }

    private fun syncPulse() {
        val should = pulsing && isAttachedToWindow &&
            windowVisibility == VISIBLE && visibility == VISIBLE
        if (should && !pulseRunning) {
            pulseRunning = true
            handler.post(pulseTick)
        } else if (!should && pulseRunning) {
            pulseRunning = false
            handler.removeCallbacks(pulseTick)
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncPulse() }
    override fun onDetachedFromWindow() {
        pulseRunning = false
        handler.removeCallbacks(pulseTick)
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility); syncPulse()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility); syncPulse()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) * 0.46f
        val density = resources.displayMetrics.density
        ringPaint.strokeWidth = 2f * density
        // Pulse phase: a 2.4 s breath, radius ±2.5%, alpha ±20%.
        val phase = if (pulseRunning)
            (SystemClock.uptimeMillis() % 2400L) / 2400f * 2f * Math.PI.toFloat()
        else 0f
        val breathe = 1f + 0.025f * kotlin.math.sin(phase)
        val alphaPulse = 1f + 0.20f * kotlin.math.sin(phase)
        val scales = floatArrayOf(1.0f, 0.74f, 0.48f)
        val alphas = intArrayOf(72, 50, 32)
        for (i in scales.indices) {
            val s = scales[i] * breathe
            ringPaint.color = SgUi.withAlpha(ringColor,
                (alphas[i] * alphaPulse).toInt().coerceIn(0, 255))
            canvas.drawPath(
                SgMotif.shieldPath(cx, cy, base * 2f * s * 0.86f, base * 2f * s), ringPaint)
        }
        // Centre: the filled shield + check (the app icon's soul).
        fillPaint.color = centerFill
        canvas.drawPath(SgMotif.shieldPath(cx, cy, base * 0.62f, base * 0.72f), fillPaint)
        markPaint.color = centerMark
        markPaint.strokeWidth = 2.4f * density
        val check = Path().apply {
            moveTo(cx - base * 0.13f, cy + base * 0.01f)
            lineTo(cx - base * 0.04f, cy + base * 0.11f)
            lineTo(cx + base * 0.15f, cy - base * 0.12f)
        }
        canvas.drawPath(check, markPaint)
    }
}

/**
 * v0.19.11 (paradigm 10): the block covers' background — the
 * covers keep their fixed, non-themed ink palette and full
 * contrast (GuardService never themes them, by design); the
 * motif rides at low alpha behind the text. Solid fill first,
 * so the cover stays effectively opaque exactly as before.
 */
class CoverMotifDrawable : Drawable() {

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(242, 11, 18, 32)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(30, 56, 189, 248)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, fillPaint)
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val base = minOf(b.width(), b.height()) * 0.30f
        ringPaint.strokeWidth = 3f
        for (s in floatArrayOf(0.55f, 0.95f, 1.35f, 1.75f)) {
            canvas.drawPath(
                SgMotif.shieldPath(cx, cy, base * 2f * s * 0.86f, base * 2f * s), ringPaint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}
