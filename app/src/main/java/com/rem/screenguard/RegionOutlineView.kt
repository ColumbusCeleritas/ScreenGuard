package com.rem.screenguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * v0.10.10: the motion-region outline (Alejandro's visual check on
 * the tracker) — a 1 px stroked rectangle, no fill, drawn at the
 * ACTIVE region's box while the debug overlay is on. The view is
 * sized and positioned by GuardService.syncRegionOutline to exactly
 * the region's display rect, so onDraw only strokes its own bounds
 * (inset 0.5 px so the 1 px stroke lies fully inside the view).
 *
 * v0.11.4: the stroke is PURPLE ([OUTLINE_COLOR_HEX]) instead of
 * red (Alejandro's order — red read as "alarm/error"; purple is
 * the tracker's own colour).
 *
 * v0.11.9: Alejandro refines the rule — the stroke is purple
 * normally and switches to RED ([OUTLINE_COLOR_SUS_HEX]) while SUS
 * mode is active for the outlined package, returning to purple
 * when suspicion ends. GuardService.syncRegionOutline drives the
 * switch via [setOutlineColor]; it applies to the active and the
 * retained box alike (same view, same paint).
 *
 * Deliberately NOT hidden around captures (unlike the debug
 * readout): Alejandro wants the box visible in the screenshots he
 * takes to verify the tracker, and a 1 px line is negligible to the
 * motion grid (the offline prototype already discarded far larger
 * overlay artifacts via its largest-component rule).
 */
class RegionOutlineView(ctx: Context) : View(ctx) {

    companion object {
        /** v0.11.4: vivid purple, Alejandro's pick for the crop outline. */
        const val OUTLINE_COLOR_HEX = "#C000FF"

        /** v0.11.9: pure red — the outline's colour while SUS mode is active. */
        const val OUTLINE_COLOR_SUS_HEX = "#FF0000"
    }

    private val outlinePaint = Paint().apply {
        color = Color.parseColor(OUTLINE_COLOR_HEX)
        style = Paint.Style.STROKE
        strokeWidth = 1f // exactly 1 physical pixel (Alejandro's spec)
        isAntiAlias = false
    }

    @Volatile private var currentColorHex = OUTLINE_COLOR_HEX

    /** v0.11.9: switch the stroke colour (purple ↔ SUS red). Called
     *  from GuardService's main-thread window updates; a same-colour
     *  call is a no-op. */
    fun setOutlineColor(hex: String) {
        if (hex == currentColorHex) return
        currentColorHex = hex
        outlinePaint.color = Color.parseColor(hex)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width > 1 && height > 1) {
            canvas.drawRect(0.5f, 0.5f, width - 0.5f, height - 0.5f, outlinePaint)
        }
    }
}
