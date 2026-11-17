package com.rem.screenguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * v0.19.11 (paradigm 6): the drawn icon system. One stroke weight
 * (2 units on a 24-unit grid), round caps and joins, one colour
 * per icon — every icon is a Canvas drawing, never an emoji or a
 * text glyph. Used by the menu rows, the app-group chips and the
 * section visuals; all colours come from the caller's palette, so
 * icons re-skin with the theme like everything else.
 */
enum class SgIcon {
    SHIELD, BOOK, CHART, PALETTE, LOCK, LIST, SLIDERS, INFO,
    EYE, EYE_OFF, CLOCK, CHECK
}

object SgIcons {

    fun view(ctx: Context, icon: SgIcon, color: Int, sizeDp: Int): View =
        IconView(ctx, icon, color, sizeDp)

    private class IconView(
        ctx: Context, val icon: SgIcon, val color: Int, sizeDp: Int
    ) : View(ctx) {

        private val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(px, px)

        override fun onDraw(canvas: Canvas) {
            val s = width / 24f
            stroke.strokeWidth = 2f * s
            canvas.save()
            canvas.scale(s, s)
            when (icon) {
                SgIcon.SHIELD -> {
                    canvas.drawPath(SgMotif.shieldPath(12f, 12f, 16.5f, 18.5f), stroke)
                    val check = Path().apply {
                        moveTo(8.6f, 12.1f); lineTo(11f, 14.7f); lineTo(15.6f, 9.3f)
                    }
                    canvas.drawPath(check, stroke)
                }
                SgIcon.BOOK -> {
                    val book = Path().apply {
                        moveTo(12f, 5.2f)
                        cubicTo(9.6f, 3.8f, 6.2f, 3.5f, 4f, 4.3f)
                        lineTo(4f, 18.7f)
                        cubicTo(6.2f, 17.9f, 9.6f, 18.1f, 12f, 19.6f)
                        cubicTo(14.4f, 18.1f, 17.8f, 17.9f, 20f, 18.7f)
                        lineTo(20f, 4.3f)
                        cubicTo(17.8f, 3.5f, 14.4f, 3.8f, 12f, 5.2f)
                    }
                    canvas.drawPath(book, stroke)
                    val spine = Path().apply { moveTo(12f, 5.2f); lineTo(12f, 19.6f) }
                    canvas.drawPath(spine, stroke)
                }
                SgIcon.CHART -> {
                    val base = Path().apply { moveTo(3.5f, 19.5f); lineTo(20.5f, 19.5f) }
                    canvas.drawPath(base, stroke)
                    val bars = Path().apply {
                        moveTo(6.8f, 19.5f); lineTo(6.8f, 13.5f)
                        moveTo(12f, 19.5f); lineTo(12f, 9.5f)
                        moveTo(17.2f, 19.5f); lineTo(17.2f, 5.8f)
                    }
                    val old = stroke.strokeWidth
                    stroke.strokeWidth = 2.6f
                    canvas.drawPath(bars, stroke)
                    stroke.strokeWidth = old
                }
                SgIcon.PALETTE -> {
                    canvas.drawCircle(12f, 12f, 8.2f, stroke)
                    canvas.drawCircle(9f, 9.4f, 1.25f, fill)
                    canvas.drawCircle(15.2f, 8.8f, 1.25f, fill)
                    canvas.drawCircle(9.4f, 15f, 1.25f, fill)
                }
                SgIcon.LOCK -> {
                    canvas.drawRoundRect(RectF(6.3f, 10.3f, 17.7f, 20f), 2f, 2f, stroke)
                    canvas.drawArc(RectF(8.4f, 4.2f, 15.6f, 11.6f), 180f, 180f, false, stroke)
                    canvas.drawCircle(12f, 15.1f, 1.3f, fill)
                }
                SgIcon.LIST -> {
                    val lines = Path().apply {
                        moveTo(9.5f, 7f); lineTo(19.5f, 7f)
                        moveTo(9.5f, 12f); lineTo(19.5f, 12f)
                        moveTo(9.5f, 17f); lineTo(19.5f, 17f)
                    }
                    canvas.drawPath(lines, stroke)
                    canvas.drawCircle(5.6f, 7f, 1.25f, fill)
                    canvas.drawCircle(5.6f, 12f, 1.25f, fill)
                    canvas.drawCircle(5.6f, 17f, 1.25f, fill)
                }
                SgIcon.SLIDERS -> {
                    val lines = Path().apply {
                        moveTo(3.5f, 7f); lineTo(20.5f, 7f)
                        moveTo(3.5f, 12f); lineTo(20.5f, 12f)
                        moveTo(3.5f, 17f); lineTo(20.5f, 17f)
                    }
                    canvas.drawPath(lines, stroke)
                    canvas.drawCircle(15f, 7f, 2.3f, fill)
                    canvas.drawCircle(8f, 12f, 2.3f, fill)
                    canvas.drawCircle(13f, 17f, 2.3f, fill)
                }
                SgIcon.INFO -> {
                    canvas.drawCircle(12f, 12f, 8.4f, stroke)
                    canvas.drawCircle(12f, 7.9f, 1.3f, fill)
                    val stem = Path().apply { moveTo(12f, 11.2f); lineTo(12f, 16.2f) }
                    canvas.drawPath(stem, stroke)
                }
                SgIcon.EYE -> {
                    canvas.drawPath(eyePath(), stroke)
                    canvas.drawCircle(12f, 12f, 2.4f, fill)
                }
                SgIcon.EYE_OFF -> {
                    canvas.drawPath(eyePath(), stroke)
                    canvas.drawCircle(12f, 12f, 2.7f, stroke)
                    val slash = Path().apply { moveTo(5f, 4.6f); lineTo(19f, 19.4f) }
                    canvas.drawPath(slash, stroke)
                }
                SgIcon.CLOCK -> {
                    canvas.drawCircle(12f, 12f, 8.4f, stroke)
                    val hands = Path().apply {
                        moveTo(12f, 12f); lineTo(12f, 8f)
                        moveTo(12f, 12f); lineTo(15.2f, 13.6f)
                    }
                    canvas.drawPath(hands, stroke)
                }
                SgIcon.CHECK -> {
                    val tick = Path().apply {
                        moveTo(5f, 12.6f); lineTo(10.2f, 17.6f); lineTo(19f, 6.8f)
                    }
                    canvas.drawPath(tick, stroke)
                }
            }
            canvas.restore()
        }

        private fun eyePath(): Path = Path().apply {
            moveTo(3.4f, 12f)
            cubicTo(6f, 6.7f, 18f, 6.7f, 20.6f, 12f)
            cubicTo(18f, 17.3f, 6f, 17.3f, 3.4f, 12f)
            close()
        }
    }
}
