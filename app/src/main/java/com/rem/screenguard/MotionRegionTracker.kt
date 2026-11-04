package com.rem.screenguard

import android.os.SystemClock
import kotlin.math.abs

/**
 * v0.10.8: live motion-region tracker (Alejandro's crop idea, benched
 * offline in BENCH-CROP-2026-10-27.md before it was built).
 *
 * The field failure this attacks: explicit video playing INLINE in a
 * feed card is diluted ~3.5x by the surrounding chrome, so the scene
 * classifier reads the whole screenshot at ~0.03 while the same model
 * reads the video region alone at ~0.82. The playing video is also
 * the one part of a feed screen that MOVES every frame — so the
 * region can be found with grid arithmetic alone, no extra model:
 * diff a coarse luminance grid between consecutive captures of the
 * same package, keep the cells that change, and take the largest
 * connected blob. The offline prototype (18 rows x 32 cols, change
 * > 5 gray levels) recovered the true video card to within one grid
 * cell and correctly discarded the overlay's own changing digits via
 * the largest-component rule.
 *
 * Fully self-contained and side-effect free apart from its log
 * lines: the caller ([GuardService]) failure-isolates it, and any
 * doubt (scroll, package switch, weird grid) simply yields no
 * region — whole-frame behaviour, exactly as before this existed.
 *
 * v0.11.1 (Alejandro's field report on v0.11.0): eager activation
 * (no stability probation) + stillness retention + the 4% area
 * floor — see git history for the full note.
 *
 * v0.11.2 (Alejandro's field report on v0.11.1): the tracker is fed
 * by a dedicated SCOUT loop in GuardService (500 ms risky / 1 s
 * other) instead of only on analysis ticks, so a region can go
 * ACTIVE ~1 scout period after a watched app appears.
 *
 * v0.11.3 (Alejandro's screen recording of v0.11.2 on X): the red
 * box latched onto a thin band at the top of a playing Dune trailer
 * and never grew into the video; on obvious videos it sometimes
 * never adjusted at all. Root cause, confirmed with him: candidacy
 * came from a SINGLE interval's largest change-component, and
 * v0.11.1's retention then defended that first partial guess — a
 * video whose background is static never presents its whole rect in
 * any one interval, and each later piece was individually too small
 * to trigger the >= 1.5x replacement rule. Derivation is now
 * ACCUMULATED:
 *  - Every non-abstained interval stamps the cells it changed into
 *    a per-cell last-changed timestamp map.
 *  - The candidate is re-derived on EVERY feed from the
 *    RECENT-MOTION SET — cells changed within the last
 *    [RECENT_WINDOW_MS] (3 s) — largest connected component, +1 cell
 *    padding, same 4%-90% validity band. As different parts of a
 *    video move over successive intervals the union grows, so the
 *    box converges to the full media rect (target ~2 s) instead of
 *    waiting for one interval to contain the whole thing.
 *  - The derived box updates the active box CONTINUOUSLY while it
 *    stays in the same family (it overlaps the active box
 *    substantially, or contains / is contained by it — growth,
 *    shrink and jitter of one region). Growth is logged.
 *  - PAUSE SURVIVAL (the v0.11.1 requirement, kept): if the
 *    recent-motion set yields no candidate while the same package
 *    stays foreground, the last formed box is RETAINED and crop
 *    passes keep running on it.
 *  - MOVES are decided by accumulated evidence, never one
 *    interval: a derived box in a genuinely different location
 *    (IoU < [MOVE_IOU] against the active box, and covering less
 *    than [MOVE_IOU] of the smaller box — pure containment is
 *    growth, not a move) replaces the retained box only after
 *    [MOVE_CONFIRM_DERIVATIONS] consecutive derivations agree on
 *    the new location. A scroll/global-change interval still
 *    abstains (it stamps nothing and suspends derivation for that
 *    interval), and a package change still resets everything.
 *
 * v0.12.2 (ZoD build spec §5.3): two read-only additions for
 * GuardService's zoom-on-disagreement ladder — [lastIntervalSpan]
 * (the latest non-abstained interval's changed-cell span) and
 * [recentCentroidIn] (the recent-motion centroid inside a box).
 * Derivation, retention and move rules are unchanged; nothing in
 * the tracker reads the span back.
 */
class MotionRegionTracker(private val log: (String) -> Unit) {

    /** Region box in grid-cell coordinates; r/b exclusive. */
    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w: Int get() = r - l
        val h: Int get() = b - t
        val areaCells: Int get() = w * h
    }

    /**
     * v0.12.2 (ZoD build spec §5.3): one interval's motion span —
     * the bounding box, count and centroid (cell coordinates,
     * cell centres at +0.5) of the cells that interval changed,
     * restricted to cells whose centres lie inside the box the
     * interval's feed returned. Consumed by GuardService's
     * zoom-on-disagreement ladder (the MB rung + the AC centroid);
     * the tracker itself never reads it back.
     */
    data class Span(val box: Box, val cells: Int, val centroidCol: Float, val centroidRow: Float)

    companion object {
        const val COLS = 32
        const val ROWS = 18
        const val CELLS = COLS * ROWS
        /** A cell counts as changed when its mean luminance moves by more than this many gray levels (the bench's threshold). */
        const val CELL_CHANGE_GRAY = 5.0
        /** An interval where more than this fraction of cells changed is a scroll / global change: it abstains (stamps nothing, derivation suspended for the interval). */
        const val GLOBAL_CHANGE_FRAC = 0.60
        /** Candidate regions must cover at least this fraction of the frame… (v0.11.1: 0.08 → 0.04 — small inline videos). */
        const val MIN_AREA_FRAC = 0.04
        /** …and at most this fraction (beyond that the "region" is the screen). */
        const val MAX_AREA_FRAC = 0.90
        /** Two boxes are "the same region" at this IoU (jitter tracking only — activation is eager and needs no stability). */
        const val STABLE_IOU = 0.7
        /** v0.11.3: a cell belongs to the recent-motion set while its last change is within this window. */
        const val RECENT_WINDOW_MS = 3000L
        /** v0.11.3: a derived box counts as a different LOCATION below this IoU vs the active box (and below this overlap of the smaller box — containment is growth, not a move). */
        const val MOVE_IOU = 0.5
        /** v0.11.3: consecutive derivations that must agree on a new location before it replaces the retained box. */
        const val MOVE_CONFIRM_DERIVATIONS = 2
    }

    private var curPkg: String? = null
    private var prevGrid: IntArray? = null
    /** v0.12.2: the most recent non-abstained interval's [Span]; null when the last interval abstained (global change) or changed nothing inside the served box. */
    private var lastSpan: Span? = null
    /** v0.11.3: per-cell elapsedRealtime of its most recent change; 0 = never changed (this package session). */
    private val lastChangedMs = LongArray(CELLS)
    private var activeBox: Box? = null
    private var suspended = false
    /** True while the active box is being served from retention (no fresh candidate driving it); bounds the REGION retained log to once per episode. */
    private var retained = false
    /** True while the latest derivation produced a candidate; bounds the REGION candidate log to episode starts. */
    private var hadCandidate = false
    /** v0.11.3: a pending move — the new-location box derivations are agreeing on, and how many consecutive derivations have agreed so far. */
    private var pendingMove: Box? = null
    private var pendingMoveCount = 0

    private fun resetLocked() {
        prevGrid = null
        lastSpan = null
        lastChangedMs.fill(0L)
        activeBox = null
        suspended = false
        retained = false
        hadCandidate = false
        pendingMove = null
        pendingMoveCount = 0
    }

    private fun pct(v: Double): String = "%.1f".format(v)

    private fun boxStr(box: Box): String =
        "[${pct(box.l * 100.0 / COLS)},${pct(box.t * 100.0 / ROWS)},${pct(box.r * 100.0 / COLS)},${pct(box.b * 100.0 / ROWS)}]"

    private fun areaStr(box: Box): String = "%.0f%%".format(box.areaCells * 100.0 / CELLS)

    /** IoU of two cell boxes, 0..1. */
    private fun iou(a: Box, b: Box): Double {
        val il = maxOf(a.l, b.l); val it = maxOf(a.t, b.t)
        val ir = minOf(a.r, b.r); val ib = minOf(a.b, b.b)
        if (ir <= il || ib <= it) return 0.0
        val inter = (ir - il) * (ib - it)
        val union = a.areaCells + b.areaCells - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }

    /** Intersection as a fraction of the SMALLER box — 1.0 when one box contains the other. */
    private fun overlapOfSmaller(a: Box, b: Box): Double {
        val il = maxOf(a.l, b.l); val it = maxOf(a.t, b.t)
        val ir = minOf(a.r, b.r); val ib = minOf(a.b, b.b)
        if (ir <= il || ib <= it) return 0.0
        val inter = (ir - il) * (ib - it)
        val smaller = minOf(a.areaCells, b.areaCells)
        return if (smaller <= 0) 0.0 else inter.toDouble() / smaller
    }

    /** "Same region" test: high IoU, or centres within one cell and sizes within one cell. */
    private fun stableMatch(a: Box, b: Box): Boolean {
        if (iou(a, b) >= STABLE_IOU) return true
        val acx = (a.l + a.r) / 2.0; val bcx = (b.l + b.r) / 2.0
        val acy = (a.t + a.b) / 2.0; val bcy = (b.t + b.b) / 2.0
        return abs(acx - bcx) <= 1.0 && abs(acy - bcy) <= 1.0 &&
            abs(a.w - b.w) <= 1 && abs(a.h - b.h) <= 1
    }

    /**
     * v0.11.3 move test: [cand] is in a genuinely different location
     * than [cur] — low IoU AND low containment. Pure growth (the
     * candidate contains the active box) and pure shrink (contained
     * by it) keep overlapOfSmaller at 1.0, so they stay on the
     * continuous-update path and never need move confirmation.
     */
    private fun isDifferentLocation(cand: Box, cur: Box): Boolean =
        iou(cand, cur) < MOVE_IOU && overlapOfSmaller(cand, cur) < MOVE_IOU && !stableMatch(cand, cur)

    /** Largest 4-connected component of [cells]; returns its bounding box (unexpanded) or null. */
    private fun largestComponentBox(cells: BooleanArray): Box? {
        val seen = BooleanArray(CELLS)
        var best: Box? = null
        var bestCount = 0
        val stack = ArrayDeque<Int>()
        for (start in 0 until CELLS) {
            if (!cells[start] || seen[start]) continue
            var count = 0
            var l = COLS; var t = ROWS; var r = -1; var b = -1
            stack.clear()
            stack.addLast(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                count++
                val cx = cur % COLS; val cy = cur / COLS
                if (cx < l) l = cx; if (cx > r) r = cx
                if (cy < t) t = cy; if (cy > b) b = cy
                // 4-neighbours
                if (cx > 0 && cells[cur - 1] && !seen[cur - 1]) { seen[cur - 1] = true; stack.addLast(cur - 1) }
                if (cx < COLS - 1 && cells[cur + 1] && !seen[cur + 1]) { seen[cur + 1] = true; stack.addLast(cur + 1) }
                if (cy > 0 && cells[cur - COLS] && !seen[cur - COLS]) { seen[cur - COLS] = true; stack.addLast(cur - COLS) }
                if (cy < ROWS - 1 && cells[cur + COLS] && !seen[cur + COLS]) { seen[cur + COLS] = true; stack.addLast(cur + COLS) }
            }
            if (count > bestCount) {
                bestCount = count
                best = Box(l, t, r + 1, b + 1)
            }
        }
        return best
    }

    /**
     * Feed one captured frame's [COLS]x[ROWS] luminance grid for
     * [pkg]. Returns the ACTIVE region box (cell coordinates) to
     * judge this frame by, or null for whole-frame behaviour.
     *
     * v0.11.3: candidacy is ALWAYS the accumulated derivation — the
     * largest component of the cells changed within the last
     * [RECENT_WINDOW_MS] — for scout and analysis feeds alike.
     * [singleInterval] no longer selects a candidacy rule (the
     * v0.11.2 single-interval and persistence rules are both gone);
     * it only marks scout-sourced log lines. Every other rule — the
     * > 60% global-change abstain, the area validity band, eager
     * activation, stillness retention — applies identically.
     */
    @Synchronized
    fun onFrame(pkg: String, grid: IntArray, singleInterval: Boolean = false): Box? {
        if (grid.size != CELLS) {
            resetLocked()
            curPkg = pkg
            return null
        }
        if (pkg != curPkg) {
            if (activeBox != null) log("REGION dropped (package change)")
            resetLocked()
            curPkg = pkg
            prevGrid = grid
            return null
        }
        val prev = prevGrid
        if (prev == null) {
            prevGrid = grid
            return null
        }
        // Interval vs the previous capture of this package.
        val changed = BooleanArray(CELLS)
        var changedCount = 0
        for (i in 0 until CELLS) {
            if (abs(grid[i] - prev[i]) > CELL_CHANGE_GRAY) {
                changed[i] = true
                changedCount++
            }
        }
        prevGrid = grid
        // Scroll / global change: this interval abstains — it stamps
        // nothing into the accumulation, derivation is suspended for
        // the interval, and any half-proven move must be re-proven
        // afterwards. The active box is kept, not dropped (a scroll
        // ends); it is simply not served this cycle (as before).
        if (changedCount.toDouble() / CELLS > GLOBAL_CHANGE_FRAC) {
            if (!suspended && (activeBox != null || hadCandidate)) {
                log("REGION suspended (global change)")
            }
            suspended = true
            pendingMove = null
            pendingMoveCount = 0
            lastSpan = null // v0.12.2: an abstained interval has no span (spec §5.3)
            return null
        }
        suspended = false
        // Stamp this interval's changes into the accumulation.
        val now = SystemClock.elapsedRealtime()
        for (i in 0 until CELLS) if (changed[i]) lastChangedMs[i] = now
        // Re-derive the candidate from the recent-motion set: every
        // cell changed within the window, largest component, +1 cell.
        val recent = BooleanArray(CELLS) {
            lastChangedMs[it] != 0L && now - lastChangedMs[it] <= RECENT_WINDOW_MS
        }
        var candidate: Box? = null
        val raw = largestComponentBox(recent)
        if (raw != null) {
            val exp = Box(
                (raw.l - 1).coerceAtLeast(0), (raw.t - 1).coerceAtLeast(0),
                (raw.r + 1).coerceAtMost(COLS), (raw.b + 1).coerceAtMost(ROWS)
            )
            val frac = exp.areaCells.toDouble() / CELLS
            if (frac in MIN_AREA_FRAC..MAX_AREA_FRAC) candidate = exp
        }
        if (candidate != null) {
            if (!hadCandidate) {
                log("REGION candidate box=${boxStr(candidate)} area=${areaStr(candidate)} (accum${if (singleInterval) ", scout" else ""})")
            }
            hadCandidate = true
            retained = false
            val cur = activeBox
            when {
                cur == null -> {
                    // EAGER activation (v0.11.1): the first qualifying
                    // derivation goes ACTIVE on the feed it appears.
                    activeBox = candidate
                    pendingMove = null
                    pendingMoveCount = 0
                    log("REGION active box=${boxStr(candidate)} area=${areaStr(candidate)} (eager, accum${if (singleInterval) ", scout" else ""})")
                }
                isDifferentLocation(candidate, cur) -> {
                    // A genuinely different location: a MOVE. It is
                    // proven by accumulated evidence only — the same
                    // new location must be derived on
                    // MOVE_CONFIRM_DERIVATIONS consecutive
                    // derivations. Until then the retained box keeps
                    // being served. (Agreement = the new derivation
                    // is in the pending box's own family.)
                    val pm = pendingMove
                    if (pm != null && !isDifferentLocation(candidate, pm)) {
                        pendingMoveCount++
                        pendingMove = candidate
                    } else {
                        pendingMove = candidate
                        pendingMoveCount = 1
                    }
                    if (pendingMoveCount >= MOVE_CONFIRM_DERIVATIONS) {
                        activeBox = candidate
                        pendingMove = null
                        pendingMoveCount = 0
                        log("REGION replaced box=${boxStr(candidate)} area=${areaStr(candidate)} (accumulated move x$MOVE_CONFIRM_DERIVATIONS)")
                    }
                }
                else -> {
                    // Same family: the derived box updates the active
                    // box CONTINUOUSLY — growth as more of the video
                    // moves, shrink as motion narrows, jitter as it
                    // breathes. No replacement bar to clear.
                    pendingMove = null
                    pendingMoveCount = 0
                    if (candidate != cur) {
                        if (candidate.areaCells > cur.areaCells) {
                            log("REGION grow box=${boxStr(candidate)} area=${areaStr(candidate)} (accum)")
                        }
                        activeBox = candidate
                    }
                }
            }
        } else {
            hadCandidate = false
            // STILLNESS RETENTION (v0.11.1, kept): the recent-motion
            // set produced no candidate — the active region STAYS
            // active on its last formed box (paused video, still
            // card) and the crop pass keeps running on it while this
            // package stays foreground. A pending move is NOT reset
            // by a quiet derivation; it is re-proven or superseded
            // by the next candidate derivation.
            if (activeBox != null && !retained) {
                retained = true
                log("REGION retained box=${boxStr(activeBox!!)} area=${areaStr(activeBox!!)} (no fresh candidate)")
            }
        }
        // v0.12.2: record this interval's span against the box this
        // feed serves (the bench's MB geometry: changed cells whose
        // centres lie inside the active box — see computeSpan).
        lastSpan = computeSpan(changed, activeBox)
        return activeBox
    }

    /** v0.12.2: bbox / count / centroid of [changed] cells whose
     *  centres lie inside [box]; null when [box] is null or no
     *  changed cell lies inside it (the interval "changed nothing"
     *  the ZoD ladder could aim at). */
    private fun computeSpan(changed: BooleanArray, box: Box?): Span? {
        val b = box ?: return null
        var n = 0
        var l = COLS; var t = ROWS; var r = -1; var bb = -1
        var sc = 0.0; var sr = 0.0
        for (i in 0 until CELLS) {
            if (!changed[i]) continue
            val cx = i % COLS; val cy = i / COLS
            if (cx < b.l || cx >= b.r || cy < b.t || cy >= b.b) continue
            n++
            if (cx < l) l = cx; if (cx > r) r = cx
            if (cy < t) t = cy; if (cy > bb) bb = cy
            sc += cx + 0.5; sr += cy + 0.5
        }
        if (n == 0) return null
        return Span(Box(l, t, r + 1, bb + 1), n, (sc / n).toFloat(), (sr / n).toFloat())
    }

    /**
     * v0.12.2 (ZoD build spec §5.3): the [Span] of the most recent
     * non-abstained interval — bbox, count and centroid of the
     * changed cells from that interval (the tracker computes the
     * per-interval changed set before stamping [lastChangedMs]);
     * null when the last interval abstained (global change) or
     * changed nothing inside the served box.
     */
    @Synchronized
    fun lastIntervalSpan(): Span? = lastSpan

    /**
     * v0.12.2 (ZoD build spec §5.3): centroid (cell coordinates) of
     * the cells whose [lastChangedMs] is within [RECENT_WINDOW_MS]
     * and whose centres lie in [box] — the AC rungs' fallback aim
     * when [lastIntervalSpan] is null. Null under 3 cells.
     */
    @Synchronized
    fun recentCentroidIn(box: Box): Pair<Float, Float>? {
        val now = SystemClock.elapsedRealtime()
        var n = 0
        var sc = 0.0; var sr = 0.0
        for (i in 0 until CELLS) {
            val ts = lastChangedMs[i]
            if (ts == 0L || now - ts > RECENT_WINDOW_MS) continue
            val cx = i % COLS; val cy = i / COLS
            if (cx < box.l || cx >= box.r || cy < box.t || cy >= box.b) continue
            n++
            sc += cx + 0.5; sr += cy + 0.5
        }
        return if (n >= 3) Pair((sc / n).toFloat(), (sr / n).toFloat()) else null
    }
}
