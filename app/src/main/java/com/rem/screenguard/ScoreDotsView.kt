package com.rem.screenguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * v0.16.0 SCORE DOTS (Alejandro's order): the live conviction
 * counters as two rows of dots in the guard overlay.
 *
 *  - RED row: whole-frame STRIKES toward FULL_STRIKES_TO_CONVICT.
 *  - YELLOW row: crop-accumulator POINTS toward the convict bar.
 *
 * Filled dots are solid; unfilled dots are a faint ring of the
 * row colour. Pure display: nothing in the scoring path reads
 * this view, and the owning window hides around captures on the
 * same pattern as the debug readout and the GT chip, so the dots
 * never appear in a graded frame.
 */
class ScoreDotsView(ctx: Context) : View(ctx) {

    var strikes: Int = 0
    var strikesMax: Int = 4
    var points: Int = 0
    var pointsMax: Int = 8

    private val density: Float = resources.displayMetrics.density
    private val radius: Float = 5f * density // 10 dp dots
    private val pitch: Float = 14f * density // 10 dp dot + 4 dp gap
    private val rowGap: Float = 4f * density
    private val pad: Float = 6f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cols = maxOf(strikesMax, pointsMax)
        val w = (pad * 2 + radius * 2 + (cols - 1) * pitch).toInt()
        val h = (pad * 2 + radius * 4 + rowGap).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawRow(canvas, 0, strikes, strikesMax, RED)
        drawRow(canvas, 1, points, pointsMax, YELLOW)
    }

    private fun drawRow(canvas: Canvas, row: Int, count: Int, max: Int, color: Int) {
        val cy = pad + radius + row * (radius * 2 + rowGap)
        for (i in 0 until max) {
            val cx = pad + radius + i * pitch
            if (i < count) {
                fillPaint.color = color
                canvas.drawCircle(cx, cy, radius, fillPaint)
            } else {
                ringPaint.color = Color.argb(170, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2f, ringPaint)
            }
        }
    }

    companion object {
        private val RED: Int = Color.rgb(229, 57, 53)
        private val YELLOW: Int = Color.rgb(255, 193, 7)
    }
}
