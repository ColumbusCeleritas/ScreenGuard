package com.rem.screenguard

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * ScreenGuard core.
 *
 * Loop: every N ms (faster when a "risky" app is in front) take a screenshot via
 * AccessibilityService.takeScreenshot() (Android 11+, no MediaProjection popup),
 * skip it if the frame barely changed, grade it in memory, and on confirmed
 * proof: eject to Home and quarantine the app under an escalating cooldown
 * cover. The screenshot itself is NEVER saved.
 *
 * v0.11.0 engine — ONE model (Alejandro: "just Falcon, full screen and the
 * cropping, and that's it"): Falconsai/nsfw_image_detection judges the whole
 * frame (Pass A) and, only while the motion-region tracker reports a stable
 * ACTIVE region, that region's crop as its own whole picture (Pass B). The
 * NudeNet detector, the old scene classifier, their tiles and every rule
 * built around them (covered veto, weak window, fragment partners) are
 * removed — BENCH-ALL-2026-10-27 showed Falconsai is the cleanest
 * whole-picture separator of everything preserved (benign worst 0.011).
 * Kept untouched: the secure/incognito luminance path, the launcher light
 * response, cooldowns/escalation, cadence bundles,
 * the settings lock and the motion-region tracker + crop outline.
 *
 * v0.11.2 scout (Alejandro's field report on v0.11.1 — the crop took
 * ~5 s to appear): region discovery is DECOUPLED from analysis. A
 * scout loop captures on its own fast timer (500 ms risky / 1 s
 * other) whenever the analysis path would be allowed to capture,
 * reduces each capture to the tracker's luminance grid and discards
 * the bitmap — no model runs, nothing is stored. At scout speed the
 * tracker accepts single-interval candidacy, so a region typically
 * goes ACTIVE within ~1 scout period of the app appearing, and the
 * next analysis tick (3 s / 15 s, unchanged) consumes the freshest
 * box instead of warming the tracker up itself. Battery honesty:
 * scouting adds grid-only screenshot captures at 2 Hz / 1 Hz while
 * a watched app is foreground.
 *
 * v0.11.3 accumulated region derivation (Alejandro's screen
 * recording of v0.11.2 on X — the red box latched onto a thin band
 * at the top of a playing trailer and never grew into the video):
 * the tracker no longer derives the region from a single interval.
 * Every interval stamps its changed cells into a per-cell
 * last-changed map, and the candidate is re-derived on every feed
 * from the cells changed within the last 3 s — so the box GROWS to
 * the full video rect as different parts of it move (~2 s), updates
 * continuously while it stays the same region, survives pauses via
 * the retained last box, and only relocates when two consecutive
 * derivations agree on a genuinely different location. Scout
 * timing/eligibility and everything else are unchanged from
 * v0.11.2; see MotionRegionTracker for the full note.
 *
 * v0.11.4 SUS MODE (Alejandro's order): any Falconsai verdict —
 * full pass OR crop pass — at or above 0.30 puts the foreground
 * package under suspicion for 15 s from that verdict (a fresh
 * 0.30+ re-triggers, extending the window). While SUS is live,
 * analysis runs at 1 s and the region scout at 200 ms: warm
 * content is sampled DENSELY instead of at the idle rate, so hot
 * frames between the relaxed ticks actually get seen. The points
 * bars do NOT move (he declined bar changes the same night) —
 * suspicion buys sampling density only, never conviction. The
 * debug overlay shows a literal "SUS MODE" line while suspicion
 * is live so the state can be debugged on screen, and the crop
 * outline is now PURPLE (#C000FF) instead of red.
 *
 * v0.11.6 SUS TRIPWIRE (Alejandro's design): the retired incumbent
 * scene classifier (v0.9.0-era asset, restored byte-identical)
 * returns as a THERMOMETER ONLY. Falconsai is polarised — ≈0 or
 * ≈1, almost nothing between — so its ≥0.30 SUS trip rarely fires
 * on borderline content; the classifier hands out graded
 * mid-range numbers and is cheap, so per analysed frame it runs
 * once on the full frame and once on the region crop (never
 * tiles), and a reading ≥0.50 (re)trips SUS exactly like a
 * Falconsai ≥0.30. It can NEVER judge: its scores are local to
 * processFrame, are never passed to handleSignal, and have no
 * path to a block, a park, or the accumulator. Guard-rail: when a
 * SUS window expires with NO Falconsai verdict ≥0.30 anywhere in
 * it, classifier-sourced trips for that package are muted for
 * 60 s (SUS refractory) so a warm-but-innocent video cannot hold
 * the phone in dense sampling; Falconsai trips are never muted.
 * The classifier is failure-isolated (CLS state line at service
 * start): if it cannot load or run, the guard is exactly
 * v0.11.5 — Falconsai-only, SUS keeping its Falconsai trigger.
 *
 * v0.11.8 CONTEXT-AWARE TRIPWIRE BAR (Alejandro's order, from the
 * feed logs): the classifier's SUS trip bar is 0.30 when the
 * foreground package is in the RISKY set and stays 0.50 for all
 * other packages (clsTripBar). Field motive: in the X feed the
 * classifier hovered at 0.31–0.45 — real warmth — without ever
 * tripping SUS at the flat 0.50 bar, so sampling never densified
 * on borderline feed content. Mechanics otherwise exactly
 * v0.11.6/v0.11.7: trips from either CLS pass, the 60 s
 * per-package refractory after a clean SUS window still applies
 * at both bars, Falconsai trips (≥0.30, any package) untouched
 * and never muted. The cls SUS-on log line gains the effective
 * bar (bar=0.30 / bar=0.50) so field logs show which bar fired.
 *
 * v0.11.10 POST-BLOCK SUS (Alejandro's order): after a block, SUS
 * mode stays on for 3 minutes in ALL apps. The v0.4.1 30 s
 * post-block watch is retired: executeBlock (quarantine path only
 * — the launcher-guard conversion returns before the arming site)
 * arms a 180 s global window, and susActive() folds that window
 * in for every package, so analysis (1 s), scout (200 ms), the
 * SUS MODE marker and the red outline all follow in any app. A
 * further block restarts the 180 s. The window is not a trip: it
 * carries no package, no warmth and no refractory state — the
 * refractory bookkeeping keys off tripSusActive() alone, and the
 * trip windows' 15 s mechanics are unchanged inside and outside
 * the global window. Log lines: "SUS on pkg=… src=post-block
 * (180s, all apps)" at arming, "SUS off src=post-block (180s
 * window expired)" at the passive expiry in tick().
 *
 * v0.11.12 PARANOID MODE (Alejandro's design, approved
 * 2026-10-28): a LAUNCHER GUARD conviction proves explicit
 * content on the one surface that can never be quarantined —
 * the Home/recents previews stayed watchable on a ~30 s duty
 * cycle (3 s cover, then watch again; the cover is even
 * hold-through-able). So a launcher-guard conviction opens a
 * 300 s PARANOID window (armed in executeBlock's launcher
 * branch — the only site, and the only event that can restart
 * it; trips and covers never extend it). While the window is
 * open AND the launcher is in front: the launcher samples at
 * SUS cadences (1 s analysis, 200 ms scout) and ANY verdict —
 * falF, falC, clsF or clsC — at/above 0.30 fires the
 * launcher-guard ACTION (Home + the fixed 3 s cover) at once,
 * once per fresh analysis, checked after handleSignal so a
 * strict conviction on the same frame takes the normal guard
 * path. A cover is an action, not a conviction: it touches no
 * conviction state, no GT interval, no SUS or refractory state.
 * Other apps follow completely normal rules during the window;
 * Settings stays absolutely exempt; the launcher is still
 * never quarantined. The overlay carries a PARANOID marker
 * while the window is open (refreshOverlay). Log lines:
 * "PARANOID on (300s) — launcher guard conviction",
 * "PARANOID cover pkg=… falF=… falC=… clsF=… clsC=… (bar 0.30)",
 * "PARANOID off (expired)".
 *
 * v0.11.13 CAPTURE PIPELINE (Alejandro's order, 2026-10-28 —
 * "move the checks to 1 sec max for everything"): the evening's
 * flatline investigation decoded takeScreenshot error code 3 as
 * INTERVAL_TIME_SHORT (AOSP: a 1000 ms minimum between
 * screenshots) and measured 216 failed vs 82 successful captures
 * in one log slice — the v0.11.2 scout (500 ms risky / 200 ms
 * SUS) and the analysis loop had been starving each other
 * through that floor since the scout shipped, including a 45 s
 * X session with zero analyses. Three structural fixes, no
 * verdict or threshold changes: (1) ONE serialised capture
 * channel — every takeScreenshot (analysis or scout) stamps
 * lastCaptureIssuedMs, nothing issues inside CAPTURE_FLOOR_MS
 * (1050 ms) of the previous capture, the analysis due within
 * the floor window owns the slot, the scout ticks at ~1 s in
 * every mode and only between analyses, and analysis bitmaps
 * feed the region tracker as they always have in processFrame;
 * (2) the error decoder now carries the TRUE positive AOSP
 * table (the old one mapped negative codes this API never
 * sends, so every failure logged "(unknown)"), and code 3 is
 * handled as a scheduling signal — retry at the next legal
 * slot, NO backoff — while other failures take the normal
 * interval plus a penalty capped at 2000 ms (the old open-ended
 * doubling, and the cadence learner keyed on the never-sent
 * -5, are gone); (3) capture health is measured, not assumed —
 * the ALIVE heartbeat carries cumulative attempts / successes /
 * failures by code (code 3 split out) and lastOkAge for the
 * foreground package, and the debug overlay shows "BLIND Ns"
 * once an analysable foreground package has gone 10 s without
 * a successful analysis (display only; no action is taken on
 * starvation in this release).
 *
 * v0.11.14 FAILURE VISIBILITY (Alejandro's order, 2026-10-28 —
 * "every failure should be absolutely marked"): the capture-floor
 * root cause sat in the logs for days because failures were
 * neither marked nor tallied — the CAP FAIL lines carried an
 * "(unknown)" code and nobody, human or agent, ever counted them.
 * This release changes NO behaviour; it makes failure impossible
 * to miss. (1) A unified FAIL channel: every failure line in the
 * debug log now begins with the literal prefix "FAIL " (see
 * failLog) — capture refusals and exceptions (analysis + scout),
 * watchdog force-resets, model load/run failures, loop crashes,
 * and every catch block in this file that used to swallow an
 * exception silently or log it without its class. A full audit of
 * the catch blocks (which ones swallowed, which logged without
 * identity) is in the release notes. A few per-tick cosmetic paths
 * (region outline, active-window query, region crop) log through
 * a 30 s-throttled variant so a persistent fault marks the log
 * repeatedly without flooding it; the counter below counts LINES
 * EMITTED, so it stays an honest tally of what the log shows.
 * (2) The ALIVE heartbeat gains fails=<n> — total FAIL lines
 * emitted since service start — so any export states its failure
 * count in its heartbeat. (3) Every FAIL event is also recorded
 * into ScoreHistory (timestamp + kind: capture / watchdog /
 * model / other) and the score graph draws each as a red vertical
 * tick with a FAIL legend chip carrying the window's count: a
 * failure storm paints a red band. Under the v0.11.12 drought's
 * behaviour, an export would have read fails=85+ with a solid red
 * stretch on the graph — visible at a glance, which is the point.
 *
 * v0.12.2 ZOOM-ON-DISAGREEMENT (ZoD) (Alejandro's design,
 * approved 2026-10-29; BENCH-FEED / BENCH-ZOD): a feed-inline
 * video is ~23% of the screen, and the accumulated region box
 * balloons past it, so the crop pass judges a UI sandwich and
 * Falconsai reads 0.00 while the classifier reads the true video
 * rect at ~1.00 — the 2026-10-29 incident took 69.8 s to block.
 * When the classifier and Falconsai DISAGREE on a served crop
 * (classifier at/above its governed trip bar, Falconsai crop
 * below 0.30, inside suspicion), Falconsai takes a second,
 * tighter look aimed by the motion itself: the ladder is the
 * latest interval's motion bounding box (MB), then two
 * aspect-capped centroid windows (AC-0.5, AC-0.4) — the plain
 * shrink ladder the design started from is geometrically dead
 * (Falconsai stretches crops to square; a shrunken tall box is
 * still a tall box, and the bench proved it reads ~0.00). A rung
 * verdict >= 0.90 LOCKS its box: the locked box replaces the
 * tracker box as the crop until it cools, relocates, times out,
 * changes package, or a block lands. Zoom verdicts reach
 * conviction ONLY through the existing crop accumulator, with
 * one guard — zoom-sourced points are capped at +1 per analysis
 * — so a fragment-class false read must sustain across >= 4
 * analyses to convict, the same evidence standard the
 * whole-frame path applies. Field replay of the incident under
 * this rule convicts at +12.5 s spending 1 extra pass. Shipped
 * LIVE at Alejandro's decision (the design's shadow-first
 * recommendation was declined with the meme-politics-04-class
 * residual risk accepted knowingly). See the ZoD section below.
 *
 * v0.14.0 WINDOW-STACK JUDGING (Alejandro's design, approved
 * 2026-10-30 after he found the hole himself: an explicit X video
 * playing in a picture-in-picture window over Android Settings —
 * Settings is absolutely exempt, the guard keyed every capture to
 * the foreground label, so it took ZERO captures and the PiP could
 * play forever). The screen is now judged as the SET of windows it
 * actually shows (window-stack-judging-design-2026-10-30):
 * getWindows() is sampled every tick; a window is JUDGEABLE when
 * it is an app window whose package is not exempt (Settings /
 * SystemUI / self / permission controllers — exemptions are now
 * properties of WINDOWS, not screens) and it is EXPOSED (no
 * higher-layer window fully contains it — a fullscreen app parked
 * behind Settings is covered, not judged). Capture proceeds iff
 * at least one judgeable window is visible; the screen's idle
 * price is the most suspicious judgeable window's tier (hotter
 * joins reprice instantly, departures need 2 samples — §3
 * hysteresis). Exactly ONE judgeable window → the entire legacy
 * engine runs unchanged with that window's package (single-app
 * behaviour is byte-identical to v0.13.5). TWO OR MORE → the
 * multi-window analysis: full-frame pass (skipped when judgeable
 * windows tile >= 90% of the display, D3), a Falconsai +
 * classifier pass-set on EVERY judgeable window's exact bounds
 * (hottest first, any size), the motion-region crop attributed
 * geometrically, ZoD composed per window — all inside a 2.4 s
 * per-analysis model budget with rotating deferral (PASS defer).
 * Verdicts attribute against the stack snapshot taken in the
 * capture tick. Whole-frame convictions on multi-window screens
 * are corroboration-gated (D1-B): a parked pending convicts only
 * when a crop-family verdict >= 0.60 localises to a window.
 * Small windows (< 2% of display or < 224 px on the small side,
 * D4) are checked at any size and their classifier still trips,
 * but their combined Falconsai points cap at +1 per analysis.
 * SUS is a per-package map now (D5); the quarantine cover keys on
 * ANY visible window of the quarantined package, so it covers a
 * PiP for its whole cooldown. The governor's latency arm compares
 * cost PER PASS-SET, so a multi-window afternoon cannot de-rate a
 * healthy phone. The tile sweep was considered and rejected (D2,
 * BENCH-GRID). The benchmark path is untouched: while benchActive
 * the stack model never engages.
 *
 * v0.14.1 COVERED-PRUNING FIX (field acceptance of v0.14.0,
 * 2026-10-30 14:57, Alejandro's log): the v0.14.0 exposure test —
 * any higher-layer window whose bounds fully contain a window
 * marks it covered — manufactured coverage that does not exist.
 * On his OnePlus, the focused fullscreen app reports a HIGHER
 * accessibility layer than the X floating window (the floating
 * window's layer reflects its task/window-area ordering, not its
 * composited z-order: freeform / PiP surfaces are drawn above
 * fullscreen apps regardless of that integer and of input focus),
 * and a fullscreen rect trivially contains every floating rect —
 * so the visibly-playing X window read (cov) for ~52 s at a time
 * whenever Settings (or the launcher, or Facebook) was the
 * fullscreen app beneath it: judged=[], zero captures, the PiP
 * hole reopened through the pruning rule — and, because the
 * quarantine cover keys on the JUDGED set, X's PiP also stayed
 * uncovered through its 20 s quarantine (block 14:58:10, (cov)
 * flip 14:58:18). The same window was judged at its rect in the
 * same session whenever the layer order ran the other way.
 * The fix classifies the OCCLUDER: a window is pruned only by a
 * system occluder (a TYPE_SYSTEM — the shade/status
 * class — or a fullscreen SystemUI app window) or by a
 * fullscreen app window over a fullscreen window. A floating
 * window (any app window below fullscreen size) is NEVER pruned
 * by an app window; IME / divider / unknown classes never
 * occlude; ambiguity resolves toward JUDGED, and a kept floating
 * window an app window contains is logged (WIN cov-override),
 * as is every prune with its occluder class (WIN cov).
 *
 * v0.14.3 SYSTEM-OCCLUDER NARROWING (field acceptance of
 * v0.14.1, 2026-10-30 15:50, Alejandro's log): the v0.14.1 fix
 * left one branch too broad — ANY TYPE_SYSTEM window at a
 * higher layer whose bounds contained a window pruned it
 * (by=sys). On the OnePlus, from 15:50:29.9 to the end of his
 * PiP-over-Settings test a persistent system window sat over
 * the screen and pruned the X PiP continuously (WIN cov
 * by=sys occ=syswin, judged=[], his GT intervals inside the
 * window all zero) — in the PiP's shrunk/mini state, while the
 * large freeform states judged correctly minutes earlier in
 * the same test. The occluder was unidentifiable because the
 * log carried it as a bare label. Working hypothesis: an
 * OxygenOS PiP-companion/frame system window whose bounds
 * contain the PiP — a companion, not an occluder.
 * The narrowing: a TYPE_SYSTEM window prunes only when it is
 * POSITIVELY the notification shade / an opaque fullscreen
 * system surface — BOTH SystemUI identity (its resolved root
 * package is SystemUI's, or its window title is the shade's)
 * AND coverage of at least STACK_FULLSCREEN_FRAC (0.95) of
 * the display. A system window failing either leg never
 * prunes: the companion/frame fails coverage by construction
 * (it hugs one app window, far from fullscreen), an
 * unidentified fullscreen system window fails identity —
 * ambiguity resolves toward JUDGED, as in v0.14.1. Every
 * system window CONSIDERED as an occluder (higher layer +
 * containment, either verdict) now leaves a WIN occ line
 * naming its type, title, root package, bounds, layer and
 * display coverage, so the next field log names the window
 * instead of calling it "syswin". System-window roots are
 * resolved in the same cached pass as app windows so the
 * identity leg has a package to test.
 *
 * v0.14.5 NO APP UNCHECKED (Alejandro's rulings, 2026-10-30
 * 16:20–16:22, amending his own v0.7.1 Settings absolute): (1)
 * "there should not be any application that doesn't get checked
 * — all of the applications should be checked at least at the
 * lowest cadence"; (2) "60 seconds should be the top limit of
 * our checks — nothing should go without a scan for more than
 * 60 seconds." Two mechanisms:
 *   SANITY CAPTURES — when the stack holds NO judgeable window
 *   (pure Settings, pure SystemUI, ScreenGuard's own UI, a
 *   permission controller), the screen is no longer a blind
 *   spot: it is captured and analysed at the SYSTEM tier's
 *   price (systemPriceMs), full pipeline — both models on the
 *   full frame, tracker feed, SCORE lines, PERF ring — so the
 *   guard is observably alive there (STACK judged=[] sanity=
 *   <pkg>; ALIVE judged=sanity:<pkg>). STRUCTURAL INNOCENCE,
 *   the classifier's pattern carried to packages: the sanity
 *   path (processFrameSanity) has no reference to the
 *   conviction machinery at all — its scores can trip SUS
 *   (noteSus / noteSusCls, so a hot exempt surface densifies
 *   to 1 s while it lasts) and feed GT peaks, but the
 *   whole-frame pending, the crop accumulator, the
 *   secure-streak block, ZoD and executeBlock are never fed
 *   for an exempt package; executeBlock additionally refuses
 *   exempt packages outright (a second wall). A block can
 *   therefore never name Settings / SystemUI / ScreenGuard /
 *   a permission controller. Full-frame passes only: no window
 *   crops exist on an exempt-only screen, and no ZoD burst runs
 *   (it would have no conviction consumer). While a
 *   shade-class occluder is up there are NO captures at all —
 *   the shade is an occlusion state, not an unchecked app, and
 *   the v0.14.0+ pause stands (StackSnapshot.shadeUp).
 *   UNIVERSAL 60 s CEILING — the SYSTEM preset row is now
 *   60/60/30 (Gentle's 120 s is abolished), the rung floor for
 *   the SYSTEM tier caps at 60 s in every rung (FLOOR
 *   included), and the system/sanity price is clamped to
 *   SANITY_CEILING_MS at pricing time, so a hand-set Custom
 *   slider cannot push it past 60 s either. With NORMAL's
 *   FLOOR at 40 s and SUSPICIOUS at 4 s, no idle interval
 *   anywhere in the ladder exceeds 60 s in any state; episodes
 *   are untouched (they are 1 s). The benchmark is inert to
 *   all of this — it disengages the stack, so the sanity path
 *   can never feed, price or gate a bench capture.
 *
 * Verbosity: every step of the loop is mirrored into DebugLog (capture
 * attempts + error codes, diff values, per-pass FAL lines) with counters in
 * Prefs, so a quiet guard can be diagnosed instead of guessed at.
 */
/** Tier ordering for "most suspicious": SUSPICIOUS < NORMAL < SYSTEM.
 *  File-level (v0.14.0): the nested StackSnapshot data class orders
 *  windows by it in primary(), and nested classes cannot reach the
 *  service's private members. */
private fun tierRank(tier: Int): Int = when (tier) {
    Prefs.TIER_SUSPICIOUS -> 0
    Prefs.TIER_NORMAL -> 1
    else -> 2
}

class GuardService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val inferExec = Executors.newSingleThreadExecutor()
    // v0.11.2: the scout's grid work runs on its own thread so a scout
    // capture never delays Falconsai inference (or vice versa).
    private val scoutExec = Executors.newSingleThreadExecutor()
    // v0.11.0: the only model — Falconsai, loaded at service start after
    // the persisted crash-loop latch is resolved (see resolveFalLatch).
    // falFirstRunDone marks this process's first FAL inference for the
    // stage markers and the persisted attempt flag (Prefs).
    @Volatile private var falModel: FalconModel? = null
    @Volatile private var falLatchedOff = false
    @Volatile private var falFirstRunDone = false
    private var falDropLogged = false
    // v0.11.6: the SUS tripwire classifier (see SceneClassifier) —
    // loaded at service start next to Falconsai, failure-isolated:
    // null means unavailable (load or run failure, reason in
    // clsFailReason + the CLS state line) and the guard then runs
    // Falconsai-only, exactly v0.11.5. Its scores are consumed ONLY
    // by the SUS trigger in processFrame; nothing else reads them.
    @Volatile private var clsModel: SceneClassifier? = null
    @Volatile private var clsFailReason: String? = null
    @Volatile private var clsFirstRunDone = false

    @Volatile private var foregroundPkg: String = ""
    // v0.17.0: the foreground window's activity class name, from
    // the same window-state events that move foregroundPkg (our
    // own windows excluded — overlay add/remove churn must not
    // redefine the foreground surface). The call-surface scoping
    // leg of the live session hold reads it.
    @Volatile private var foregroundClass: String = ""
    @Volatile private var running = false
    // v0.12.1: whether the status-bar shield notification is currently
    // posted. In-memory only; syncStatusNotification reconciles it
    // against the live permission state on every heartbeat.
    @Volatile private var statusNotifShown = false
    // v0.13.1: the "shield suppressed" line logs ONCE per service
    // start — the v0.13.0 code logged nothing in that state, so a
    // missing POST_NOTIFICATIONS grant was undiagnosable from the log
    // (Alejandro's field report, Oct 29). Reset in onServiceConnected.
    @Volatile private var statusSuppressionLogged = false
    @Volatile private var busy = false
    @Volatile private var busySinceMs = 0L
    private var lastThumb: IntArray? = null
    private var checks = 0
    private var blocks = 0
    private var lastSignalSecure = false
    // v0.11.5 WHOLE-FRAME PENDING (reformed — see the pending block in
    // handleInstantSignal): a conviction-grade whole-frame verdict
    // parks here as strike 1 of FULL_STRIKES_TO_CONVICT (v0.16.0).
    // NOTHING clean can drop it — the v0.6.0-era
    // clean-confirm-frame drop was an incumbent-classifier fossil:
    // that model really did spike on innocent
    // frames, but Falconsai's benign full-frame ceiling is 0.011
    // (BENCH-ALL), so a >= 0.90 full verdict is conviction-grade and
    // a clean frame of a DIFFERENT post after a scroll is not an
    // alibi. The pending convicts on a second fresh whole-frame
    // verdict >= FAL_STRONG_BAR and dies only by expiry —
    // PENDING_EXPIRY_MS after the last warmth (any fresh verdict,
    // full or crop, at/above SUS_BAR for this package) — or on
    // package change / any block.
    private var pendingBlockPkg: String? = null
    private var pendingHits = 0 // v0.16.0: strikes counted toward FULL_STRIKES_TO_CONVICT while parked (park = 1)
    private var pendingLastWarmthMs = 0L // last fresh verdict >= SUS_BAR for the pending package (park time initially)
    private var pendingPeak = 0f // strongest whole-frame verdict seen while parked (attribution)
    // v0.10.9: crop accumulator — points state for CROP verdicts only
    // (Alejandro: a crop is the content's own whole screen, so its
    // verdicts accumulate toward conviction like whole-screen proof).
    // v0.11.0: NO witness — the detector that supplied it is removed,
    // and Falconsai's benign record (worst 0.011 across ~150 benign
    // whole pictures, BENCH-ALL-2026-10-27) is why points alone may
    // convict: no benign whole picture ever came near the +1 band.
    // Per-package in practice: reset on package change (streak block in
    // handleInstantSignal) and on any block (executeBlock).
    // v0.14.0: the accumulator state itself moved to the
    // per-package map (accByPkg / AccState, declared with the SUS
    // map below) — resetCropAcc keeps its name and its semantics
    // (any block / analysed-package change empties the evidence);
    // it now empties the map.
    private fun resetCropAcc() {
        accByPkg.clear()
    }
    // ---- v0.12.2 ZoD state (design build spec §5.2) ----
    // The aim lock: a zoom rung's box, stored as bitmap fractions,
    // that replaces the tracker box as the crop while it stays hot.
    // All of it is per-package in effect (the lock names its package
    // and dies on package change); the pass budget is device-level
    // and survives package changes, as specced.
    private var zodLockBoxFrac: FloatArray? = null // locked box as bitmap fractions [l,t,r,b]
    private var zodLockPkg: String? = null // package the lock belongs to
    private var zodLockParent: MotionRegionTracker.Box? = null // tracker box at lock time (relocation test)
    private var zodLockRung: String? = null // "MB" | "AC05" | "AC04"
    private var zodLockExpiryMs = 0L // refreshed by locked verdicts >= ZOD_WARM
    private var zodLockCold = 0 // consecutive locked verdicts < ZOD_FALC_MAX
    private var zodCoolUntilMs = 0L // per zodCoolPkg (the single-slot cooldown, scoped by package)
    private var zodCoolPkg: String? = null
    private val zodPassTimes = ArrayDeque<Long>() // rolling budget (device-level, survives pkg change)
    private var zodTrackerErrorLogged = false // one-shot FAIL marker for the tracker span APIs, same pattern as regionErrorLogged
    // v0.7.1: any suspicious frame (not just a conviction-track signal)
    // puts its app on max cadence for a short burst.
    @Volatile private var attentionPkg: String? = null
    @Volatile private var attentionUntilMs = 0L
    // v0.7.1: packages of every installed HOME app, resolved dynamically —
    // the system launcher is never checked, whatever brand it is.
    @Volatile private var homePackages: Set<String> = emptySet()
    @Volatile private var homePackagesCheckedMs = 0L
    // v0.13.0 (tiers design §7-S1): raised 500 → 1000 ms. The
    // platform's screenshot floor is 1000 ms, so a 500 ms warning
    // cadence could never be honoured — requests inside the floor
    // drew code-3 refusals that masqueraded as slowness. Every
    // cadence this service schedules is now >= 1 s.
    private var warningIntervalMs = 1000L
    // v0.11.2 scout state (see the scout section below): capture
    // serialisation vs the analysis path, the platform-rate-limit
    // backoff, and the REGION first per-session timing.
    @Volatile private var scoutBusy = false
    @Volatile private var scoutBusySinceMs = 0L
    @Volatile private var scoutBackoffMult = 1
    private var scoutOkStreak = 0
    // v0.11.14: scoutFailLogMs (the 30 s throttle on scout failure
    // lines) is gone — scout failures log through the FAIL channel
    // on every occurrence now (see scoutNoteFailure).
    // v0.11.13 CAPTURE PIPELINE (Alejandro's order, 2026-10-28 —
    // "move the checks to 1 sec max for everything"): the platform
    // enforces a 1000 ms minimum between screenshots (AOSP
    // ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS) and
    // refuses early calls with error code 3, INTERVAL_TIME_SHORT.
    // The v0.11.2 scout (200-500 ms) and the analysis loop shared
    // that one channel with no floor between them and starved each
    // other: a field log slice measured 216 failures against 82
    // successes, every failure code 3, including a 45 s stretch in
    // X with ZERO analyses while the scout ate every legal slot.
    // Now ALL screenshot acquisition is serialised through
    // lastCaptureIssuedMs: no takeScreenshot is issued less than
    // CAPTURE_FLOOR_MS after the previous one, an analysis due
    // within the floor window owns the next slot outright, and an
    // analysis capture feeds the region tracker itself (the scout
    // exists only to keep the tracker fed between analyses).
    @Volatile private var lastCaptureIssuedMs = 0L
    // v0.11.13 capture-health counters (cumulative per service
    // session, both capture consumers): attempts, successes,
    // failures by error code, and the last successful ANALYSIS
    // delivery per package (a capture handed to processFrame).
    // Surfaced on the ALIVE heartbeat; the BLIND overlay label is
    // derived from lastAnalysisOk* + blindFg* (display only).
    @Volatile private var capAttAll = 0
    @Volatile private var capOkAll = 0
    @Volatile private var capFailByCode: Map<Int, Int> = emptyMap()
    // v0.11.14: total FAIL lines emitted since service start (see
    // failLog) — rides the ALIVE heartbeat as fails=<n>. Counts
    // EMITTED lines, so the throttled failLog variant below counts
    // only when it actually writes.
    @Volatile private var failTotal = 0
    private val failThrottleMs = HashMap<String, Long>()
    @Volatile private var lastAnalysisOkPkg: String? = null
    @Volatile private var lastAnalysisOkMs = 0L
    private var blindFgPkg: String? = null
    private var blindFgSinceMs = 0L
    @Volatile private var blindLabel: String? = null
    @Volatile private var regionSessionPkg: String? = null
    @Volatile private var regionSessionStartMs = 0L
    @Volatile private var regionSessionLogged = false
    // ---- v0.13.0: device capability — governor + PERF + benchmark ----
    // (device-capability design, Oct 29). The governor watches the
    // capture channel's recent past and de-rates the effective preset
    // (Prefs.effRung) one rung at a time when this phone demonstrably
    // cannot sustain the chosen cadence. Observability first: the
    // same window feeds the ALIVE line's PERF fields, so a slow phone
    // is diagnosable from a single heartbeat.
    // One recorded capture outcome: wall time, success, and the
    // failure code (0 on success). Code 6 (SECURE_WINDOW) attempts
    // count toward the window size but never toward failures — a
    // secure window on screen is content, not device weakness.
    private data class GovSample(val tsMs: Long, val ok: Boolean, val code: Int)
    private val govWindow = ArrayDeque<GovSample>()
    // Rolling per-analysis model costs (last 20 completed analyses):
    // [falMsTotal, clsMsTotal] — backs the PERF heartbeat fields and
    // the governor's latency arm.
    private val perfSamples = ArrayDeque<LongArray>()
    @Volatile private var govReady = false // warm-up: first successful analysis + one heartbeat seen
    @Volatile private var heartbeatSeen = false
    @Volatile private var govLastDerateMs = 0L
    @Volatile private var govPrevOverRatio = false
    @Volatile private var govWatchLogged = false
    @Volatile private var govFloorStrikes = 0
    @Volatile private var govResting = false
    // v0.14.4: the blindness arm's sustain streak — consecutive
    // evaluations on which the v0.14.2 blind condition held, the
    // wall-clock start of the current streak and the judged context
    // (foreground package) it belongs to. Any evaluation where the
    // condition does not hold, and any context switch, resets it to
    // zero: the evidence must be continuous, never accumulated.
    @Volatile private var govBlindStreak = 0
    @Volatile private var govBlindStreakStartMs = 0L
    @Volatile private var govBlindStreakCtx: String? = null
    // v0.14.4: the last evaluated blind age / bar, kept only so the
    // ALIVE heartbeat can show a building streak before it fires.
    @Volatile private var govBlindAgeMs = -1L
    @Volatile private var govBlindBarMs = 0L
    // Due-slot accounting (v0.13.0: built for the benchmark's
    // skip_busy bar; v0.13.1: that bar is retired — the counters now
    // feed the BENCH line's informational skipBusy % only): a due
    // slot is counted once, at the first poll that finds it due.
    @Volatile private var dueSlotsAll = 0
    @Volatile private var dueBusyAll = 0
    @Volatile private var lastCountedDueMs = 0L
    @Volatile private var watchdogTripsAll = 0
    // Benchmark machine (BenchmarkActivity renders; the service
    // drives): phases climb Gentle → Balanced → Strict → episode
    // probe, first failure stops the climb and the cap is written.
    @Volatile private var benchActive = false
    @Volatile private var benchPhaseIdx = 0
    @Volatile private var benchPhaseEndMs = 0L
    @Volatile private var benchPhaseStartMs = 0L // v0.13.2: starvation guard reference
    @Volatile private var benchIntervalMs = 0L
    // v0.13.3: the benchmark's OWN schedule. While benchActive the
    // tick prices our surface from nextBenchDueMs, never from the
    // normal ladder: tick() computes its `next` BEFORE benchTick()
    // starts/advances a run later in the same tick, so phase 1's
    // first capture was issued at the PRE-benchmark price —
    // tierIntervalMs(own pkg) = 40 s under a FLOOR cap — and
    // doCapture's callbacks baked that into nextCaptureDueMs; every
    // later tick merely waited on it (field 2026-10-30 09:34/09:35:
    // dueIn 28912 / 35984 inside a 4 s phase, starvation abort at
    // att=1). Phase transitions had the same defect in miniature
    // (each new phase's first slot priced at the PREVIOUS phase's
    // interval). Set at phase begin (first capture immediate),
    // advanced one phase interval at every issued bench capture,
    // re-anchored when a bench slot completes late — a long
    // analysis drops missed slots instead of compressing them.
    @Volatile private var nextBenchDueMs = 0L
    // v0.13.1: phase capture counters count ONLY the phase's own
    // captures — doCapture calls issued against the benchmark stage
    // (pkg == our own) while a phase runs. The v0.13.0 snapshot-diff
    // of the global capAttAll/capOkAll swept up every consumer of the
    // shared channel; these counters measure the phase itself. Reset
    // at every phase begin.
    @Volatile private var benchPhaseAtt = 0
    @Volatile private var benchPhaseOk = 0
    @Volatile private var benchWatchdog0 = 0
    @Volatile private var benchDue0 = 0
    @Volatile private var benchDueBusy0 = 0
    @Volatile private var benchCaptureOk = 0
    @Volatile private var benchBasisModel = true
    private val benchAnalyses = ArrayList<LongArray>() // [falMsTotal, clsMsTotal] per completed analysis this phase
    // v0.13.4: once-per-phase latch for the static-gate bypass log
    // in analyseFrame (reset in benchBeginPhase).
    @Volatile private var benchStaticBypassLogged = false
    // v0.11.4 SUS state: the package under suspicion and the
    // wall-clock deadline of its 15 s window. Set by noteSus on any
    // fresh verdict >= SUS_BAR (either pass); cleared by clearSus
    // on expiry, package change, or any block (see the SUS section
    // helpers below). Sampling state only — never conviction state.
    // v0.14.0 (window-stack design D5): SUS is a PER-PACKAGE MAP —
    // two windows on one screen can hold independent suspicion, and
    // one window's trip must not evict another's. Each entry carries
    // its own deadline and its own Falconsai-warmth flag (the
    // v0.11.6 refractory bookkeeping, now per package — the
    // refractory itself is a per-package map too). Single-app flows
    // behave exactly as the old single slot did: trips key on the
    // one analysed package, and the analysed-package change still
    // clears every other package's entry (handleInstantSignal).
    private class SusEntry(var untilMs: Long, var falWarm: Boolean)
    private val susByPkg = HashMap<String, SusEntry>()
    private val clsRefractoryByPkg = HashMap<String, Long>()
    // ---- v0.17.0: LIVE SESSION HOLD state ----
    // The package whose live call session is currently held, or
    // null. Armed by evalCallHold (two cheap signals: the audio
    // mode is a call mode AND the foreground surface scopes to a
    // call surface for that package), lifted when the call state
    // ends. While armed: the held package's call surface is not
    // captured or analysed at all (Alejandro's ruling — the app
    // stops judging while calling), its floating windows are
    // filtered out of the judged stack, its strikes/points were
    // reset at entry (and again at exit), and executeBlock
    // refuses any conviction that still names it (race net).
    private var callHoldPkg: String? = null
    private var callHoldSinceMs = 0L
    private var callHoldVia: String = ""
    private var callArmStreak = 0
    private var callExitStreak = 0
    // The mode-flip witness: the package + activity class that
    // were foreground on the tick the audio mode was first seen
    // in a call mode. The fallback scoping leg for call surfaces
    // whose class names match no pattern — it qualifies only
    // while that exact package + class continuity holds.
    private var callFlipPkg: String? = null
    private var callFlipClass: String? = null
    private var audioWasInCall = false
    // ---- v0.14.0: window-stack judging state ----
    // One sampled app window. [pkg] is null when the window's root
    // resolves no package (D6: logged as ?@bounds, never judged).
    // [judgeable] folds the exemption sets (a WINDOW property now);
    // [exposed] is the v0.14.1 occlusion test: pruned only by a
    // qualifying occluder (a system window, a fullscreen SystemUI
    // app window, or a fullscreen app window over a fullscreen
    // window) — a floating window is never pruned by an app
    // window; a window is JUDGED iff type-app && judgeable &&
    // exposed. [small] is the D4 size test, computed against the
    // display at sample time.
    private data class StackWindow(
        val id: Int,
        val pkg: String?,
        val bounds: Rect,
        val layer: Int,
        val focused: Boolean,
        val judgeable: Boolean,
        val exposed: Boolean,
        val small: Boolean,
        val tier: Int // Prefs.TIER_* for pkg; NORMAL when pkg == null
    ) {
        val judged: Boolean get() = pkg != null && judgeable && exposed
    }
    // The stack as one tick saw it. Every analysis is stamped with
    // the snapshot taken in its capture tick (inFlightStack), and
    // every verdict from that bitmap attributes against the
    // snapshot — never against whatever the stack became by
    // verdict time (design §6/§7).
    private data class StackSnapshot(
        val tsMs: Long,
        val appWindows: List<StackWindow>, // TYPE_APPLICATION only
        val judged: List<StackWindow>,
        val dispW: Int,
        val dispH: Int,
        val tileFrac: Double, // judged bounds area / display area (D3)
        // v0.14.1 coverage-decision record, logged by
        // onStackSampled on stack change: one "pkg=… by=…" entry
        // per judgeable window pruned (occluder class sys/appfs +
        // occluder), one "pkg=… occ=…" per floating window kept
        // judged despite app-window containment (cov-override).
        val covNotes: List<String> = emptyList(),
        val overrideNotes: List<String> = emptyList(),
        // v0.14.3: one entry per SYSTEM window considered as an
        // occluder against a judgeable window (higher layer +
        // containment), either verdict — the occluder's full
        // identity (type/title/root package/bounds/layer/
        // coverage) and whether it was positively shade-class.
        // Logged by onStackSampled whenever the consideration
        // set changes (its own signature — a system window can
        // start containing a judged window without moving the
        // judged set).
        val occNotes: List<String> = emptyList(),
        // v0.14.5: the sanity target for an exempt-only stack —
        // the topmost EXPOSED exempt app window's package
        // (Settings / SystemUI / self / permission controller).
        // Null when a judgeable window exists to price the
        // screen instead, when no exempt window is exposed, or
        // while shadeUp. tick() captures for this package at
        // the SYSTEM price when the judged set is empty.
        val sanityPkg: String? = null,
        // v0.14.5: a shade-class occluder is up (positively the
        // notification shade / an opaque fullscreen system
        // surface, or a fullscreen SystemUI app window). While
        // it holds, NO captures issue at all — the shade is an
        // occlusion state, not an unchecked app, so the sanity
        // path stands down with everything else.
        val shadeUp: Boolean = false
    ) {
        /** Topmost judged window's package (the ALIVE judged= field). */
        val topPkg: String? get() = judged.maxByOrNull { it.layer }?.pkg
        /** The pace-setter: most suspicious judged window (tier
         *  rank, ties to the topmost layer). Its package is the
         *  analysis's primary — capture key, tracker session and
         *  full-frame trip attribution in multi-window mode. */
        fun primary(): StackWindow? =
            judged.minWithOrNull(compareBy<StackWindow> { tierRank(it.tier) }.thenByDescending { it.layer })
        /** Tracker session key: the single judged package in the
         *  one-window case (identical to the legacy session), a
         *  set signature when the screen is shared — the scout and
         *  the analyses must agree on this key or the tracker
         *  session flips between feeds and no region ever forms. */
        fun trackerKey(): String = when {
            judged.isEmpty() -> ""
            judged.size == 1 -> judged[0].pkg!!
            else -> "stack:" + judged.map { it.pkg!! }.sorted().joinToString("+")
        }
    }
    @Volatile private var currentStack: StackSnapshot? = null
    @Volatile private var inFlightStack: StackSnapshot? = null
    // v0.14.5: the sanity stamp — set by tick() when the capture
    // it issues is a sanity capture (exempt-only stack), naming
    // the exempt package the frame belongs to. processFrame
    // reads and clears it before any other dispatch; it is the
    // structural gate that routes the frame to
    // processFrameSanity, where the conviction machinery does
    // not exist. Re-stamped at every capture issue.
    @Volatile private var inFlightSanityPkg: String? = null
    private var lastStackSig: String? = null
    // v0.14.3: signature of the last-emitted system-occluder
    // consideration set (WIN occ lines) — tracked apart from
    // lastStackSig because a system window can start or stop
    // containing a judged window without changing the judged
    // set, and the occluder must be named either way.
    private var lastOccSig: String? = null
    // §3 hysteresis: the stack-priced bottom rung. A hotter window
    // joining applies at once; a hotter window LEAVING applies
    // only after 2 consecutive samples without it, so a dismissing
    // PiP's animation cannot flap the cadence.
    @Volatile private var stackPricedMs: Long? = null
    private var stackSlowStreak = 0
    @Volatile private var visibleSusCached = false
    // Rotating deferral (§5): window packages the budget cap
    // deferred in the last multi-window analysis — first in line
    // (P4 order) in the next one, so a starved window is served
    // every other analysis instead of never.
    private val deferredWinPkgs = LinkedHashSet<String>()
    // Per-package crop accumulators (v0.14.0 refactor): the legacy
    // single slot (cropAccPoints/cropAccPeak) becomes one AccState
    // per package so two windows' crop evidence never mixes. The
    // legacy analysed-package change and any block clear the whole
    // map — exactly the old single-slot reset semantics.
    private class AccState(var points: Int = 0, var peak: Float = 0f)
    private val accByPkg = HashMap<String, AccState>()
    // v0.14.0 multi-window whole-frame pending (D1-B): parks on a
    // full-frame verdict >= FAL_STRONG_BAR like the legacy pending,
    // but carries NO package — on a shared screen the full frame
    // cannot name its heater. It convicts only when a crop-family
    // verdict >= STACK_LOCALISE_BAR attributes to a window inside
    // its life; a second full hit alone never convicts. Warmth =
    // any fresh attributed verdict >= SUS_BAR; expiry is the same
    // PENDING_EXPIRY_MS as the legacy pending.
    private var multiPendingParkedMs = 0L
    private var multiPendingWarmthMs = 0L
    private var multiPendingPeak = 0f
    // v0.14.0 per-window ZoD aim lock: the legacy lock keys on the
    // tracker box; the window lock keys on (package, window rect at
    // lock time, box as fractions WITHIN that rect), composes
    // inside one window (design §4.1) and dies when its window
    // leaves the judged set or its bounds move (IoU < 0.5).
    private var zodWinLockPkg: String? = null
    private var zodWinLockRect: Rect? = null
    private var zodWinLockFrac: FloatArray? = null // [l,t,r,b] within the window rect
    private var zodWinLockExpiryMs = 0L
    private var zodWinLockCold = 0
    // Per-analysis model-pass counters (v0.14.0): incremented inside
    // falScore / clsScore, reset at each processFrame entry, stored
    // in the PERF ring so the governor's latency arm can price cost
    // PER PASS-SET instead of per analysis (§5 correctness note).
    @Volatile private var analysisFalPasses = 0
    @Volatile private var analysisClsPasses = 0
    // getWindows() package-resolution cache: window id -> (bounds
    // string, package). Root-node lookups are the expensive part of
    // a sample; a window whose bounds have not moved keeps its
    // resolved package (design §2 sampling note).
    private val winPkgCache = HashMap<Int, Pair<String, String?>>()
    // ---- v0.11.9: GT ground-truth intervals (Alejandro's design) ----
    // INSTRUMENT ONLY: Alejandro taps the overlay's GT button the
    // moment explicit content appears; the open interval accumulates
    // peaks / first-hot / SUS / block timings from the analyses that
    // run anyway, and GT END summarises it for offline correlation.
    // Nothing in the detection or response path reads any of this.
    @Volatile private var gtPkg: String? = null
    @Volatile private var gtStartMs = 0L
    @Volatile private var gtPeakFalF = 0f
    @Volatile private var gtPeakFalC = 0f
    @Volatile private var gtPeakClsF = 0f
    @Volatile private var gtPeakClsC = 0f
    @Volatile private var gtFirstHotElapsedMs = -1L
    @Volatile private var gtSusElapsedMs = -1L
    private var gtForeignTicks = 0 // consecutive ticks naming a different foreground package (package-change close)
    @Volatile private var cooldownUntilMs = 0L

    companion object {
        // Apps where new visual content appears constantly: checked more often.
        // v0.13.0: RISKY is retained ONLY as the NODES-probe set
        // (v0.11.11, log-only). Cadences and the CLS trip bar re-key
        // to the tier sets below (app tiers design, Oct 29).
        private val RISKY = setOf(
            "com.android.chrome", "org.mozilla.firefox", "com.brave.browser",
            "com.instagram.android", "com.twitter.android", "com.zhiliaoapp.musically",
            "com.reddit.frontpage", "com.snapchat.android", "org.telegram.messenger",
            "com.discord", "com.google.android.youtube"
        )
        // v0.13.0 APP TIERS (app-tiers-design-2026-10-29): SUSPICIOUS is
        // the old RISKY set minus Instagram — priced like risky apps
        // because a browser / social / video / chat surface is where
        // unknown visual content appears. Instagram is NORMAL by
        // Alejandro's own example: a curated feed of followed
        // accounts, priced like everywhere else.
        private val SUSPICIOUS = setOf(
            "com.android.chrome", "org.mozilla.firefox", "com.brave.browser",
            "com.twitter.android", "com.zhiliaoapp.musically",
            "com.reddit.frontpage", "com.snapchat.android", "org.telegram.messenger",
            "com.discord", "com.google.android.youtube"
        )
        // v0.13.0: SYSTEM tier — curated tight list of stock utility
        // apps whose foregrounds mean timer / permission / arithmetic
        // use, not content. Sanity cadence only (Prefs.system_ms);
        // any escalation restores full machinery within seconds.
        // AOSP + Google + OnePlus/Oppo (ColorOS) dialects; the field
        // pass on Alejandro's phone confirms which dialect is live.
        private val SYSTEM_PKGS = setOf(
            "com.android.dialer", "com.google.android.dialer", "com.coloros.dialer", "com.oplus.dialer",
            "com.android.deskclock", "com.google.android.deskclock", "com.coloros.alarmclock", "com.oplus.alarmclock",
            "com.android.calculator2", "com.google.android.calculator", "com.coloros.calculator", "com.oplus.calculator",
            "com.android.contacts", "com.google.android.contacts", "com.coloros.contacts", "com.oplus.contacts"
        )
        // v0.2.1: the secure/incognito signal only applies to browsers —
        // incognito is a browser feature, and social apps' dark media
        // viewers false-positive the black-frame signature.
        private val BROWSERS = setOf(
            "com.android.chrome", "org.mozilla.firefox", "com.brave.browser"
        )
        // Never analyse: ourselves, system UI, settings, permission dialogs.
        private val SKIP_EXACT = setOf(
            "com.rem.screenguard", "com.android.systemui", "com.android.settings",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller"
        )
        // v0.7.1 (Alejandro): the launcher and Settings are NEVER checked —
        // no captures, no grading, even during the post-block SUS window — to
        // prevent weird lock artifacts on the home screen. Settings package
        // names vary slightly by OEM; launchers are resolved dynamically
        // from the HOME intent (see isLauncherOrSettings), with this known
        // list as a fallback.
        // v0.10.10 (Alejandro, field finding): the SETTINGS half of that
        // ruling stands, absolute. The LAUNCHER half is reversed —
        // Recents renders other apps' live surfaces inside the launcher,
        // so a launcher-exempt screen was an unguarded theatre: explicit
        // content could be watched indefinitely in a preview card while
        // the guard idled. The launcher is now captured and analysed
        // like any other app, but executeBlock converts any conviction
        // there into the light response (Home + a fixed 3 s cover) —
        // the launcher itself can NEVER be quarantined or cooled down.
        private val SKIP_SETTINGS = setOf(
            "com.oplus.settings", "com.coloros.settings", "com.oneplus.settings"
        )
        private val KNOWN_LAUNCHERS = setOf(
            "com.android.launcher", "com.android.launcher3",
            "com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher",
            "com.oneplus.launcher", "com.coloros.launcher", "com.oppo.launcher",
            "com.miui.home", "com.huawei.android.launcher", "com.microsoft.launcher"
        )
        // v0.8.0: the attention burst length is a setting (Prefs.attentionMs);
        // v0.11.0: the suspicion warmth bar is Prefs.suspFal.
        private const val DIFF_THRESHOLD = 3.0
        private const val BUSY_WATCHDOG_MS = 10_000L
        // v0.1.7 secure-window (incognito) detection. FLAG_SECURE content
        // (Chrome Incognito above all) is composited out of accessibility
        // screenshots: the capture SUCCEEDS but the content area is digitally
        // pure black (lum 0), unlike any natural dark UI (dark themes sit at
        // lum ~18-54). A frame from a risky app that is >=85% near-black
        // cells with mean luminance <=15 is treated as a secure window.
        // v0.2.1 tightened from v0.2.0 field data: true Chrome incognito
        // frames read blackFrac 0.98-0.99 / mean lum 2, while X's dark media
        // viewer false-positived at 0.89-0.94 / lum 10-13.
        private const val SECURE_BLACK_FRAC = 0.97
        private const val SECURE_BLACK_LUM = 10
        private const val SECURE_MEAN_LUM = 8.0
        // v0.8.0: the remaining corroboration count (secureFrames) is a
        // setting — adjustable in the app, no rebuild. The score BARS
        // are v0.11.0 named constants below: with one model there is
        // nothing left to tune per-model. v0.11.5: the whole-frame
        // corroboration counts/windows (strongHits, pendingWindowMs,
        // persistFrames, persistWindowMs) left the Custom screen with
        // the machinery they governed — the reformed pending is a
        // fixed FULL_STRIKES_TO_CONVICT strikes inside a fixed
        // PENDING_EXPIRY_MS warmth window (v0.16.0: the count rose
        // 2 -> 4 on Alejandro's order, after the Nov 1 field log
        // showed two-strike snap convictions on flickery benign
        // video; the warmth/expiry semantics are untouched).
        // v0.11.0 Falconsai bars (named constants, identical across
        // presets — the accumulator bars are Alejandro's v0.10.9
        // standard, unchanged): a WHOLE-PICTURE verdict at/above
        // FAL_STRONG_BAR is conviction-grade — it parks the whole-frame
        // pending as strike 1, and the FULL_STRIKES_TO_CONVICT-th one
        // convicts. Crop verdicts feed the points accumulator:
        // >= CROP_ACC_HOT earns 2 points, >= CROP_ACC_WARM earns 1,
        // CROP_ACC_FLOOR..WARM holds, below FLOOR drains 1;
        // CROP_ACC_TARGET points convicts (no witness — see the
        // accumulator state comment). Points cap at CROP_ACC_CAP
        // (v0.16.0: target and cap both 8, up from 4/6).
        private const val FAL_STRONG_BAR = 0.90f
        // v0.16.0: whole-frame strikes needed to convict. A code
        // constant, never a pref/preset — behaviour ground truth
        // lives in code (Alejandro's v0.15.1 rule).
        private const val FULL_STRIKES_TO_CONVICT = 4
        // v0.11.5: a parked whole-frame pending dies this long after
        // the last warmth (any fresh verdict >= SUS_BAR, full or crop,
        // for its package) — and ONLY by this expiry, package change,
        // or a block. No frame, however clean, can drop it.
        private const val PENDING_EXPIRY_MS = 10_000L
        private const val CROP_ACC_HOT = 0.90f
        private const val CROP_ACC_WARM = 0.75f
        private const val CROP_ACC_FLOOR = 0.60f
        private const val CROP_ACC_TARGET = 8
        private const val CROP_ACC_CAP = 8
        // v0.17.0 LIVE SESSION HOLD (design:
        // live-session-hold-design-2026-11-01; Alejandro's
        // rulings — generic scope, the app STOPS judging while
        // calling, the in-call residual accepted): consecutive
        // qualifying ticks to arm the hold, consecutive
        // non-qualifying ticks to lift it. Code constants, like
        // every behaviour bar (v0.15.1 ground-truth rule).
        private const val CALLHOLD_ARM_CHECKS = 2
        private const val CALLHOLD_EXIT_CHECKS = 3
        // v0.11.11 FIX B probe (log only): accessibility node-bounds
        // walk caps — the walk must never stall the analysis loop on
        // a huge or pathological tree (see logNodeProbe). MAX_RECTS
        // bounds the logged candidates per analysis; MIN_AREA is the
        // candidate floor as a percent of the screen (see the walk).
        private const val NODES_MAX_DEPTH = 15
        private const val NODES_MAX_VISITED = 1_000
        private const val NODES_MAX_RECTS = 8
        private const val NODES_WALK_BUDGET_MS = 50L
        private const val NODES_MIN_AREA_PCT = 2L
        // v0.10.10: the launcher light response's cover duration —
        // fixed 3 s (Alejandro), NOT the quarantine cooldown ladder.
        private const val LAUNCHER_COVER_MS = 3_000L
        // v0.11.10: POST-BLOCK SUS (Alejandro's order) — the v0.4.1
        // 30 s post-block watch is RETIRED. After any real block, a
        // global suspicion window treats EVERY app as SUS-active
        // for POST_BLOCK_SUS_MS (see postBlockSusUntilMs / susActive).
        private const val POST_BLOCK_SUS_MS = 180_000L
        // v0.11.12 PARANOID MODE (Alejandro's design): a launcher-
        // guard conviction opens a PARANOID_WINDOW_MS window during
        // which the launcher — the one surface that can never be
        // quarantined — gets SUS cadences and a PARANOID_BAR hair
        // trigger on ANY verdict from either model (see
        // paranoidUntilMs / armParanoid / fireParanoidCover).
        private const val PARANOID_WINDOW_MS = 300_000L
        private const val PARANOID_BAR = 0.30f
        // v0.6.0 quarantine (Alejandro's paradigm): a confirmed block covers
        // the app for an escalating wall-clock cooldown with no grading
        // under the cover. The escalation level resets after 30 min without
        // a block in that app. v0.7.1 (Alejandro): the penalty DOUBLES —
        // 20 s -> 40 s -> 80 s -> 160 s -> 320 s -> capped at 600 s
        // (10 min), and every further block is another 10 min.
        // v0.7.1 doubling cooldowns became settings in v0.8.0: the duration
        // is quarBaseSec doubling per level, capped at quarMaxSec, and the
        // level resets after quarResetMs without a block (see quarDurationSec).
        // v0.15.1 (Alejandro's correction) closes that detour: the ladder
        // is code-only ground truth — Prefs.QUAR_BASE_SEC (10 s)
        // doubling per level to Prefs.QUAR_MAX_SEC (600 s), the level
        // resetting after Prefs.QUAR_RESET_MS (30 min). No stored value
        // is read on this path, presets no longer write the keys, and
        // stored leftovers are deleted at every service start.
        // Post-block blind window: just long enough to cover the block
        // overlay + Home transition so the guard never judges its own screen.
        private const val BLOCK_BLIND_MS = 1_500L // v0.3.0: trimmed 3 s -> 1.5 s at Alejandro's order (still covers the block overlay + Home transition)
        private const val POLL_MS = 1_000L // v0.2.3: max tick sleep between window polls
        // v0.11.13: the platform screenshot floor and the scout's
        // place under it. CAPTURE_FLOOR_MS is the AOSP 1000 ms
        // minimum screenshot interval plus a 50 ms guard margin:
        // NOTHING in this service issues takeScreenshot inside it
        // (see lastCaptureIssuedMs). The scout ticks on that same
        // ~1 s rhythm in every mode — the v0.11.2 sub-second scout
        // periods (500 ms risky, 200 ms SUS) are REMOVED: they
        // could only ever draw INTERVAL_TIME_SHORT refusals and
        // starve the analysis they existed to serve.
        private const val CAPTURE_FLOOR_MS = 1_050L
        private const val SCOUT_MAX_PERIOD_MS = 2_000L // cap for the scout's non-rate-limit failure backoff (and the v0.11.13 cap for analysis failure backoff)
        private const val SCOUT_BACKOFF_RESET_SUCCESSES = 120 // clean scout captures before a failure backoff is forgiven
        // v0.11.13: the debug overlay shows "BLIND Ns" once the
        // foreground package — one the service should be analysing —
        // has gone this long with no successful analysis (display
        // only; no action is taken on it in this release).
        private const val BLIND_AFTER_MS = 10_000L
        // ---- v0.13.0: device capability (device-capability-design-2026-10-29) ----
        // Governor signal: the last GOV_WINDOW_ATTEMPTS capture
        // attempts spanning at most GOV_WINDOW_SPAN_MS, fed by BOTH
        // capture consumers. Code 6 (SECURE_WINDOW) counts toward the
        // window's attempts but never toward its failures.
        private const val GOV_WINDOW_ATTEMPTS = 40
        private const val GOV_WINDOW_SPAN_MS = 300_000L
        private const val GOV_MIN_ATTEMPTS = 20 // below this the ratio arms stay silent (the blindness arm may still fire)
        private const val GOV_WATCH_RATIO = 0.05 // plain GOVERNOR watch line past this failure ratio
        private const val GOV_DERATE_RATIO = 0.10 // de-rate past this, sustained across 2 consecutive evaluations
        private const val GOV_BLIND_MS = 30_000L // fast trigger: foreground owed an analysis this long with attempts in flight
        private const val GOV_BLIND_INTERVAL_FACTOR = 2L // v0.14.2: the blind bar is max(GOV_BLIND_MS, this × the interval the current pricing itself schedules) — a rung must not convict itself
        private const val GOV_SENTINEL_AGE_S = 31_536_000L // v0.14.2: 365 days — a persisted blind age at/above this could only be the never-analysed sentinel (Long.MAX_VALUE-derived, 9.2e15 s); a genuine age is bounded by process uptime
        private const val GOV_BLIND_STREAK_EVALS = 3 // v0.14.4: consecutive blind-condition evaluations required before the arm may de-rate (the ratio arm's sustain, applied to the blind arm)
        private const val GOV_BLIND_STREAK_SPAN_MS = 45_000L // v0.14.4: ... spanning at least this much wall time (~3 heartbeats) — both floors must hold, so stretched heartbeats cannot shorten the evidence window
        private const val GOV_DWELL_MS = 60_000L // minimum dwell between de-rates (one rung per decision)
        private const val GOV_LATENCY_FACTOR = 1.5 // latency arm: median full-analysis cost vs the idle effective interval
        private const val GOV_FLOOR_STRIKES = 3 // failing evaluations at FLOOR before the governor rests + the Home banner
        private const val PERF_RING = 20 // analyses kept for the PERF medians / latency arm
        // Benchmark pass bars (per rung, capability design §3).
        private const val BENCH_MIN_SUCCESS = 0.98 // capture success ratio per phase
        // v0.13.1: the v0.13.0 skip_busy FAIL bar (5% of due slots) is
        // RETIRED — it convicted a healthy flagship twice in the field
        // (skipBusy 14% / 7% at 100% capture success): due slots are
        // counted on a channel the phase shares with the diff-skip
        // re-poll loop, so a handful of busy slots on a tiny gentle-
        // phase denominator read as device weakness. skip_busy stays
        // in the BENCH line as information only. Capture success, the
        // cost bars and the watchdog bar carry the verdict (the
        // emulator fails on cost + watchdog alone).
        private const val BENCH_MED_COST_FRAC = 0.60 // median full-analysis cost vs the rung interval
        private const val BENCH_P90_COST_FRAC = 1.0 // p90 full-analysis cost vs the rung interval
        // v0.13.2: below BENCH_MIN_SAMPLES a phase has not MEASURED
        // anything — too few captures issued (att) or too few analyses
        // completed (n). That is a measurement failure, never a phone
        // failure: the run aborts, writes no cap, and says so (see
        // benchAbortUnmeasured). BENCH_STARVE_MS is the mid-phase
        // checkpoint for the same condition: fewer than
        // BENCH_MIN_SAMPLES captures issued this far into a phase
        // means the issuance path itself is stalled.
        private const val BENCH_MIN_SAMPLES = 3
        private const val BENCH_STARVE_MS = 12_000L
        // v0.11.4 SUS MODE (Alejandro's order): any fresh Falconsai
        // verdict at/above SUS_BAR on either pass (re)triggers a
        // SUS_WINDOW_MS suspicion window for the foreground package:
        // analysis at SUS_ANALYSIS_MS (the scout follows the shared
        // capture floor since v0.11.13 — see CAPTURE_FLOOR_MS). The
        // bar is a named constant, NOT Prefs.suspFal — the existing
        // warmth setting (0.40 default) drives the attention burst
        // and is deliberately untouched. SUS changes sampling
        // density only; the accumulator/strong bars do not move.
        private const val SUS_BAR = 0.30f
        private const val SUS_WINDOW_MS = 15_000L // v0.11.9: 5 s -> 15 s at Alejandro's order (retrigger extends by the full window, as before)
        private const val SUS_ANALYSIS_MS = 1_000L
        // v0.11.13: SUS_SCOUT_MS (200 ms) is REMOVED — under the
        // platform's 1000 ms screenshot floor a 200 ms scout could
        // never legally capture and only starved the 1 s analysis
        // it served. The scout now ticks at CAPTURE_FLOOR_MS in
        // every mode; in SUS the analysis frames themselves (one
        // per floor slot) feed the region tracker.
        // v0.11.6 SUS TRIPWIRE (Alejandro's design): the restored
        // incumbent classifier (re)trips SUS at/above the package's
        // effective CLS trip bar on either of its passes — the
        // graded mid-range signal Falconsai's polarised output
        // cannot supply. A tripwire bar only: it gates sampling
        // density, never any verdict.
        // v0.11.8 (Alejandro's order, from the feed logs): the bar
        // is context-aware — CLS_TRIP_BAR_RISKY in RISKY packages
        // (the feed case: the classifier hovered at 0.31–0.45,
        // real warmth that never reached 0.50, so SUS never
        // densified sampling), CLS_TRIP_BAR everywhere else.
        // See clsTripBar.
        // CLS_REFRACTORY_MS: after a SUS window in which Falconsai
        // never read >= SUS_BAR, classifier trips for that package
        // are muted this long (Falconsai trips are never muted).
        private const val CLS_TRIP_BAR = 0.50f
        private const val CLS_TRIP_BAR_RISKY = 0.30f
        private const val CLS_REFRACTORY_MS = 60_000L
        private const val GT_TIMEOUT_MS = 600_000L // v0.11.9: a forgotten GT interval auto-closes at 10 min
        private const val FLICKER_HIDE_MS = 60L // v0.2.4: overlay hidden this long before the shot (a few display frames, so the compositor has really dropped it)
        // v0.12.1: the persistent status notification — a shield in the
        // status bar whenever the guard is running (Alejandro's order).
        private const val STATUS_CHANNEL_ID = "sg_status"
        private const val STATUS_NOTIFICATION_ID = 1201
        // v0.12.2 ZoD constants (design build spec §5.1 — code
        // constants like the other v0.11.x bars; no settings UI,
        // nothing for the settings lock to freeze).
        /** falC must be below this for a burst (the dilution ceiling; numerically = SUS_BAR, named separately). */
        private const val ZOD_FALC_MAX = 0.30f
        /** Rung stop / warm threshold (= CROP_ACC_FLOOR). */
        private const val ZOD_WARM = 0.60f
        /** Aim-lock entry bar (= CROP_ACC_HOT). */
        private const val ZOD_LOCK_BAR = 0.90f
        /** AC window height cap, as a multiple of its width — the aspect correction the bench proved decisive. */
        private const val ZOD_AC_CAP_HW = 1.0
        /** Min changed cells for a valid MB (the incident's median in-box count was 73). */
        private const val ZOD_MIN_CELLS = 8
        /** MB validity: at least this fraction of the screen (design §3.2)… */
        private const val ZOD_MB_MIN_SCREEN_FRAC = 0.02
        /** …and at most this fraction. */
        private const val ZOD_MB_MAX_SCREEN_FRAC = 0.60
        /** MB / AC window at most this fraction of the tracker box's area (a zoom must actually zoom). */
        private const val ZOD_MB_MAX_PARENT_FRAC = 0.60
        /** Per-package cooldown after an all-cold burst, and after a cooled unlock. */
        private const val ZOD_COOL_FRUITLESS_MS = 20_000L
        /** Per-package cooldown after a warm-but-unlocked burst. */
        private const val ZOD_COOL_WARM_MS = 5_000L
        /** Rolling 60 s cap on zoom passes, device-level. */
        private const val ZOD_PASS_BUDGET_PER_MIN = 12
        /** A lock dies this long after its last >= ZOD_WARM locked verdict (one SUS window). */
        private const val ZOD_LOCK_EXPIRY_MS = 15_000L
        /** Consecutive locked verdicts below ZOD_FALC_MAX that release the lock as cooled. */
        private const val ZOD_LOCK_COLD_RELEASE = 2
        /** Max positive accumulator points per analysis from zoom-sourced verdicts (the design's one argued exception to the bars). */
        private const val ZOD_ZOOM_POINT_CAP = 1
        /**
         * Rollout flag (design §5.6): false = SHADOW (bursts run and
         * log would-locks; falCrop is never replaced and the
         * accumulator never sees a zoom verdict), true = LIVE.
         * Shipped LIVE at Alejandro's decision (2026-10-29) — the
         * shadow-first recommendation was declined with the
         * meme-politics-04-class residual risk accepted knowingly.
         * One-line flip back to shadow if the field ever demands it.
         */
        private const val ZOD_MODE_LIVE = true
        // ---- v0.14.0: window-stack judging (design §4/§5/§6/§8) ----
        /** Per-analysis model-time budget across ALL pass-sets of a
         *  multi-window analysis (≈ 5 pass-sets at the field's
         *  472 ms/set). Passes run in the §4 priority order until
         *  the cap; the rest defer (PASS defer) and rotate to the
         *  front of the next analysis. Decision D3. */
        private const val ANALYSIS_BUDGET_MS = 2_400L
        /** D3: when judgeable windows tile at least this fraction
         *  of the display, the P1 full-frame pass is skipped — on a
         *  clean split the windows ARE the screen. */
        private const val STACK_TILE_SKIP_FRAC = 0.90
        /** Fullscreen dedupe (§4): a judged window covering at
         *  least this fraction of the display IS the full frame —
         *  P1 doubles as its window pass, so single-app screens
         *  cost exactly what v0.13.5 cost. */
        private const val STACK_FULLSCREEN_FRAC = 0.95
        /** v0.14.3: the shade's accessibility window title on
         *  AOSP (the NotificationShade window names itself);
         *  one of the two SystemUI-identity signals for the
         *  system-occluder test — the other is the occluder's
         *  resolved root package (isSystemUiPkg). Either
         *  signal establishes identity; the prune additionally
         *  requires STACK_FULLSCREEN_FRAC coverage, so a
         *  SystemUI-drawn PiP frame (identity without
         *  coverage) and an anonymous fullscreen system
         *  window (coverage without identity) both fail. */
        private const val SHADE_TITLE_MARKER = "NotificationShade"
        /** D4 small-window legs (§8): a judged window is small when
         *  its area is under this fraction of the display (the
         *  ZOD_MB_MIN_SCREEN_FRAC precedent) OR its smaller side is
         *  under STACK_SMALL_MIN_SIDE_PX. Small windows are checked
         *  at any size and their classifier still trips SUS, but
         *  their combined positive Falconsai points cap at
         *  STACK_SMALL_POINT_CAP per analysis. */
        private const val STACK_SMALL_AREA_FRAC = 0.02
        private const val STACK_SMALL_MIN_SIDE_PX = 224
        private const val STACK_SMALL_POINT_CAP = 1
        /** D1-B (§6): on a multi-window screen, a crop-family
         *  verdict at/above this bar localises a parked whole-frame
         *  pending to its window and convicts it (= ZOD_WARM). */
        private const val STACK_LOCALISE_BAR = 0.60f
    }

    private fun dbg(line: String) {
        if (Prefs.verbose(this)) DebugLog.log(this, line)
    }

    /**
     * v0.11.14: THE failure channel (Alejandro's order — every
     * failure absolutely marked). Every failure in the guard is
     * logged through here, so its line begins with the literal
     * prefix "FAIL " (greppable, countable), the session failure
     * counter ([failTotal], surfaced as fails=<n> on the ALIVE
     * heartbeat) increments, and the event is recorded into
     * [ScoreHistory] under [kind] ("capture" / "watchdog" /
     * "model" / "other") so the score graph paints it as a red
     * tick at the moment it happened. [line] carries the detail —
     * for exceptions, ALWAYS the class + message, never a bare
     * swallow. Observability only: logging a failure never changes
     * how the failure itself is handled.
     */
    private fun failLog(kind: String, line: String) {
        failTotal++
        ScoreHistory.recordFailure(System.currentTimeMillis(), kind)
        DebugLog.log(this, "FAIL $line")
    }

    /**
     * v0.11.14: [failLog] for failure sites on per-tick paths (the
     * cosmetic region outline, the active-window query, the region
     * crop): the first occurrence logs at once and a persistent
     * fault re-marks at most once per 30 s per [tag] — marked and
     * counted, never a flood that pushes real evidence out of the
     * ring buffer.
     */
    private fun failLogThrottled(tag: String, kind: String, line: String) {
        val now = System.currentTimeMillis()
        if (now - (failThrottleMs[tag] ?: 0L) < 30_000L) return
        failThrottleMs[tag] = now
        failLog(kind, line)
    }

    // ---- v0.12.1: status-bar shield (Alejandro's order, Oct 29) ----
    // A persistent, silent, ongoing notification whose small icon is
    // the shield: visible in the status bar for exactly as long as
    // this service runs — the service running IS "protection on"
    // (there is no separate pause state; disabling the accessibility
    // service stops the service and onDestroy cancels the icon).
    // POST_NOTIFICATIONS (Android 13+) is requested from Home's Setup
    // checklist; protection is never gated on it — without the grant
    // the guard works fully and the icon simply cannot show.
    // syncStatusNotification is reconciled from onServiceConnected,
    // onDestroy and the ~15 s heartbeat, so a grant or revocation
    // lands without an app restart. Failures post through failLog:
    // a shield that silently never appears is a broken promise.

    private fun ensureStatusChannel() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(STATUS_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    STATUS_CHANNEL_ID, "Protection status", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shield in the status bar while ScreenGuard is protecting"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        } catch (t: Throwable) {
            failLog("other", "status channel create ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun syncStatusNotification() {
        val nm = try {
            getSystemService(NotificationManager::class.java)
        } catch (t: Throwable) {
            failLog("other", "status notif manager ${t.javaClass.simpleName}: ${t.message}")
            return
        } ?: return
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (running && allowed) {
            if (statusNotifShown) return
            try {
                ensureStatusChannel()
                val tap = PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notif = Notification.Builder(this, STATUS_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_shield)
                    .setContentTitle("ScreenGuard")
                    .setContentText("Protection is on")
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setContentIntent(tap)
                    .build()
                nm.notify(STATUS_NOTIFICATION_ID, notif)
                statusNotifShown = true
                DebugLog.log(this, "STATUS shield posted (protection on)")
            } catch (t: Throwable) {
                failLog("other", "status notif post ${t.javaClass.simpleName}: ${t.message}")
            }
        } else if (statusNotifShown) {
            // Toggled off, permission revoked, or the service is going
            // down: the shield leaves with the protection.
            try { nm.cancel(STATUS_NOTIFICATION_ID) } catch (t: Throwable) {
                failLog("other", "status notif cancel ${t.javaClass.simpleName}: ${t.message}")
            }
            statusNotifShown = false
            DebugLog.log(this, "STATUS shield removed (running=$running allowed=$allowed)")
        } else if (running && !allowed && !statusSuppressionLogged) {
            // v0.13.1: protection is ON but the shield cannot show —
            // say so once per service start instead of staying silent
            // (the heartbeat keeps reconciling; it does not re-log).
            statusSuppressionLogged = true
            DebugLog.log(this, "STATUS shield suppressed — notifications not granted")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.appendLog(this, ts() + " service connected, loading model…")
        DebugLog.log(this, "SVC connected (v0.7.0 verbose=${Prefs.verbose(this)})")
        // Fresh counters for this diagnostic session.
        for (k in listOf("ticks", "cap_try", "cap_ok", "cap_fail", "diff_skip", "skip_busy", "skip_pkg", "secure_frames", "cooldown_skip")) {
            Prefs.putStatus(this, k, 0)
        }
        // v0.11.13: the capture-health counters restart with the session too.
        capAttAll = 0
        capOkAll = 0
        capFailByCode = emptyMap()
        failTotal = 0 // v0.11.14: the FAIL tally restarts with the session too
        lastAnalysisOkPkg = null
        lastAnalysisOkMs = 0L
        lastCaptureIssuedMs = 0L
        // v0.13.0: capability state restarts with the session too —
        // the governor window, the PERF ring and the due-slot books
        // are this session's evidence. The PERSISTED rung / cap are
        // not touched here; they are re-applied below.
        govWindow.clear()
        perfSamples.clear()
        govReady = false
        heartbeatSeen = false
        govLastDerateMs = 0L
        govPrevOverRatio = false
        govWatchLogged = false
        govFloorStrikes = 0
        govResting = false
        govBlindStreak = 0
        govBlindStreakStartMs = 0L
        govBlindStreakCtx = null
        govBlindAgeMs = -1L
        govBlindBarMs = 0L
        dueSlotsAll = 0
        dueBusyAll = 0
        lastCountedDueMs = 0L
        watchdogTripsAll = 0
        benchActive = false
        synchronized(benchAnalyses) { benchAnalyses.clear() }
        // v0.13.5: reconcile the PERSISTED bench session against the
        // in-memory one just reset. benchSetStatus mirrors the run
        // into Prefs (bench_active / bench_status / bench_phase_end)
        // so BenchmarkActivity can render it — but if the previous
        // process died or was rebound mid-run, the stored flag
        // claims a run this instance does not have: a persisted true
        // here is definitionally an orphan. Left alone it dead-ends
        // the feature for good: MainActivity.startBenchmark arms a
        // new run only when bench_active reads false, and benchAbort
        // early-returns on the (false) in-memory flag, so Cancel
        // cannot clear it either — the button silently never starts
        // a run again (field-observed on the emulator during the
        // v0.13.4 verification; disclosed in that release's notes).
        // Clear the dead session record whole, in one edit; like a
        // cancel / unmeasured abort, an orphaned run writes NO
        // result record and NO cap — the capability state (cap /
        // governor) is not the dead run's to touch.
        if (Prefs.get(this).getBoolean("bench_active", false)) {
            Prefs.get(this).edit()
                .putBoolean("bench_active", false)
                .remove("bench_run")
                .remove("bench_cancel")
                .remove("bench_status")
                .remove("bench_phase_end")
                .apply()
            failLog("bench", "bench-orphaned — cleared stale benchmark state on service start")
        }
        // v0.15.1: quarantine duration is code-only behaviour (Alejandro's
        // correction — see Prefs.QUAR_*). Any stored quar_base_s /
        // quar_max_s / quar_reset_min left over from the settings era is
        // deleted at every service start, so no leftover can veto the
        // code ladder; the QUAR line below is the field proof, present
        // in every start's log, that ground truth is live.
        Prefs.get(this).edit()
            .remove("quar_base_s")
            .remove("quar_max_s")
            .remove("quar_reset_min")
            .apply()
        DebugLog.log(this, "QUAR ladder src=code base=10s steps=10/20/40/80/160/320/600 (stored values removed)")
        // v0.13.0 PERF LOGGING (Alejandro's requirement): every log
        // from this phone states the device up front, so a log from
        // an unknown weaker phone identifies itself — manufacturer,
        // model, Android SDK, ABI, app version.
        val appVer = try {
            @Suppress("DEPRECATION")
            val pi = packageManager.getPackageInfo(packageName, 0)
            "${pi.versionName}(${pi.longVersionCode})"
        } catch (e: Exception) { "?" }
        DebugLog.log(
            this,
            "DEVICE mfr=${Build.MANUFACTURER} model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} app=$appVer"
        )
        // v0.13.0: a persisted governor rung / benchmark cap is
        // re-applied (and re-stated) at every service start — the
        // de-rate survives restarts until a benchmark rewrites the
        // record (capability design §2/§4).
        // v0.14.2: one exception — a governor record whose reason
        // is the blind signature at a sentinel-scale age is NOT
        // re-applied; it is discarded below (self-heal).
        run {
            val capR = Prefs.capRung(this)
            val govR = Prefs.govRung(this)
            if (capR >= 0) {
                DebugLog.log(
                    this,
                    "CAP re-applied cap=${Prefs.rungName(capR)} episodeOk=${Prefs.capEpisodeOk(this)} " +
                        "device=${Prefs.capDevice(this)} ts=${Prefs.capTs(this)}"
                )
            }
            if (govR >= 0) {
                val reason = Prefs.govReason(this)
                // v0.14.2 self-heal: a persisted rung whose recorded
                // reason is the blind signature at a sentinel-scale
                // age was never a measurement — the pre-fix trigger
                // read a never-analysed package as infinitely blind
                // (field 2026-10-30: the old re-apply line itself
                // logged reason=blind lastOkAge=9223372036854775s).
                // Discard it here, loudly, instead of re-applying
                // it: the phone recovers at the first start of the
                // fixed build, no benchmark needed. Genuine records
                // (failure / latency reasons, blind records with
                // plausible ages) re-apply exactly as before.
                val bogusAgeS = sentinelBlindAgeS(reason)
                if (bogusAgeS >= 0L) {
                    Prefs.clearGov(this)
                    Prefs.get(this).edit().remove("gov_floor_banner").apply()
                    failLog(
                        "governor",
                        "GOV rung=${Prefs.rungName(govR)}->none why=discard-blind-sentinel age=${bogusAgeS}s reason=$reason — " +
                            "persisted governor rung discarded at service start (v0.14.2 self-heal)"
                    )
                } else {
                    DebugLog.log(
                        this,
                        "GOV rung=none->${Prefs.rungName(govR)} why=reapply eff=${Prefs.rungName(Prefs.effRung(this))} reason=$reason"
                    )
                }
            }
        }
        resolveFalLatch() // v0.11.0: honour / arm the Falconsai crash-loop latch and state it before any frame is graded
        inferExec.execute {
            if (!falLatchedOff) {
                DebugLog.log(this, "FAL load start") // stage marker: before session creation
                falModel = try {
                    FalconModel(applicationContext) { line -> DebugLog.log(this, line) }.also {
                        Prefs.appendLog(this, ts() + " model loaded (Falconsai nsfw_image_detection, ONNX)")
                        Prefs.putStatus(this, "model_ok", true)
                        DebugLog.log(this, "FAL session ok — Falconsai loaded")
                    }
                } catch (t: Throwable) {
                    Prefs.appendLog(this, ts() + " FAL LOAD FAILED: ${t.message}")
                    Prefs.putStatus(this, "model_ok", false)
                    failLog("model", "fal load ${t.javaClass.simpleName}: ${t.message} — latching FAL off")
                    latchFalOffNow("load", "${t.javaClass.simpleName}: ${t.message}")
                    null
                }
            }
            // v0.11.6: load the SUS tripwire classifier in the same
            // stage, independently of Falconsai's state — and just as
            // failure-isolated. A load failure here must never touch
            // the guard: the state line says UNAVAILABLE with the
            // reason, clsModel stays null, and every CLS consumer in
            // processFrame simply skips (Falconsai-only, v0.11.5).
            DebugLog.log(this, "CLS load start") // stage marker: before session creation
            clsModel = try {
                SceneClassifier(applicationContext).also {
                    Prefs.putStatus(this, "cls_state", "ARMED")
                    DebugLog.log(this, "CLS state: ARMED")
                    Prefs.appendLog(this, ts() + " CLS state: ARMED (SUS tripwire)")
                    DebugLog.log(this, "CLS session ok — incumbent classifier loaded (tripwire only)")
                }
            } catch (t: Throwable) {
                val reason = "load: ${t.javaClass.simpleName}: ${t.message}"
                clsFailReason = reason
                Prefs.putStatus(this, "cls_state", "UNAVAILABLE ($reason)")
                DebugLog.log(this, "CLS state: UNAVAILABLE ($reason)")
                failLog("model", "cls load ${t.javaClass.simpleName}: ${t.message} — tripwire UNAVAILABLE, Falconsai-only")
                Prefs.appendLog(this, ts() + " CLS state: UNAVAILABLE ($reason) — Falconsai-only")
                null
            }
        }
        running = true
        statusSuppressionLogged = false // v0.13.1: one suppression line per service start
        syncStatusNotification() // v0.12.1: shield up — protection is on
        nextCaptureDueMs = 0L
        scheduleNext()
        scheduleScout() // v0.11.2: the region scout runs on its own fast timer
        DebugLog.log(this, "SVC loop armed (v0.7.0)")
        DebugLog.log(this, "SCOUT loop armed (v0.11.13: ~1s ticks through the shared ${CAPTURE_FLOOR_MS}ms capture floor, grid-only)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != foregroundPkg) {
                foregroundPkg = pkg
                lastThumb = null
                dbg("FG -> $pkg tier=${tierName(tierOf(pkg))} skip=${pkg in SKIP_EXACT}")
            }
            // v0.17.0: the activity class rides the same event —
            // the live session hold's surface-scoping leg reads
            // it. Our own windows are excluded: overlay add /
            // remove churn (readout, dots, covers) must not
            // redefine what the foreground surface is.
            if (pkg != packageName) {
                val cls = event.className?.toString()
                if (cls != null) foregroundClass = cls
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        syncStatusNotification() // v0.12.1: shield leaves with the service
        mainHandler.removeCallbacks(scoutRunnable) // v0.11.2
        debugView?.let { v ->
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {
                failLog("other", "window teardown (debug overlay) ${e.javaClass.simpleName}: ${e.message}")
            }
            debugView = null
        }
        gtButton?.let { v ->
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {
                failLog("other", "window teardown (GT button) ${e.javaClass.simpleName}: ${e.message}")
            }
            gtButton = null
            gtShownState = null
        }
        outlineView?.let { v ->
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {
                failLog("other", "window teardown (region outline) ${e.javaClass.simpleName}: ${e.message}")
            }
            outlineView = null
            outlineKey = null
        }
        hideLauncherCover()
        hideQuarantineCover()
        DebugLog.log(this, "SVC destroyed")
        super.onDestroy()
    }

    // ---- v0.13.0: app tiers (app-tiers-design-2026-10-29) ----
    // A package's tier is a pure function of its name, resolved at
    // every scheduling decision (never stored): EXEMPT is not a tier
    // (the skip sets in tick/scoutTargetPkg answer first), SYSTEM
    // and SUSPICIOUS are the curated sets, everything else — the
    // launcher, Photos/Gallery, Instagram, every unknown package —
    // is NORMAL. Prefs.TIER_* ids.
    private fun tierOf(pkg: String): Int = when {
        pkg in SUSPICIOUS -> Prefs.TIER_SUSPICIOUS
        pkg in SYSTEM_PKGS -> Prefs.TIER_SYSTEM
        else -> Prefs.TIER_NORMAL
    }

    private fun tierName(tier: Int): String = when (tier) {
        Prefs.TIER_SUSPICIOUS -> "suspicious"
        Prefs.TIER_SYSTEM -> "system"
        else -> "normal"
    }

    /** The user's own idle cadence for [pkg]'s tier (the pref the
     *  preset or the Custom sliders wrote). */
    private fun tierPrefMs(pkg: String): Long = when (tierOf(pkg)) {
        Prefs.TIER_SUSPICIOUS -> Prefs.riskyIntervalMs(this)
        Prefs.TIER_SYSTEM -> Prefs.systemIntervalMs(this)
        else -> Prefs.normalIntervalMs(this)
    }

    /**
     * v0.13.0: the effective idle interval for [pkg] — the user's
     * tier cadence, slowed (never sped up) by the effective
     * capability rung (Prefs.effRung: min of chosen / benchmark cap /
     * governor). This is the ONLY place the rung touches cadence;
     * the episode rungs of intervalMs' ladder (quarantine, pending,
     * secure, attention, SUS, post-block, paranoid) are never
     * de-rated (capability design §2, decision 3).
     */
    private fun tierIntervalMs(pkg: String): Long {
        val tier = tierOf(pkg)
        // v0.14.5: the SYSTEM tier prices through systemPriceMs
        // so the universal ceiling binds it in every state.
        if (tier == Prefs.TIER_SYSTEM) return systemPriceMs()
        return maxOf(tierPrefMs(pkg), Prefs.rungFloorMs(Prefs.effRung(this), tier))
    }

    /**
     * v0.14.5: the effective SYSTEM-tier price — the user's
     * system_ms slowed (never sped up) by the effective rung's
     * SYSTEM floor, then clamped to the universal ceiling
     * (Prefs.SANITY_CEILING_MS, 60 s) at pricing time. The clamp
     * is what makes the ceiling universal: it binds a hand-set
     * Custom slider value and every rung including FLOOR (whose
     * SYSTEM floor is itself 60 s since v0.14.5 — a 60 s sanity
     * capture is one analysis a minute, which even a starving
     * phone sustains; the governor protects against 1–4 s
     * cadences, not one look a minute). This one price serves
     * both SYSTEM-tier foregrounds and the exempt-surface
     * sanity state — they are the same "lowest cadence" by
     * Alejandro's ruling.
     */
    private fun systemPriceMs(): Long = minOf(
        maxOf(Prefs.systemIntervalMs(this), Prefs.rungFloorMs(Prefs.effRung(this), Prefs.TIER_SYSTEM)),
        Prefs.SANITY_CEILING_MS
    )

    private fun intervalMs(): Long {
        // Cadence: fast while anything needs a quick next look, the
        // tier rate while idle. v0.13.0: tiers price ONLY this
        // function's bottom rung — every rung above it (quarantine,
        // pending, secure, attention, SUS, post-block, paranoid)
        // keeps its exact pre-tiers meaning and cadence.
        val pkg = lastWinPkg ?: foregroundPkg
        // v0.13.0: a running benchmark owns our own package's cadence
        // outright (the rung under test IS the schedule).
        if (benchActive && pkg == packageName) return benchIntervalMs
        // v0.6.0: a quarantined foreground app polls fast so its countdown
        // stays smooth and expiry is prompt.
        // v0.13.0 (tiers §7-S1): 500L → 1000L — sub-floor cadences
        // only ever drew code-3 refusals (see warningIntervalMs).
        if (Prefs.quarUntil(this, pkg) > System.currentTimeMillis()) return 1000L
        // v0.6.0: a pending block gets the fast cadence so its confirming
        // frame lands ~1 s later, not after the idle rate.
        if (pendingBlockPkg == pkg) return warningIntervalMs
        // v0.7.1: a first secure frame likewise — its confirming second
        // frame must not wait out the relaxed idle rate.
        if (instantSecureStreak > 0 && streakPkg == pkg) return warningIntervalMs
        // v0.7.1: ANY suspicious frame holds max cadence for its burst.
        if (attentionPkg == pkg && System.currentTimeMillis() < attentionUntilMs) return warningIntervalMs
        // v0.11.4: SUS MODE pins analysis to 1 s for the suspicious
        // package — denser than every preset idle rate; the warning
        // paths above now share the same 1 s (v0.13.0: the 500 ms
        // cadence is gone everywhere, per the platform floor).
        if (susActive(pkg)) return SUS_ANALYSIS_MS
        // v0.14.0: stack SUS — a live trip window on ANY visible
        // judgeable window's package densifies the whole screen
        // (D5: cadence follows visibility; the entry's own clock
        // and refractory are untouched by whether it is visible).
        if (anyVisibleSus()) return SUS_ANALYSIS_MS
        // v0.11.12: paranoid window open + launcher in front — SUS
        // cadence for the launcher too, trips or no trips, so a
        // rise is caught within a second.
        if (paranoidLauncherActive(pkg)) return SUS_ANALYSIS_MS
        // v0.14.0: paranoid keys to the launcher WINDOW (§3) — the
        // launcher sitting in the judged stack (even unfocused,
        // under a PiP) earns the same cadence while the window is
        // open. Single-window screens: the launcher in the stack IS
        // the foreground case above.
        if (paranoidActive() && currentStack?.judged?.any { isLauncherPkg(it.pkg!!) } == true) return SUS_ANALYSIS_MS
        // v0.13.0: the idle bottom rung — the package's tier cadence
        // under the effective capability rung.
        // v0.14.0: when the stack model is live, the bottom rung is
        // the stack's price — the most suspicious judgeable window's
        // tier interval, hysteresis-filtered (stackPricedMs, set in
        // onStackSampled). With one judgeable window it equals
        // tierIntervalMs(that window) exactly.
        val priced = stackPricedMs
        if (priced != null) return priced
        return tierIntervalMs(pkg)
    }

    /** v0.7.1: does this package currently deserve the max cadence? */
    private fun urgentFor(pkg: String): Boolean {
        if (pendingBlockPkg == pkg) return true
        if (streakPkg == pkg && instantSecureStreak > 0) return true
        if (attentionPkg == pkg && System.currentTimeMillis() < attentionUntilMs) return true
        // v0.11.4: a live SUS window also pulls the next capture in,
        // so suspicion takes effect at once instead of after the
        // relaxed idle wait it exists to skip.
        // v0.11.12: the paranoid launcher cadence pulls in too.
        // v0.14.0: suspicion on any visible judgeable window pulls
        // in too — the pull-in exists so a trip takes effect at
        // once, and a PiP's trip is a trip.
        return susActive(pkg) || paranoidLauncherActive(pkg) || anyVisibleSus()
    }

    // ---- v0.11.4: SUS MODE state helpers ----
    // Suspension buys sampling density only: while live, analysis
    // runs at 1 s (intervalMs) and the scout ticks on the shared
    // ~1 s capture floor (scoutPeriodMs, v0.11.13), and the debug overlay carries a literal
    // "SUS MODE" line (refreshOverlay). Expiry is passive: tick()
    // spots the lapsed window and logs the off-transition once.
    // v0.11.6: suspicion now has TWO trigger sources — Falconsai
    // (noteSus) and the classifier tripwire (noteSusCls) — and the
    // expiry path (expireSus) applies the refractory guard-rail.
    // v0.11.10: a THIRD way in — the global post-block window
    // (postBlockSusUntilMs, armed by executeBlock after a real
    // block): while it runs, EVERY package is SUS-active. It is
    // not a trip: it carries no package, no warmth bookkeeping,
    // and no refractory interaction (see tripSusActive).

    /** Is [pkg]'s own TRIP window live right now? The refractory
     *  bookkeeping keys off this alone — the post-block window
     *  must not create or consume refractory state. v0.14.0: reads
     *  the per-package map (D5). */
    private fun tripSusActive(pkg: String): Boolean {
        val e = susByPkg[pkg] ?: return false
        return System.currentTimeMillis() < e.untilMs
    }

    /** v0.14.0: does any CURRENTLY VISIBLE judgeable window's
     *  package hold a live trip window? (Reads the tick-refreshed
     *  cache — intervalMs runs before this tick's own stack sample,
     *  on the previous sample, exactly as it reads lastWinPkg.) */
    private fun anyVisibleSus(): Boolean = visibleSusCached

    /** v0.11.10: is the global post-block SUS window live? */
    private fun postBlockSusActive(): Boolean =
        System.currentTimeMillis() < postBlockSusUntilMs

    /** Is SUS MODE live for [pkg] right now? — its own trip window,
     *  or the global post-block window (after a block, every app is
     *  treated as SUS-active: 1 s analysis, ~1 s scout floor, SUS MODE
     *  marker, red outline). */
    private fun susActive(pkg: String): Boolean =
        tripSusActive(pkg) || postBlockSusActive()

    /** v0.11.12: is the paranoid window live? (Armed only by a
     *  launcher-guard conviction — see armParanoid.) */
    private fun paranoidActive(): Boolean =
        System.currentTimeMillis() < paranoidUntilMs

    /** v0.11.12: does [pkg] get paranoid treatment right now —
     *  the window is open AND the launcher is in front? Cadence
     *  and the hair trigger key off this alone; the window by
     *  itself changes NOTHING for any other package. Settings can
     *  never satisfy this (it is never the launcher), so its
     *  absolute exemption is structural, not a special case. */
    private fun paranoidLauncherActive(pkg: String): Boolean =
        paranoidActive() && isLauncherPkg(pkg)

    /** v0.11.12: (re)arm the paranoid window — called ONLY from
     *  executeBlock's launcher-guard branch, i.e. on a fresh
     *  conviction, which is also the only event allowed to
     *  restart the 300 s. */
    private fun armParanoid() {
        paranoidUntilMs = System.currentTimeMillis() + PARANOID_WINDOW_MS
        DebugLog.log(this, "PARANOID on (300s) — launcher guard conviction")
        refreshOverlay()
    }

    /** v0.11.12: the paranoid hair trigger — while the window is
     *  open and the launcher is in front, ANY verdict at/above
     *  [PARANOID_BAR] fires the launcher-guard ACTION (Home +
     *  the fixed 3 s cover) at once. This is an action, not a
     *  conviction: it touches no conviction state (no pending or
     *  accumulator reset, no SUS clear, no GT close — a labelled
     *  interval stays open across covers, so a test session shows
     *  the cover cadence inside one interval) and it never
     *  extends the window. Called once per fresh analysis, after
     *  handleSignal has had its chance to convict strictly on
     *  the same frame. */
    private fun fireParanoidCover(pkg: String, falFull: Float, falCrop: Float?, clsFull: Float?, clsCrop: Float?) {
        val f2 = { v: Float? -> v?.let { "%.2f".format(it) } ?: "-" }
        DebugLog.log(this, "PARANOID cover pkg=$pkg falF=${f2(falFull)} falC=${f2(falCrop)} clsF=${f2(clsFull)} clsC=${f2(clsCrop)} (bar ${"%.2f".format(PARANOID_BAR)})")
        mainHandler.post {
            performGlobalAction(GLOBAL_ACTION_HOME)
            showLauncherCover()
        }
    }

    /**
     * A fresh Falconsai verdict at/above [SUS_BAR] — (re)trigger
     * suspicion for [pkg]: the 15 s window runs from THIS verdict,
     * so a warm session keeps extending its own dense sampling.
     * Logged once per trigger event (never per tick). v0.11.6: a
     * Falconsai trigger also marks the window Falconsai-warm, which
     * is what spares the package the classifier refractory when the
     * window expires (see expireSus).
     */
    private fun noteSus(pkg: String, score: Float, src: String) {
        val e = susByPkg.getOrPut(pkg) { SusEntry(0L, false) }
        e.untilMs = System.currentTimeMillis() + SUS_WINDOW_MS
        e.falWarm = true
        DebugLog.log(this, "SUS on pkg=$pkg fal=${"%.2f".format(score)} src=$src")
        noteGtSus(pkg) // v0.11.9: instrument timing only
        refreshOverlay()
    }

    /**
     * v0.11.8: the effective classifier SUS trip bar for [pkg] —
     * [CLS_TRIP_BAR_RISKY] when the package is in the RISKY set
     * (the same classification that drives the risky cadences),
     * [CLS_TRIP_BAR] for every other package.
     * v0.13.0: re-keyed RISKY → SUSPICIOUS with identical semantics
     * (tiers design §3): the low bar now follows the SUSPICIOUS
     * tier. The one behavioural consequence is Instagram's: NORMAL
     * tier, so its bar rises 0.30 → 0.50 — priced in the design
     * (its Falconsai trips are untouched; only the classifier knock
     * needs to be stronger there).
     */
    private fun clsTripBar(pkg: String): Float =
        if (pkg in SUSPICIOUS) CLS_TRIP_BAR_RISKY else CLS_TRIP_BAR

    /**
     * v0.11.6: a fresh CLASSIFIER reading at/above the package's
     * effective trip bar (see clsTripBar) — the tripwire
     * (re)trigger: the same 15 s window and the same dense sampling
     * as a Falconsai trip, from a graded mid-range signal
     * Falconsai cannot express. Two differences, both by design:
     * it never marks the window Falconsai-warm (that is what the
     * refractory measures), and it is silently dropped while this
     * package's classifier refractory is running. Logged once per
     * trigger event (never per tick); v0.11.8: the log line
     * carries the effective bar (bar=) for field debuggability.
     */
    private fun noteSusCls(pkg: String, score: Float, src: String) {
        if (clsRefractoryActive(pkg)) return
        // v0.11.10: "fresh" judges the TRIP window only — the global
        // post-block window being live must not stop a classifier-
        // opened window from starting Falconsai-cold (that is what
        // the refractory measures at ITS expiry).
        val freshWindow = !tripSusActive(pkg)
        val e = susByPkg.getOrPut(pkg) { SusEntry(0L, false) }
        e.untilMs = System.currentTimeMillis() + SUS_WINDOW_MS
        if (freshWindow) e.falWarm = false
        DebugLog.log(this, "SUS on pkg=$pkg cls=${"%.2f".format(score)} src=$src bar=${"%.2f".format(clsTripBar(pkg))}")
        noteGtSus(pkg) // v0.11.9: instrument timing only
        refreshOverlay()
    }

    /** v0.11.6: is the classifier refractory (see expireSusFor) currently muting classifier trips for [pkg]? */
    private fun clsRefractoryActive(pkg: String): Boolean {
        val until = clsRefractoryByPkg[pkg] ?: return false
        return System.currentTimeMillis() < until
    }

    /**
     * v0.11.6: the SUS expiry path (called from tick when the
     * window has lapsed). The refractory guard-rail lives here and
     * ONLY here — package change and blocks clear suspicion via
     * clearSus directly and never mute the tripwire. If Falconsai
     * never read >= SUS_BAR during the expiring window, the
     * classifier alone kept the phone sampling densely for
     * nothing: mute classifier-sourced trips for this package for
     * CLS_REFRACTORY_MS (logged once, here, per refractory).
     * Falconsai-sourced trips are never muted.
     */
    private fun expireSusFor(p: String) {
        val e = susByPkg[p] ?: return
        if (!e.falWarm) {
            clsRefractoryByPkg[p] = System.currentTimeMillis() + CLS_REFRACTORY_MS
            DebugLog.log(this, "SUS refractory pkg=$p (cls muted 60s)")
        }
        clearSusEntry(p, "expired")
    }

    /** v0.14.0: expire every lapsed trip window (tick calls this
     *  once per poll; each package's refractory is judged on its
     *  own warmth, as the single-slot expiry judged the one). */
    private fun expireSusLapsed() {
        val now = System.currentTimeMillis()
        val lapsed = susByPkg.entries.filter { now >= it.value.untilMs }.map { it.key }
        for (p in lapsed) expireSusFor(p)
    }

    /** Remove [pkg]'s suspicion entry (expiry / package change /
     *  any block) and log the off-transition once. */
    private fun clearSusEntry(pkg: String, reason: String) {
        if (susByPkg.remove(pkg) == null) return
        DebugLog.log(this, "SUS off pkg=$pkg ($reason)")
        refreshOverlay()
    }

    /** v0.14.0: end suspicion for EVERY package (the legacy
     *  clearSus call sites that meant "the" suspicion: the
     *  analysed-package change in handleInstantSignal, and any
     *  block in executeBlock — a block ends trip suspicion
     *  globally, as the single slot did; the per-package entries
     *  it removes are logged individually only when the caller
     *  names one — here the count rides the line). */
    private fun clearSus(reason: String) {
        if (susByPkg.isEmpty()) return
        val pkgs = susByPkg.keys.toList()
        susByPkg.clear()
        for (p in pkgs) DebugLog.log(this, "SUS off pkg=$p ($reason)")
        refreshOverlay()
    }

    // ---- v0.14.0: THE WINDOW STACK (window-stack-judging-design §2/§3) ----
    // The stack is re-read inside every tick() poll — the cadence
    // machinery never runs on a stack older than one poll. One
    // screenshot still serves everything: capture remains a single
    // full-display takeScreenshot; the windows matter as rectangles
    // and names over that one bitmap (Android offers no per-window
    // screenshot, and needs none).

    private fun bstr(r: Rect): String = "${r.left},${r.top},${r.right},${r.bottom}"

    /** Is [pkg]'s window exempt from judging? The v0.7.1 absolutes,
     *  rescoped from screens to windows (§2): ourselves, SystemUI,
     *  Settings (AOSP + OEM), permission controllers. */
    private fun isExemptWindowPkg(pkg: String): Boolean =
        pkg in SKIP_EXACT || isSettingsPkg(pkg)

    /** v0.14.1: SystemUI's own package — its fullscreen
     *  application windows are shade / keyguard-class system
     *  surfaces, never content, so they classify as SYSTEM
     *  occluders in the exposure test (some builds render the
     *  shade as an app window instead of a TYPE_SYSTEM). */
    private fun isSystemUiPkg(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg.endsWith(".systemui")

    /**
     * Sample the visible window stack once. Returns null when the
     * stack itself cannot be read (the caller then falls back to
     * the legacy foreground-label behaviour for this tick — §10:
     * the label survives as the fallback when the stack read
     * fails). Never throws.
     */
    // ---- v0.17.0: LIVE SESSION HOLD (design:
    // live-session-hold-design-2026-11-01) ----
    // A live voice/video call is pixel-indistinguishable from
    // the content the models convict (field incident 2026-11-01
    // 21:37: a WhatsApp video call took 4 full-screen strikes in
    // 7.2 s with the classifier at 0.79–0.99 throughout and was
    // quarantined 120 s mid-call). The separating signal is
    // context, not pixels — the phone knows a call is happening.
    // Two cheap signals, both readable with no new permission:
    //   (1) the AudioManager mode — MODE_IN_COMMUNICATION for
    //       any VoIP/video call app, MODE_IN_CALL for cellular;
    //   (2) call-surface scoping — the foreground activity class
    //       names a call surface (WhatsApp's voicecalling.*
    //       classes; generic "calling"/"voip" matches), with the
    //       mode-flip witness as the fallback leg.
    // Both must agree, debounced, before anything is held. While
    // held, the app STOPS judging the call (Alejandro's ruling,
    // overriding the design draft's keep-judging option): no
    // captures, no analyses for the held surface; the held
    // package's floating windows leave the judged stack; entry
    // and exit detection keep running on the signals alone. The
    // in-call residual (content arriving inside the call
    // surface) is accepted — it is held together with the call.

    /** Is the audio subsystem in a call mode right now? */
    private fun audioInCall(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return false
        val m = try { am.mode } catch (e: Exception) { return false }
        return m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_IN_CALL
    }

    /**
     * The scoping legs for the FOREGROUND package: does the
     * surface currently in front scope to a call surface? The
     * activity leg reads the foreground activity class (call
     * activities name themselves: voicecalling / videocalling /
     * voip); the witness leg is the fallback — the audio mode
     * flipped into a call mode while this exact package + class
     * was in front, and that continuity still holds. Returns the
     * CALLHOLD log's via string, or null when neither leg holds.
     * Meaningful only for the foreground package (the class
     * describes the foreground window); callers honour that.
     */
    private fun callSurfaceLegs(fgPkg: String): String? {
        if (fgPkg.isBlank()) return null
        val cls = foregroundClass
        val low = cls.lowercase()
        val act = low.contains("calling") || low.contains("voip")
        val wit = callFlipPkg != null && callFlipPkg == fgPkg &&
            !callFlipClass.isNullOrBlank() && cls == callFlipClass
        if (!act && !wit) return null
        return buildString {
            append("audio")
            if (act) append("+activity")
            if (wit) append("+witness")
        }
    }

    /** Is [pkg] the held package AND its call surface the
     *  foreground content right now (audio still in a call mode,
     *  scoping legs still holding)? The wholesale judging pause
     *  keys on this — it is re-evaluated every tick, so the
     *  pause lifts the moment the surface or the call ends. */
    private fun callSurfaceHeldNow(pkg: String): Boolean =
        callHoldPkg == pkg && audioInCall() && callSurfaceLegs(pkg) != null

    /** Clear the held package's conviction counters — the
     *  whole-frame pending (when it names this package) and its
     *  crop accumulator entry. Called on hold entry AND exit:
     *  a call can never bank strikes that convict the moment it
     *  ends, and judging after a call resumes from zero. */
    private fun resetStrikesAndPoints(pkg: String) {
        if (pendingBlockPkg == pkg) {
            pendingBlockPkg = null
            pendingHits = 0
            pendingLastWarmthMs = 0L
            pendingPeak = 0f
        }
        accByPkg.remove(pkg)
    }

    /**
     * The hold state machine, ridden on every tick (cheap: one
     * audio-mode read + the event-fed class label — no captures).
     * Arm: the audio mode is a call mode AND the foreground
     * surface scopes to a call, for CALLHOLD_ARM_CHECKS
     * consecutive ticks. Exit: CALLHOLD_EXIT_CHECKS consecutive
     * ticks where the audio mode is back to normal OR the held
     * package's surface is gone entirely (no window in the stack
     * sample and not foreground — a floating call window keeps
     * its package visible in the sample, so a PiP call never
     * trips this leg; [visiblePkgs] null = stack read failed =
     * unknown, never "gone").
     */
    private fun evalCallHold(now: Long, fgPkg: String, visiblePkgs: Set<String>?) {
        val inCall = audioInCall()
        // Witness bookkeeping: the flip into a call mode records
        // the surface in front at that moment; leaving call
        // modes clears the record.
        if (inCall && !audioWasInCall) {
            callFlipPkg = fgPkg.ifBlank { null }
            callFlipClass = foregroundClass.ifBlank { null }
        } else if (!inCall) {
            callFlipPkg = null
            callFlipClass = null
        }
        audioWasInCall = inCall
        val held = callHoldPkg
        if (held == null) {
            val legs = if (inCall) callSurfaceLegs(fgPkg) else null
            if (legs != null) {
                if (++callArmStreak >= CALLHOLD_ARM_CHECKS) {
                    callHoldPkg = fgPkg
                    callHoldSinceMs = now
                    callHoldVia = legs
                    callArmStreak = 0
                    callExitStreak = 0
                    resetStrikesAndPoints(fgPkg)
                    DebugLog.log(this, "CALLHOLD on pkg=$fgPkg via=$legs")
                    refreshOverlay()
                }
            } else {
                callArmStreak = 0
            }
            return
        }
        val surfaceGone = visiblePkgs != null && held !in visiblePkgs && fgPkg != held
        if (!inCall || surfaceGone) {
            if (++callExitStreak >= CALLHOLD_EXIT_CHECKS) {
                DebugLog.log(this, "CALLHOLD off pkg=$held dur=${(now - callHoldSinceMs) / 1000}s")
                resetStrikesAndPoints(held)
                callHoldPkg = null
                callHoldVia = ""
                callExitStreak = 0
                callArmStreak = 0
                refreshOverlay()
            }
        } else {
            callExitStreak = 0
        }
    }

    /**
     * Remove the held package's FLOATING windows from a sampled
     * stack's judged set (the PiP case: the call floats over
     * another app — its legs are skipped, everything else is
     * judged exactly as before). A fullscreen window of the held
     * package is NOT filtered: it is a foreground surface, and
     * the surface logic governs it (paused when it scopes to a
     * call, judged when it does not — chats opened mid-call stay
     * convictable, per the design's hole analysis). When the
     * filter empties the judged set over an exempt surface, the
     * sanity target is recomputed exactly as sampleStack derives
     * it, so the exempt surface keeps its sanity checks.
     */
    private fun applyCallHoldLegFilter(s: StackSnapshot): StackSnapshot {
        val held = callHoldPkg ?: return s
        if (s.judged.none { it.pkg == held }) return s
        val dispArea = s.dispW.toLong() * s.dispH.coerceAtLeast(1)
        val fsMin = (STACK_FULLSCREEN_FRAC * dispArea).toLong()
        val kept = s.judged.filter { w ->
            !(w.pkg == held && w.bounds.width().toLong() * w.bounds.height().toLong() < fsMin)
        }
        if (kept.size == s.judged.size) return s
        val area = kept.sumOf { it.bounds.width().toLong() * it.bounds.height().toLong() }
        val sanity = if (kept.isEmpty() && s.sanityPkg == null && !s.shadeUp) {
            s.appWindows.filter { it.pkg != null && it.exposed && isExemptWindowPkg(it.pkg!!) }
                .maxByOrNull { it.layer }?.pkg
        } else s.sanityPkg
        return s.copy(
            judged = kept,
            tileFrac = if (dispArea > 0) minOf(1.0, area.toDouble() / dispArea) else 0.0,
            covNotes = s.covNotes + "pkg=$held by=call-hold (v0.17.0)",
            sanityPkg = sanity
        )
    }

    private fun sampleStack(): StackSnapshot? {
        val wins: List<android.view.accessibility.AccessibilityWindowInfo>? = try {
            windows
        } catch (e: Exception) {
            failLogThrottled("stack", "other", "getWindows ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (wins.isNullOrEmpty()) return null
        val dm = resources.displayMetrics
        val dispW = dm.widthPixels
        val dispH = dm.heightPixels
        val dispArea = dispW.toLong() * dispH.coerceAtLeast(1)
        // Pass 1: raw geometry for every window (occlusion is a
        // property of the whole list, judgeable or not).
        class Raw(
            val info: android.view.accessibility.AccessibilityWindowInfo,
            val bounds: Rect, val layer: Int, val type: Int
        )
        val raws = ArrayList<Raw>(wins.size)
        for (w in wins) {
            val b = Rect()
            try { w.getBoundsInScreen(b) } catch (e: Exception) { continue }
            if (b.width() <= 0 || b.height() <= 0) continue
            raws.add(Raw(w, b, w.layer, w.type))
        }
        if (raws.isEmpty()) return null
        val seenIds = HashSet<Int>()
        // Package resolution for every APPLICATION window FIRST,
        // cached per window id + bounds (the expensive part;
        // bounds and layer are read fresh every sample, the root
        // lookup only on change). The exposure test below needs
        // occluders' packages too (a fullscreen SystemUI app
        // window is a shade-class occluder, v0.14.1), so
        // resolution can no longer live inside the build loop.
        // v0.14.3: SYSTEM windows resolve in the same pass —
        // the narrowed sys test needs the occluder's root
        // package for its SystemUI-identity leg (the shade's
        // root lives in com.android.systemui), and the WIN occ
        // line names it either way.
        val pkgById = HashMap<Int, String?>()
        for (raw in raws) {
            if (raw.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                raw.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
            ) continue
            val id = raw.info.id
            seenIds.add(id)
            val bKey = bstr(raw.bounds)
            val cached = winPkgCache[id]
            pkgById[id] = if (cached != null && cached.first == bKey) {
                cached.second
            } else {
                val resolved: String? = try {
                    val root = raw.info.root
                    val p = root?.packageName?.toString()
                    try { root?.recycle() } catch (_: Exception) {}
                    p
                } catch (e: Exception) {
                    // A window whose root cannot be read is an
                    // unresolvable window (D6) — logged in the STACK
                    // line as ?@bounds, never judged, never fatal.
                    null
                }
                winPkgCache[id] = bKey to resolved
                resolved
            }
        }
        // v0.14.1 EXPOSURE (the covered-pruning fix — diagnosis in
        // the file header). A window is pruned ONLY by a
        // qualifying occluder: another window at a higher reported
        // layer whose bounds fully contain it AND that is one of
        //   sys   — a TYPE_SYSTEM window that is positively the
        //           notification shade / an opaque fullscreen
        //           system surface (v0.14.3 narrowing below), or
        //           a fullscreen SystemUI application window
        //           (the shade rendered as an app window on some
        //           builds);
        //   appfs — a fullscreen application window over a
        //           fullscreen window: the top one IS the visible
        //           one, and grading the buried one would grade
        //           the top window's pixels under its name.
        // A FLOATING window (any app window below fullscreen
        // size) is NEVER pruned by an app window: the window
        // manager composites freeform / PiP surfaces above
        // fullscreen apps by construction, whatever the layer
        // integer or input focus says. Our own accessibility
        // overlays never occlude (they are not content), and IME /
        // divider / unknown classes never occlude either —
        // ambiguity resolves toward JUDGED. A kept floating
        // window that an app window contains is recorded for the
        // WIN cov-override line (logged on stack change, in
        // onStackSampled), as is every prune with its occluder.
        //
        // v0.14.3 NARROWING of the sys leg (diagnosis in the file
        // header): v0.14.1 pruned on ANY containing TYPE_SYSTEM
        // window, and a persistent OxygenOS system window — most
        // likely the PiP's own companion/frame — pruned the X
        // PiP for the whole second half of the field test. A
        // TYPE_SYSTEM window now prunes only when BOTH legs of
        // shadeClassSys hold: SystemUI identity (resolved root
        // package, or the shade's window title) AND coverage of
        // at least STACK_FULLSCREEN_FRAC of the display. The
        // companion/frame fails coverage by construction — its
        // bounds hug the one app window it frames, far from
        // fullscreen — and a system window whose identity cannot
        // be established fails the other leg; either way the
        // window stays JUDGED. Every TYPE_SYSTEM window that is
        // considered against a judgeable window (higher layer +
        // containment, either verdict) is recorded in occNotes
        // with its full identity for the WIN occ line.
        val fsMinArea = (STACK_FULLSCREEN_FRAC * dispArea).toLong()
        fun fsClass(b: Rect): Boolean = b.width().toLong() * b.height() >= fsMinArea
        // The v0.14.3 shade-class test for TYPE_SYSTEM occluders.
        fun shadeClassSys(occ: Raw, occPkg: String?): Boolean {
            val title = try { occ.info.title?.toString() } catch (e: Exception) { null }
            val identity = (occPkg != null && isSystemUiPkg(occPkg)) ||
                (title != null && title.contains(SHADE_TITLE_MARKER, ignoreCase = true))
            return identity && fsClass(occ.bounds)
        }
        // The WIN occ identity record for one system occluder
        // considered against one judgeable window.
        fun occNote(targetPkg: String?, occ: Raw, occPkg: String?, shade: Boolean, prune: Boolean): String {
            val title = try { occ.info.title?.toString() } catch (e: Exception) { null }
            val covPct = if (dispArea > 0) {
                occ.bounds.width().toLong() * occ.bounds.height() * 100 / dispArea
            } else 0L
            return "pkg=${targetPkg ?: "?"} occType=${occ.type} occTitle=${title ?: "-"} " +
                "occPkg=${occPkg ?: "-"} occBounds=${bstr(occ.bounds)} occLayer=${occ.layer} " +
                "occCov=${covPct}% shade=${if (shade) 1 else 0} " +
                "occVerdict=${if (prune) "prune" else "no-prune"}"
        }
        val appWindows = ArrayList<StackWindow>()
        val covNotes = ArrayList<String>()
        val overrideNotes = ArrayList<String>()
        val occNotes = ArrayList<String>()
        var judgedArea = 0L
        for (raw in raws) {
            if (raw.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val id = raw.info.id
            val pkg: String? = pkgById[id]
            val judgeable = pkg != null && !isExemptWindowPkg(pkg)
            var exposed = true
            var covBy: String? = null
            var overrideOcc: String? = null
            for (occ in raws) {
                if (occ === raw || occ.layer <= raw.layer) continue
                if (occ.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val o = occ.bounds
                if (!(o.left <= raw.bounds.left && o.top <= raw.bounds.top &&
                        o.right >= raw.bounds.right && o.bottom >= raw.bounds.bottom)
                ) continue
                // occ reports a higher layer and geometrically
                // contains this window. Whether it OCCLUDES is a
                // question of occ's class (v0.14.1), not geometry.
                // v0.14.3: system windows resolve through the
                // same pkgById pass now, so occPkg carries the
                // TYPE_SYSTEM occluder's root package too.
                val occPkg: String? = pkgById[occ.info.id]
                val occDesc = occPkg ?: when (occ.type) {
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "syswin"
                    else -> "type${occ.type}"
                }
                when {
                    occ.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> {
                        // v0.14.3: a system window prunes ONLY
                        // when positively shade-class (SystemUI
                        // identity AND >= 95% display coverage —
                        // shadeClassSys). Every consideration is
                        // recorded with the occluder's identity
                        // for the WIN occ line, either verdict.
                        // A non-shade system window — the PiP
                        // companion/frame class, or one whose
                        // identity cannot be established — is NO
                        // occluder at all: the window stays
                        // judged and the search continues (a real
                        // occluder may still be in the list).
                        val shade = shadeClassSys(occ, occPkg)
                        if (judgeable) occNotes.add(occNote(pkg, occ, occPkg, shade, shade))
                        if (shade) {
                            exposed = false; covBy = "sys occ=$occDesc"; break
                        }
                    }
                    occ.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> {
                        when {
                            occPkg != null && isSystemUiPkg(occPkg) && fsClass(o) -> {
                                // The shade rendered as a
                                // fullscreen SystemUI app window:
                                // a sys-class prune — record the
                                // occluder's identity too (v0.14.3).
                                if (judgeable) occNotes.add(occNote(pkg, occ, occPkg, true, true))
                                exposed = false; covBy = "sys occ=$occDesc"; break
                            }
                            fsClass(o) && fsClass(raw.bounds) -> {
                                exposed = false; covBy = "appfs occ=$occDesc"; break
                            }
                            // An app window contains this floating
                            // window — v0.14.0 pruned here, on the
                            // layer integer alone. The window is
                            // composited visibly; keep it judged
                            // and remember the near-miss.
                            overrideOcc == null -> overrideOcc = occDesc
                        }
                    }
                    // IME / split divider / unknown window classes
                    // never occlude (ambiguity → judged).
                    else -> Unit
                }
            }
            if (!exposed && judgeable && covBy != null) covNotes.add("pkg=$pkg by=$covBy")
            if (exposed && judgeable && overrideOcc != null) overrideNotes.add("pkg=$pkg occ=$overrideOcc")
            val area = raw.bounds.width().toLong() * raw.bounds.height()
            val small = area.toDouble() < STACK_SMALL_AREA_FRAC * dispArea ||
                minOf(raw.bounds.width(), raw.bounds.height()) < STACK_SMALL_MIN_SIDE_PX
            val sw = StackWindow(
                id = id, pkg = pkg, bounds = Rect(raw.bounds), layer = raw.layer,
                focused = raw.info.isFocused, judgeable = judgeable, exposed = exposed,
                small = small, tier = if (pkg != null) tierOf(pkg) else Prefs.TIER_NORMAL
            )
            appWindows.add(sw)
            if (sw.judged) judgedArea += area
        }
        winPkgCache.keys.retainAll(seenIds)
        // v0.14.5: the shade state and the sanity target. The
        // shade is up when any TYPE_SYSTEM window is positively
        // shade-class (the same shadeClassSys test the exposure
        // pass applies) or a fullscreen SystemUI APPLICATION
        // window is present (the shade rendered as an app
        // window). The sanity target — only meaningful when the
        // judged set is empty — is the topmost exposed exempt
        // app window; the shade suppresses it outright.
        var shadeUp = false
        for (raw in raws) {
            if (raw.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                shadeClassSys(raw, pkgById[raw.info.id])
            ) { shadeUp = true; break }
        }
        if (!shadeUp) {
            shadeUp = appWindows.any { w -> w.pkg != null && isSystemUiPkg(w.pkg!!) && fsClass(w.bounds) }
        }
        val sanityPkg = if (shadeUp) null else appWindows
            .filter { it.pkg != null && it.exposed && isExemptWindowPkg(it.pkg!!) }
            .maxByOrNull { it.layer }?.pkg
        // Windows are recycled by the platform docs' contract for
        // obtained objects; getWindows' list items themselves are
        // NOT recycled (the framework manages them) — only roots
        // (above) are.
        val judged = appWindows.filter { it.judged }
        return StackSnapshot(
            tsMs = System.currentTimeMillis(),
            appWindows = appWindows,
            judged = judged,
            dispW = dispW, dispH = dispH,
            tileFrac = if (dispArea > 0) minOf(1.0, judgedArea.toDouble() / dispArea) else 0.0,
            covNotes = covNotes,
            overrideNotes = overrideNotes,
            occNotes = occNotes,
            sanityPkg = sanityPkg,
            shadeUp = shadeUp
        )
    }

    /** The STACK line signature: the judged set + the unjudged app
     *  windows' descriptors. Bounds ride along — a moved PiP is a
     *  changed set (its crops move with it). */
    private fun stackSig(s: StackSnapshot): String {
        val j = s.judged.joinToString(";") { "${it.pkg}@${bstr(it.bounds)}" }
        val u = s.appWindows.filter { !it.judged }.joinToString(";") { w ->
            when {
                w.pkg != null && isSettingsPkg(w.pkg) -> "settings"
                w.pkg == null -> "?@${bstr(w.bounds)}"
                w.judgeable && !w.exposed -> "${w.pkg}@${bstr(w.bounds)}(cov)"
                else -> "${w.pkg}@${bstr(w.bounds)}"
            }
        }
        // v0.14.5: the sanity target rides the signature — two
        // exempt windows trading the top layer is a changed set
        // (the sanity captures retarget) even when the judged
        // and unjudged descriptors stand still.
        return "$j|$u" + if (s.judged.isEmpty()) "|sanity=${s.sanityPkg ?: "-"}" else ""
    }

    /**
     * Fold one fresh stack sample into the service state: the
     * STACK line on judged-set change, the §3 hysteresis on the
     * stack price, the visible-SUS cache intervalMs reads, and the
     * per-package evidence pruning (an accumulator whose window
     * left the screen dies with it; the window ZoD lock likewise).
     */
    private fun onStackSampled(s: StackSnapshot) {
        currentStack = s
        val sig = stackSig(s)
        if (sig != lastStackSig) {
            lastStackSig = sig
            val j = s.judged.joinToString(",") { "${it.pkg}(${tierName(it.tier)})@${bstr(it.bounds)}" }
            val u = s.appWindows.filter { !it.judged }.joinToString(",") { w ->
                when {
                    w.pkg != null && isSettingsPkg(w.pkg) -> "settings"
                    w.pkg == null -> "?@${bstr(w.bounds)}"
                    w.judgeable && !w.exposed -> "${w.pkg}(cov)"
                    else -> "${w.pkg}"
                }
            }
            // v0.14.5: an exempt-only stack names its sanity
            // target on the STACK line — the field log must show
            // the sanity captures for what they are, not as a
            // judging anomaly.
            val sanityMark = if (s.judged.isEmpty() && s.sanityPkg != null) " sanity=${s.sanityPkg}" else ""
            DebugLog.log(this, "STACK judged=[$j]$sanityMark unjudged=[$u] top=${s.topPkg ?: "-"}")
            // v0.14.1: the coverage decisions behind this stack,
            // logged once per stack change — every prune names
            // its occluder and its class, every floating window
            // kept against app-window containment names the app
            // window it floats over.
            for (n in s.covNotes) DebugLog.log(this, "WIN cov $n")
            for (n in s.overrideNotes) DebugLog.log(this, "WIN cov-override $n — floating window contained by an app window; judged, not pruned (v0.14.1)")
        }
        // v0.14.3: the system-occluder considerations behind
        // this stack (WIN occ) — emitted whenever the
        // CONSIDERATION SET changes, which is not the same
        // trigger as the judged-set change above: a system
        // window can start (or stop) containing a judged window
        // while the judged set stands still, and the field
        // diagnosis needs the occluder named in both cases.
        // Every line carries the occluder's identity and its
        // shade-class verdict, so a prune that survives the
        // narrowing is attributable from the log alone.
        val occSig = s.occNotes.joinToString(";")
        if (occSig != lastOccSig) {
            lastOccSig = occSig
            for (n in s.occNotes) DebugLog.log(this, "WIN occ $n")
        }
        // §3 hysteresis on the bottom-rung price.
        // v0.14.5: an exempt-only stack prices at the SYSTEM
        // sanity price (systemPriceMs, ceiling-clamped) so
        // intervalMs' bottom rung — and the governor blind arm's
        // interval-aware bar reading it — see the sanity price,
        // not the foreground label's tier price.
        val raw = s.judged.minOfOrNull { tierIntervalMs(it.pkg!!) }
            ?: s.sanityPkg?.let { systemPriceMs() }
        if (raw == null) {
            stackPricedMs = null
            stackSlowStreak = 0
        } else {
            val cur = stackPricedMs
            when {
                cur == null -> { stackPricedMs = raw; stackSlowStreak = 0 }
                raw < cur -> { stackPricedMs = raw; stackSlowStreak = 0 } // hotter joins: instant
                raw > cur -> {
                    if (++stackSlowStreak >= 2) { stackPricedMs = raw; stackSlowStreak = 0 }
                }
                else -> stackSlowStreak = 0
            }
        }
        // v0.14.5: a trip window on the SANITY target densifies
        // the screen too (cadence follows visibility) — the
        // sanity path's whole point is that a hot exempt
        // surface is watched at 1 s while it lasts.
        visibleSusCached = s.judged.any { tripSusActive(it.pkg!!) } ||
            (s.sanityPkg != null && tripSusActive(s.sanityPkg))
        // Evidence pruning: accumulators belong to visible windows.
        val judgedPkgs = s.judged.map { it.pkg!! }.toSet()
        accByPkg.keys.retainAll(judgedPkgs)
        // The window ZoD lock dies when its window leaves the stack.
        val lp = zodWinLockPkg
        if (lp != null && lp !in judgedPkgs) clearZodWinLock("win-gone")
    }

    /** IoU of two pixel rects (the window-lock relocation test). */
    private fun rectIou(a: Rect, b: Rect): Double {
        val il = maxOf(a.left, b.left); val it = maxOf(a.top, b.top)
        val ir = minOf(a.right, b.right); val ib = minOf(a.bottom, b.bottom)
        if (ir <= il || ib <= it) return 0.0
        val inter = (ir - il).toDouble() * (ib - it)
        val union = a.width().toDouble() * a.height() + b.width().toDouble() * b.height() - inter
        return if (union <= 0) 0.0 else inter / union
    }

    // ---- v0.12.2: ZOOM-ON-DISAGREEMENT (design build spec §5) ----
    // The disagreement burst, the aim lock, and its exits. Zoom
    // verdicts reach conviction ONLY through the existing crop
    // accumulator (handleInstantSignal, with zoom-sourced points
    // capped at ZOD_ZOOM_POINT_CAP per analysis); an un-locked
    // burst's verdicts are diagnostic only (ZOOM line + the graph's
    // falZ series) and are never scored, so there is exactly one
    // crop-evidence stream per analysis and nothing double-counts.

    /** Is the aim lock live for [pkg] right now? */
    private fun zodLockedFor(pkg: String): Boolean =
        zodLockBoxFrac != null && zodLockPkg == pkg

    /**
     * Release the aim lock (if any) and log the unlock once.
     * [why] is cooled | reloc | timeout | pkgchange | block
     * (design §3.3 / §5.5). A cooled unlock also starts the
     * fruitless cooldown for the lock's package — the only exit
     * that does (the design's cooldown list names fruitless
     * bursts and cooled unlocks; timeout/reloc/pkgchange/block
     * re-arm ZoD immediately).
     */
    private fun clearZodLock(why: String) {
        val p = zodLockPkg ?: return
        val had = zodLockBoxFrac != null
        zodLockBoxFrac = null
        zodLockPkg = null
        zodLockParent = null
        zodLockRung = null
        zodLockExpiryMs = 0L
        zodLockCold = 0
        if (!had) return
        DebugLog.log(this, "ZOOM unlock pkg=$p why=$why")
        if (why == "cooled") {
            zodCoolPkg = p
            zodCoolUntilMs = System.currentTimeMillis() + ZOD_COOL_FRUITLESS_MS
        }
    }

    /** IoU of two tracker cell boxes, 0..1 (the relocation test). */
    private fun zodIou(a: MotionRegionTracker.Box, b: MotionRegionTracker.Box): Double {
        val il = maxOf(a.l, b.l); val it = maxOf(a.t, b.t)
        val ir = minOf(a.r, b.r); val ib = minOf(a.b, b.b)
        if (ir <= il || ib <= it) return 0.0
        val inter = (ir - il) * (ib - it)
        val union = a.areaCells + b.areaCells - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }

    /** Tracker-box percent string, the REGION lines' style (for the ZOOM line's box=). */
    private fun zodBoxStr(box: MotionRegionTracker.Box): String =
        "[${"%.1f".format(box.l * 100.0 / MotionRegionTracker.COLS)},${"%.1f".format(box.t * 100.0 / MotionRegionTracker.ROWS)},${"%.1f".format(box.r * 100.0 / MotionRegionTracker.COLS)},${"%.1f".format(box.b * 100.0 / MotionRegionTracker.ROWS)}]"

    /** Crop [bmp] to bitmap fractions [l,t,r,b] (clamped, min 1 px) — the locked / zoom boxes' coordinate space. */
    private fun cropToFrac(bmp: Bitmap, frac: FloatArray): Bitmap? {
        val l = (frac[0] * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val t = (frac[1] * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val r = (frac[2] * bmp.width).toInt().coerceIn(l + 1, bmp.width)
        val b = (frac[3] * bmp.height).toInt().coerceIn(t + 1, bmp.height)
        return try {
            Bitmap.createBitmap(bmp, l, t, r - l, b - t)
        } catch (e: Exception) {
            // Same discipline as cropToRegion: a failed crop is
            // marked (throttled — this can run per analysis), never
            // swallowed; the rung it fed counts as ERR in the ZOOM line.
            failLogThrottled("zodcrop", "other", "zoom crop ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** The locked box (bitmap fractions) as a tracker cell box — the region outline's coordinate space (cosmetic approximation for the AC rungs, exact for MB). */
    private fun zodFracToCellBox(frac: FloatArray): MotionRegionTracker.Box {
        val l = (frac[0] * MotionRegionTracker.COLS).toInt().coerceIn(0, MotionRegionTracker.COLS - 1)
        val t = (frac[1] * MotionRegionTracker.ROWS).toInt().coerceIn(0, MotionRegionTracker.ROWS - 1)
        val r = (frac[2] * MotionRegionTracker.COLS).toInt().coerceIn(l + 1, MotionRegionTracker.COLS)
        val b = (frac[3] * MotionRegionTracker.ROWS).toInt().coerceIn(t + 1, MotionRegionTracker.ROWS)
        return MotionRegionTracker.Box(l, t, r, b)
    }

    /**
     * An AC rung window (design §3.2, bench geometry): the tracker
     * box shrunk by [scale] around the motion centroid, its height
     * then capped at [ZOD_AC_CAP_HW] × its width — the aspect
     * correction, without which a shrunken tall box reads ~0.00.
     * Computed in pixel space against [bmp], clamped inside the
     * frame, returned as bitmap fractions.
     */
    private fun zodAcWindow(
        bmp: Bitmap, parent: MotionRegionTracker.Box,
        centroidCol: Float, centroidRow: Float, scale: Double
    ): FloatArray {
        val bw = bmp.width.toDouble(); val bh = bmp.height.toDouble()
        val pw = parent.w * bw / MotionRegionTracker.COLS
        val ph = parent.h * bh / MotionRegionTracker.ROWS
        val cx = centroidCol * bw / MotionRegionTracker.COLS
        val cy = centroidRow * bh / MotionRegionTracker.ROWS
        val w = pw * scale
        var h = ph * scale
        if (h > ZOD_AC_CAP_HW * w) h = ZOD_AC_CAP_HW * w
        val x0 = (cx - w / 2).coerceIn(0.0, maxOf(0.0, bw - w))
        val y0 = (cy - h / 2).coerceIn(0.0, maxOf(0.0, bh - h))
        return floatArrayOf(
            (x0 / bw).toFloat(), (y0 / bh).toFloat(),
            ((x0 + w) / bw).toFloat(), ((y0 + h) / bh).toFloat()
        )
    }

    /**
     * The ZoD burst (design §3.1–§3.3): evaluated on a crop-served
     * analysis with no lock active. Applies every trigger gate;
     * when they all hold, runs the ladder (MB → AC-0.5 → AC-0.4,
     * stop at the first verdict >= [ZOD_WARM], max 3 passes, each
     * inside the rolling budget) against the analysis bitmap via
     * the existing [falScore] path — zoom passes live inside the
     * analysis slot and reuse its bitmap, never extra captures.
     * Logs exactly one ZOOM line per burst. Returns (best verdict,
     * locking verdict): in LIVE mode a rung >= [ZOD_LOCK_BAR] sets
     * the aim lock and its verdict is returned for the caller to
     * score as this analysis's falC; in SHADOW mode the lock is
     * computed and logged (wouldLock=) but never set or scored.
     * A null/null return means no burst ran (a gate failed, the
     * budget was spent, or no motion data could aim a rung).
     */
    private fun runZodBurst(
        bmp: Bitmap, pkg: String, parentBox: MotionRegionTracker.Box,
        fal: FalconModel, falFull: Float, falCropIn: Float,
        clsFull: Float?, clsCrop: Float?
    ): Pair<Float?, Float?> {
        val none: Pair<Float?, Float?> = null to null
        // --- trigger gates (design §3.1) ---
        if (falCropIn >= ZOD_FALC_MAX) return none // Falconsai's crop is not cold — no disagreement
        val bar = clsTripBar(pkg)
        val clsHot = (clsCrop != null && clsCrop >= bar) || (clsFull != null && clsFull >= bar)
        if (!clsHot) return none // the classifier does not disagree
        if (clsRefractoryActive(pkg)) return none // the tripwire is muted for this package
        // Inside suspicion: a live window (trip or post-block), or
        // tripping on this very analysis — a Falconsai verdict
        // >= SUS_BAR, or the classifier heat above (noteSusCls
        // fires on it below; the refractory gate already passed).
        if (!(susActive(pkg) || falFull >= SUS_BAR || clsHot)) return none
        val now0 = System.currentTimeMillis()
        if (zodCoolPkg == pkg && now0 < zodCoolUntilMs) return none // per-package cooldown
        // --- aim data: the latest interval's span; the recent-motion centroid is the AC fallback ---
        val span = try {
            regionTracker.lastIntervalSpan()
        } catch (e: Exception) {
            if (!zodTrackerErrorLogged) {
                zodTrackerErrorLogged = true
                failLog("other", "zod span ${e.javaClass.simpleName}: ${e.message} (ZoD aim degraded)")
            }
            null
        }
        val centroid: Pair<Float, Float>? = span?.let { it.centroidCol to it.centroidRow }
            ?: try {
                regionTracker.recentCentroidIn(parentBox)
            } catch (e: Exception) {
                if (!zodTrackerErrorLogged) {
                    zodTrackerErrorLogged = true
                    failLog("other", "zod centroid ${e.javaClass.simpleName}: ${e.message} (ZoD aim degraded)")
                }
                null
            }
        if (span == null && centroid == null) return none // nothing to aim with — no burst, no cooldown
        // --- ladder construction (design §3.2) ---
        class Rung(val name: String, val frac: FloatArray)
        val rungs = ArrayList<Rung>(3)
        if (span != null && span.cells >= ZOD_MIN_CELLS) {
            val mb = span.box
            val mbScreenFrac = mb.areaCells.toDouble() / MotionRegionTracker.CELLS
            val inside = mb.l >= parentBox.l && mb.t >= parentBox.t && mb.r <= parentBox.r && mb.b <= parentBox.b
            if (inside && mbScreenFrac >= ZOD_MB_MIN_SCREEN_FRAC && mbScreenFrac <= ZOD_MB_MAX_SCREEN_FRAC &&
                mb.areaCells.toDouble() <= ZOD_MB_MAX_PARENT_FRAC * parentBox.areaCells
            ) {
                rungs.add(
                    Rung(
                        "MB", floatArrayOf(
                            mb.l.toFloat() / MotionRegionTracker.COLS, mb.t.toFloat() / MotionRegionTracker.ROWS,
                            mb.r.toFloat() / MotionRegionTracker.COLS, mb.b.toFloat() / MotionRegionTracker.ROWS
                        )
                    )
                )
            }
        }
        if (centroid != null) {
            val parentFrac = parentBox.areaCells.toDouble() / MotionRegionTracker.CELLS
            for ((name, s) in listOf("AC05" to 0.5, "AC04" to 0.4)) {
                val f = zodAcWindow(bmp, parentBox, centroid.first, centroid.second, s)
                val winFrac = (f[2] - f[0]).toDouble() * (f[3] - f[1]).toDouble()
                if (winFrac <= ZOD_MB_MAX_PARENT_FRAC * parentFrac) rungs.add(Rung(name, f))
            }
        }
        if (rungs.isEmpty()) return none
        // --- run the ladder ---
        var best: Float? = null
        var lockName: String? = null
        var lockFrac: FloatArray? = null
        var lockVerdict: Float? = null
        val rungLog = ArrayList<String>(3)
        for (rung in rungs) {
            // Rolling budget, checked per rung: a burst stops
            // mid-ladder when the minute's zoom passes are spent.
            val nowB = System.currentTimeMillis()
            while (zodPassTimes.isNotEmpty() && nowB - zodPassTimes.first() > 60_000L) zodPassTimes.removeFirst()
            if (zodPassTimes.size >= ZOD_PASS_BUDGET_PER_MIN) break
            val rb = cropToFrac(bmp, rung.frac)
            if (rb == null) {
                rungLog.add("${rung.name}:ERR")
                continue
            }
            zodPassTimes.addLast(System.currentTimeMillis())
            val v = falScore(fal, rb) // a failure failLogs + latches inside falScore; null counts as ERR/cold here
            rb.recycle()
            if (v == null) {
                rungLog.add("${rung.name}:ERR")
                continue
            }
            rungLog.add("${rung.name}:${"%.3f".format(v)}")
            if (best == null || v > best) best = v
            if (v >= ZOD_LOCK_BAR) {
                lockName = rung.name
                lockFrac = rung.frac
                lockVerdict = v
                break
            }
            if (v >= ZOD_WARM) break
        }
        if (rungLog.isEmpty()) return none // budget spent before the first rung — no burst happened
        // --- outcome: lock (LIVE) / cooldowns / the one ZOOM line ---
        val now2 = System.currentTimeMillis()
        var coolMs = 0L
        if (lockVerdict != null && ZOD_MODE_LIVE) {
            zodLockBoxFrac = lockFrac
            zodLockPkg = pkg
            zodLockParent = parentBox
            zodLockRung = lockName
            zodLockExpiryMs = now2 + ZOD_LOCK_EXPIRY_MS
            zodLockCold = 0
            regionVisBox = zodFracToCellBox(lockFrac!!) // the outline follows the lock at once
        } else if (lockVerdict == null) {
            coolMs = if (best != null && best >= ZOD_WARM) ZOD_COOL_WARM_MS else ZOD_COOL_FRUITLESS_MS
            zodCoolPkg = pkg
            zodCoolUntilMs = now2 + coolMs
        }
        val f2 = { v: Float? -> v?.let { "%.2f".format(it) } ?: "-" }
        val sb = StringBuilder()
        sb.append(if (ZOD_MODE_LIVE) "ZOOM" else "ZOOM(shadow)")
        sb.append(" pkg=$pkg trigClsC=${f2(clsCrop)} trigClsF=${f2(clsFull)} falC=${f2(falCropIn)}")
        sb.append(" box=${zodBoxStr(parentBox)} rungs=${rungLog.joinToString(",")}")
        if (ZOD_MODE_LIVE) sb.append(" lock=${lockName ?: "none"}")
        else sb.append(" wouldLock=${lockName ?: "none"}")
        if (lockName == null) sb.append(" cool=${coolMs / 1000}s")
        DebugLog.log(this, sb.toString())
        return best to (if (ZOD_MODE_LIVE) lockVerdict else null)
    }

    /**
     * Aim-lock exit evaluation (design §5.4) — called at the end of
     * handleInstantSignal, after the analysis is scored: a fresh
     * lock-served verdict >= [ZOD_WARM] refreshes the lock's expiry
     * and clears its cold count; a fresh lock-served verdict below
     * [ZOD_FALC_MAX] counts toward the cooled release
     * ([ZOD_LOCK_COLD_RELEASE] in a row, which also starts the
     * fruitless cooldown — see clearZodLock); and the time-based
     * expiry ([ZOD_LOCK_EXPIRY_MS] without a >= ZOD_WARM verdict)
     * is checked on every signal. Relocation, package change and
     * blocks clear the lock at their own sites.
     */
    private fun zodAfterScored(pkg: String, fresh: Boolean, falCrop: Float?, cropZoomSourced: Boolean) {
        if (!zodLockedFor(pkg)) return
        val now = System.currentTimeMillis()
        if (fresh && cropZoomSourced && falCrop != null) {
            when {
                falCrop >= ZOD_WARM -> {
                    zodLockExpiryMs = now + ZOD_LOCK_EXPIRY_MS
                    zodLockCold = 0
                }
                falCrop < ZOD_FALC_MAX -> {
                    zodLockCold++
                    if (zodLockCold >= ZOD_LOCK_COLD_RELEASE) {
                        clearZodLock("cooled")
                        return
                    }
                }
                else -> zodLockCold = 0
            }
        }
        if (now > zodLockExpiryMs) clearZodLock("timeout")
    }

    // ---- v0.11.9: GT interval open/close (instrument only) ----

    /** Open a ground-truth interval for [pkg] (GT button tap with no
     *  interval open) and log GT START. */
    private fun openGt(pkg: String) {
        gtPkg = pkg
        gtStartMs = System.currentTimeMillis()
        gtPeakFalF = 0f
        gtPeakFalC = 0f
        gtPeakClsF = 0f
        gtPeakClsC = 0f
        gtFirstHotElapsedMs = -1L
        gtSusElapsedMs = -1L
        gtForeignTicks = 0
        DebugLog.log(this, "GT START pkg=$pkg")
        refreshGtButton()
    }

    /**
     * Close the open GT interval (if any) and log ONE GT END summary
     * line. [end] is tap | block | pkgchange | timeout; a block close
     * is invoked from the top of executeBlock, so the line lands right
     * after the caller's INSTANT BLOCK line. The susIn token rides
     * along only when suspicion tripped during the interval — the time
     * to SUS is tracked per the instrument's design even though the
     * headline fields are the peaks and firstHot.
     */
    private fun closeGt(end: String) {
        if (gtPkg == null) return
        val elapsedMs = System.currentTimeMillis() - gtStartMs
        val secs = { ms: Long -> "%.1f".format(ms / 1000.0) }
        val sb = StringBuilder()
        sb.append("GT END dur=${secs(elapsedMs)}s blocked=${if (end == "block") "yes" else "no"}")
        if (end == "block") sb.append(" blockIn=${secs(elapsedMs)}s")
        if (gtFirstHotElapsedMs >= 0L) sb.append(" firstHot=${secs(gtFirstHotElapsedMs)}s") else sb.append(" firstHot=never")
        if (gtSusElapsedMs >= 0L) sb.append(" susIn=${secs(gtSusElapsedMs)}s")
        sb.append(" peakFalF=${"%.2f".format(gtPeakFalF)} peakFalC=${"%.2f".format(gtPeakFalC)}")
        sb.append(" peakClsF=${"%.2f".format(gtPeakClsF)} peakClsC=${"%.2f".format(gtPeakClsC)}")
        sb.append(" end=$end")
        gtPkg = null
        gtForeignTicks = 0
        DebugLog.log(this, sb.toString())
        refreshGtButton()
    }

    /** v0.11.9: note the time-to-SUS for an open GT interval on [pkg]
     *  (first trip only; called from noteSus / noteSusCls on an actual
     *  trip — never from a refractory-dropped one). */
    private fun noteGtSus(pkg: String) {
        if (gtPkg == pkg && gtSusElapsedMs < 0L) {
            gtSusElapsedMs = System.currentTimeMillis() - gtStartMs
        }
    }

    /**
     * v0.7.1: a suspicious frame was seen in [pkg] — hold max cadence for
     * Prefs.attentionMs and pull the already-scheduled next capture in.
     * The schedule for the NEXT capture is normally fixed before this
     * frame is even analysed, so without the pull-in the burst would only
     * start after the relaxed 5 s / 15 s idle wait it exists to skip.
     */
    private fun noteSuspicion(pkg: String, reason: String) {
        val now = System.currentTimeMillis()
        val newly = attentionPkg != pkg || now > attentionUntilMs
        attentionPkg = pkg
        attentionUntilMs = now + Prefs.attentionMs(this)
        if (newly) DebugLog.log(this, "ATTENTION pkg=$pkg reason=$reason — max cadence for ${Prefs.attentionMs(this) / 1000}s")
        pullNextCaptureIn(now)
    }

    /** Bring the next scheduled capture forward to one warning interval from now, if it is further out. */
    private fun pullNextCaptureIn(now: Long = System.currentTimeMillis()) {
        val target = now + warningIntervalMs
        if (nextCaptureDueMs > target) nextCaptureDueMs = target
    }

    /** Refresh the dynamically-resolved HOME (launcher) package set, at most once a minute. */
    private fun refreshHomePackages(now: Long) {
        if (homePackages.isNotEmpty() && now - homePackagesCheckedMs < 60_000L) return
        homePackagesCheckedMs = now
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val found = packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
            val def = packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            homePackages = if (def != null) found + def else found
        } catch (e: Exception) {
            // v0.11.14: was a bare swallow — a failed HOME query left
            // the launcher set empty (launcher judged as a normal
            // app) with no trace. Marked now; the ≤1/min refresh
            // guard above bounds its rate.
            failLog("other", "home-packages query ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** v0.10.10: the Settings half of the v0.7.1 exemption — unchanged
     *  and absolute (OEM Settings package names vary slightly). */
    private fun isSettingsPkg(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg in SKIP_SETTINGS || pkg == "com.android.settings" || pkg.startsWith("com.android.settings.")
    }

    /** v0.10.10: is this the default launcher? Resolved dynamically
     *  from the HOME intent (every HOME-capable package plus the
     *  current default), with known launcher names and the .launcher
     *  suffix as fallbacks — never a hardcoded OEM package. */
    private fun isLauncherPkg(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        refreshHomePackages(System.currentTimeMillis())
        return pkg in homePackages || pkg in KNOWN_LAUNCHERS || pkg.endsWith(".launcher")
    }

    /** v0.7.1 union of the two exemptions above (kept for callers that
     *  still treat launcher + Settings alike). */
    private fun isLauncherOrSettings(pkg: String?): Boolean =
        isSettingsPkg(pkg) || isLauncherPkg(pkg)

    /** The package of the window that is ACTUALLY active right now, asked fresh. */
    private fun activeWindowPkg(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (e: Exception) {
        // v0.11.14: was a bare swallow on a per-tick path — marked
        // via the throttled channel (a dead window query means the
        // foreground opinion can freeze, the 2026-10-28 Gallery
        // failure shape; it must leave a trace, not a flood).
        failLogThrottled("winpkg", "other", "active-window query ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    // v0.2.3 poller: ticks wake at least every POLL_MS to re-read the active
    // window, but screenshots are taken only when nextCaptureDueMs has
    // passed. v0.2.2 waited out the PREVIOUS package's whole interval after
    // an app switch (field log 2026-10-25 22:04: returning to X waited out
    // the launcher's 5 s) — part of the 3-7 s first-detection delay.
    @Volatile private var nextCaptureDueMs = 0L
    private var lastHeartbeatMs = 0L
    private val tickRunnable = Runnable {
        try {
            tick()
        } catch (e: Exception) {
            // v0.2.5: the loop must never die silently. Field report on
            // v0.2.4 (2026-10-25 23:24): "no blocks, no frame, nothing" —
            // and no trace in the exported log, because a tick that throws
            // before re-posting itself ends the chain forever while
            // accessibility events keep flowing. Log the crash INTO the
            // debug log and re-arm (scheduleNext dedupes pending callbacks,
            // so the normal paths stay single-scheduled).
            try {
                failLog("other", "tick crash ${e.javaClass.simpleName}: ${e.message} — loop re-armed")
            } catch (_: Exception) {
                // Guards the failure logger itself — unloggable by
                // construction, the one deliberate bare swallow left
                // (v0.11.14 audit, release notes).
            }
        }
        // v0.4.0: re-arm UNCONDITIONALLY (was: only on crash, inside tick's
        // branches, and inside the screenshot callbacks). Field log
        // 2026-10-26: at 01:10:27 a takeScreenshot on com.facebook.aura
        // never called back, no branch re-armed, and the guard sat blind
        // for 22 minutes while accessibility events kept flowing. With the
        // loop always re-armed, the busy watchdog in tick() can actually
        // fire and unwedge a hung capture. scheduleNext dedupes, so this
        // is safe on every path.
        scheduleNext()
    }

    private fun scheduleNext() {
        mainHandler.removeCallbacks(tickRunnable)
        val wait = (nextCaptureDueMs - System.currentTimeMillis()).coerceIn(200L, POLL_MS)
        mainHandler.postDelayed(tickRunnable, wait)
    }

    private var lastWinPkg: String? = null

    // ---- v0.13.0: device capability — governor + benchmark ----
    // (device-capability-design-2026-10-29.)

    /** Record one capture outcome (either consumer) into the
     *  governor's rolling window: last GOV_WINDOW_ATTEMPTS attempts,
     *  pruned to a GOV_WINDOW_SPAN_MS span. Called from the capture
     *  callbacks only — never blocks, never throws outward. */
    private fun govNoteCapture(ok: Boolean, code: Int) {
        val now = System.currentTimeMillis()
        govWindow.addLast(GovSample(now, ok, code))
        while (govWindow.size > GOV_WINDOW_ATTEMPTS) govWindow.removeFirst()
        while (govWindow.isNotEmpty() && now - govWindow.first().tsMs > GOV_WINDOW_SPAN_MS) govWindow.removeFirst()
    }

    private fun govWindowStats(): Triple<Int, Int, Int> {
        // (attempts, failures excluding code 6, code-3 failures)
        var fails = 0
        var code3 = 0
        for (s in govWindow) {
            if (!s.ok && s.code != 6) {
                fails++
                if (s.code == 3) code3++
            }
        }
        return Triple(govWindow.size, fails, code3)
    }

    private fun perfMedian(values: List<Long>): Long {
        if (values.isEmpty()) return -1L
        val s = values.sorted()
        return s[s.size / 2]
    }

    private fun perfP90(values: List<Long>): Long {
        if (values.isEmpty()) return -1L
        val s = values.sorted()
        return s[minOf(s.size - 1, (s.size * 9) / 10)]
    }

    /**
     * THE GOVERNOR (capability design §2). Runs once per ALIVE
     * heartbeat (~15 s), reads only records the guard already keeps,
     * and answers one question: is this phone failing to sustain the
     * chosen cadence? Three arms — capture-failure ratio (sustained
     * across two evaluations), the blindness fast trigger, and the
     * latency arm. A de-rate moves ONE rung, persists, and is never
     * undone automatically. Episodes are never de-rated: this
     * function only ever moves the rung that clamps IDLE tier
     * intervals (tierIntervalMs).
     *
     * v0.14.2: the blindness arm is sentinel-safe (it evaluates only
     * a real age of the current judged context — never-analysed and
     * just-switched states are a grace) and interval-aware (its bar
     * is max(30 s, 2× the interval the current pricing schedules)),
     * after the v0.13.0–v0.14.1 trigger read the never-analysed
     * sentinel as infinite blindness and self-locked at FLOOR
     * (field 2026-10-30). Every rung change this function makes is
     * logged in the GOV rung=<from>-><to> why=<arm> format.
     *
     * v0.14.4: the blindness arm also SUSTAINS — it de-rates only
     * when its condition has held across GOV_BLIND_STREAK_EVALS
     * consecutive evaluations spanning at least
     * GOV_BLIND_STREAK_SPAN_MS of wall time (Alejandro's ruling,
     * 2026-10-30: the governor "should see a lot of evidence that
     * the phone cannot run at the current cadence in order to
     * throttle"). The ratio arm already required sustained evidence
     * (two evaluations + dwell); the blind arm fired on a single
     * heartbeat and was the hair trigger of the three. The BLIND
     * overlay readout is a different path and is untouched.
     */
    private fun evaluateGovernor(now: Long, fgPkg: String) {
        if (!govReady) {
            // Warm-up grace: no action until the first successful
            // analysis AND one full heartbeat have been seen — a
            // booting service must not convict its own phone.
            if (heartbeatSeen && lastAnalysisOkMs > 0L) govReady = true else return
        }
        if (govResting) return
        if (benchActive) {
            // v0.14.4: no governor evidence carries across the
            // measurement in charge — the streak restarts after it.
            govBlindStreak = 0
            govBlindStreakCtx = null
            govBlindStreakStartMs = 0L
            return // the benchmark is the measurement in charge right now
        }
        val (att, fails, code3) = govWindowStats()
        val ratio = if (att > 0) fails.toDouble() / att else 0.0
        val pct = (ratio * 100).toInt()
        val codeOth = fails - code3
        // Blindness signature: the foreground package is OWED an
        // analysis (one is due — an idle SYSTEM sanity wait is not
        // blindness), none has landed past the blindness bar, and
        // attempts are demonstrably in flight inside the last 30 s.
        val owedAnalysis = nextCaptureDueMs in 1..now || susActive(fgPkg) || postBlockSusActive()
        // v0.14.2 (field 2026-10-30): the age is REAL or the arm
        // does not evaluate. The pre-fix age had two sentinel
        // branches — Long.MAX_VALUE when no analysis had ever
        // landed this run, and Long.MAX_VALUE again whenever the
        // judged package differed from the last analysed one and
        // the blind tracker had not adopted it yet. The tracker
        // adopts a new package in the heartbeat's bookkeeping
        // AFTER this evaluation runs, so the first evaluation
        // after any switch to a never-analysed package read
        // okAgeMs = Long.MAX_VALUE — 9223372036854775 s of
        // "blindness" — and de-rated on the spot (the blind arm,
        // unlike the ratio arm, has no sustain requirement); the
        // sentinel age was then persisted in the rung's reason
        // via setGov and re-applied at every service start. A
        // judged context is blind-ageable now only when an
        // analysis OF IT is the most recent one landed — its
        // current tenure has produced an analysis, and none has
        // landed since. Never-analysed, just-switched and
        // post-start states are a grace, not blindness, and move
        // the governor in neither direction.
        val okAgeMs: Long? =
            if (lastAnalysisOkMs > 0L && fgPkg == lastAnalysisOkPkg) now - lastAnalysisOkMs else null
        // v0.14.2: the bar is interval-aware — max(GOV_BLIND_MS,
        // GOV_BLIND_INTERVAL_FACTOR × intervalMs()), the interval
        // the CURRENT effective pricing schedules for this
        // context. At fast prices (SUSPICIOUS 2–4 s, episodes 1 s)
        // 2× sits far under the 30 s floor, so the bar there is
        // the floor, exactly as before; at the slow idle prices
        // (NORMAL 20 s gentle / 40 s at FLOOR, SYSTEM to 120 s)
        // the bar scales with the schedule, so a rung can never
        // manufacture the signature that convicts it — FLOOR's
        // own lawful 40 s NORMAL gaps are a schedule, not
        // blindness. 2×, not 1×: one missed slot is jitter (an
        // analysis still running when the next slot falls due,
        // the post-block evaluation pause, a watchdog reset);
        // two consecutive missed slots are a stall.
        val blindBarMs = maxOf(GOV_BLIND_MS, GOV_BLIND_INTERVAL_FACTOR * intervalMs())
        val attemptsInFlight = govWindow.any { now - it.tsMs <= GOV_BLIND_MS }
        val blindCond = okAgeMs != null && owedAnalysis && attemptsInFlight && okAgeMs > blindBarMs
        // v0.14.4: the condition above is a suspicion, not a verdict.
        // The arm fires only when the condition has held
        // CONTINUOUSLY — GOV_BLIND_STREAK_EVALS consecutive
        // evaluations spanning at least GOV_BLIND_STREAK_SPAN_MS of
        // wall time, all against the same judged context. Any
        // evaluation where the condition does not hold resets the
        // streak to zero: an analysis landing (the age collapses),
        // a never-analysed / just-switched grace state (no real age
        // at all), an episode (its 1 s cadence lands analyses, so
        // the age cannot stay past the bar) — flicker can never
        // accumulate into a de-rate, while a genuine wedge holds
        // the condition at every heartbeat and convicts on the
        // streak. A context switch likewise restarts the streak:
        // a new judged context begins with no evidence against it.
        // At the ~15 s heartbeat the two floors bind together —
        // three evaluations span only ~30 s, so in practice the
        // 45 s span floor is the binding one (typically the fourth
        // consecutive true evaluation fires); stretched heartbeats
        // cannot shrink the wall-time evidence below 45 s, and the
        // count floor keeps a fast burst of evaluations from
        // standing in for duration. Each rung must convict on its
        // own evidence: a de-rate (any arm) resets the streak.
        if (blindCond) {
            if (govBlindStreakCtx != fgPkg || govBlindStreak == 0) {
                govBlindStreak = 1
                govBlindStreakStartMs = now
                govBlindStreakCtx = fgPkg
            } else {
                govBlindStreak++
            }
        } else {
            govBlindStreak = 0
            govBlindStreakCtx = null
            govBlindStreakStartMs = 0L
        }
        govBlindAgeMs = okAgeMs ?: -1L
        govBlindBarMs = blindBarMs
        val blindSpanMs = if (govBlindStreak > 0) now - govBlindStreakStartMs else 0L
        val blind = blindCond && govBlindStreak >= GOV_BLIND_STREAK_EVALS &&
            blindSpanMs >= GOV_BLIND_STREAK_SPAN_MS
        // Latency arm: the median analysis cost against the
        // foreground tier's idle effective interval.
        // v0.14.0 (§5 correctness note): the arm prices cost PER
        // PASS-SET, not per analysis — a multi-window analysis is
        // supposed to cost several pass-sets, and comparing its
        // total would read a split-screen afternoon as a slow
        // phone and de-rate a healthy device. PERF entries carry
        // their FAL pass count at [2]; the median is over per-set
        // costs. The capture-health arm above is untouched:
        // captures are still one per analysis, however many
        // windows it serves.
        val perSetCosts = perfSamples.map { s ->
            (s[0] + s[1]).toDouble() / maxOf(1L, s[2])
        }
        val medCost = if (perSetCosts.isEmpty()) -1L else perfMedian(perSetCosts.map { it.toLong() })
        val latency = perSetCosts.size >= 10 && medCost > 0 &&
            medCost.toDouble() > GOV_LATENCY_FACTOR * tierIntervalMs(fgPkg)
        val overRatio = att >= GOV_MIN_ATTEMPTS && ratio > GOV_DERATE_RATIO
        val watch = att >= GOV_MIN_ATTEMPTS && ratio > GOV_WATCH_RATIO
        if (watch && !govWatchLogged) {
            DebugLog.log(this, "GOVERNOR watch fails=$pct% win=${att}att code3=$code3 codeOth=$codeOth")
            govWatchLogged = true
        } else if (!watch) {
            govWatchLogged = false
        }
        val derateWanted = blind || latency || (overRatio && govPrevOverRatio)
        govPrevOverRatio = overRatio
        val eff = Prefs.effRung(this)
        if (!derateWanted) {
            govFloorStrikes = 0
            return
        }
        if (eff <= 0) {
            // At FLOOR: three further failing evaluations and the
            // governor rests, honestly (design §2) — a persistent
            // FAIL line plus the Home banner.
            govFloorStrikes++
            if (govFloorStrikes >= GOV_FLOOR_STRIKES) {
                govResting = true
                Prefs.get(this).edit().putBoolean("gov_floor_banner", true).apply()
                failLog(
                    "capture",
                    "GOV rung=floor->rest why=floor-strikes fails=$pct% win=${att}att blind=$blind latency=$latency — " +
                        "failures persist at FLOOR; this phone cannot sustain ScreenGuard's checks"
                )
            }
            return
        }
        if (now - govLastDerateMs < GOV_DWELL_MS) return // one rung per decision, 60 s dwell
        val newGov = eff - 1
        // v0.14.4: the streak evidence is captured BEFORE the
        // de-rate below resets it, so the reason and the GOV line
        // record the case that convicted, not the cleared state.
        val streakAtDerate = govBlindStreak
        val reason = when {
            blind -> "blind lastOkAge=${(okAgeMs ?: 0L) / 1000}s pkg=$fgPkg streak=$streakAtDerate span=${blindSpanMs / 1000}s"
            latency -> "latency medCost=${medCost}ms vs idle ${tierIntervalMs(fgPkg)}ms"
            else -> "fails=$pct% win=${att}att"
        }
        Prefs.setGov(this, newGov, reason)
        govLastDerateMs = now
        govPrevOverRatio = false
        // v0.14.4: a de-rate (any arm) closes the blind streak's
        // case — the next rung must convict on its own evidence.
        govBlindStreak = 0
        govBlindStreakCtx = null
        govBlindStreakStartMs = 0L
        // v0.14.2: every rung change carries the stable greppable
        // GOV line (why=<arm> + its inputs) — the pre-fix build's
        // de-rates were three differently-shaped FAIL lines and
        // its benchmark release was silent, so the 2026-10-30
        // FLOOR cycle left almost no governor trace in the logs.
        when {
            blind -> failLog(
                "capture",
                "GOV rung=${Prefs.rungName(eff)}->${Prefs.rungName(newGov)} why=blind lastOkAge=${(okAgeMs ?: 0L) / 1000}s bar=${blindBarMs / 1000}s streak=$streakAtDerate span=${blindSpanMs / 1000}s pkg=$fgPkg chosen=${Prefs.rungName(Prefs.chosenRung(this))}"
            )
            latency -> failLog(
                "capture",
                "GOV rung=${Prefs.rungName(eff)}->${Prefs.rungName(newGov)} why=latency medCost=${medCost}ms idle=${tierIntervalMs(fgPkg)}ms chosen=${Prefs.rungName(Prefs.chosenRung(this))}"
            )
            else -> failLog(
                "capture",
                "GOV rung=${Prefs.rungName(eff)}->${Prefs.rungName(newGov)} why=fails fails=$pct% win=${att}att code3=$code3 codeOth=$codeOth chosen=${Prefs.rungName(Prefs.chosenRung(this))}"
            )
        }
    }

    /**
     * v0.14.2: the recorded blindness age (seconds) of a persisted
     * governor reason, when that age could only be the
     * never-analysed sentinel — or -1 when it could not. A genuine
     * blind age is measured live (now minus the last analysis) and
     * is therefore bounded by the process's own uptime;
     * GOV_SENTINEL_AGE_S (365 days) sits far above any real value
     * and nine orders of magnitude below the sentinel's 9.2e15 s.
     * Only the blind arm ever wrote an age into a reason, so only
     * its records are candidates for the discard.
     */
    private fun sentinelBlindAgeS(reason: String): Long {
        if (!reason.startsWith("blind")) return -1L
        val m = Regex("lastOkAge=(\\d+)s").find(reason) ?: return -1L
        val age = m.groupValues[1].toLongOrNull() ?: return -1L
        return if (age >= GOV_SENTINEL_AGE_S) age else -1L
    }

    // ---- v0.13.0: the benchmark (capability design §3) ----
    // BenchmarkActivity is the stage; this machine is the referee.
    // Phases climb Gentle (30 s @4 s) → Balanced (30 s @3 s) →
    // Strict (30 s @2 s) → episode probe (20 s @1 s); the first
    // failure stops the climb. A completed run writes the cap record
    // (Prefs.setCap) and clears the governor state — the measurement
    // replaces the estimate. A cancelled run writes NOTHING.

    private fun benchPhaseName(idx: Int): String = when (idx) {
        0 -> "gentle"
        1 -> "balanced"
        2 -> "strict"
        else -> "episode"
    }

    private fun benchPhaseInterval(idx: Int): Long = when (idx) {
        0 -> 4000L
        1 -> 3000L
        2 -> 2000L
        else -> 1000L
    }

    private fun benchPhaseDuration(idx: Int): Long = if (idx == 3) 20_000L else 30_000L

    private fun benchSetStatus(text: String) {
        Prefs.get(this).edit()
            .putString("bench_status", text)
            .putBoolean("bench_active", benchActive)
            .putLong("bench_phase_end", benchPhaseEndMs)
            .apply()
    }

    /** Start the machine when Home / Setup asked (bench_run pref) —
     *  called from tick. No-op while a run is live. */
    private fun benchMaybeStart(now: Long) {
        if (benchActive) return
        val p = Prefs.get(this)
        if (!p.getBoolean("bench_run", false)) return
        p.edit().remove("bench_run").remove("bench_cancel").apply()
        benchActive = true
        benchBasisModel = p.getBoolean("model_ok", false)
        benchPhaseIdx = 0
        DebugLog.log(
            this,
            "BENCH start device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "basis=${if (benchBasisModel) "analyses" else "captures(model unavailable)"} chosen=${Prefs.rungName(Prefs.chosenRung(this))}"
        )
        benchBeginPhase(now)
    }

    /** v0.13.3: advance the benchmark's own schedule one phase
     *  interval out from [anchorMs], leaving the decision in the
     *  log — a starved phase must be diagnosable from the re-arm
     *  trace alone. */
    private fun benchRearm(anchorMs: Long, why: String) {
        nextBenchDueMs = anchorMs + benchIntervalMs
        DebugLog.log(
            this,
            "BENCH rearm why=$why phase=${benchPhaseName(benchPhaseIdx)} interval=${benchIntervalMs}ms " +
                "dueIn=${(nextBenchDueMs - System.currentTimeMillis()).coerceAtLeast(0)}ms"
        )
    }

    /** v0.13.3: a bench slot ended (its analysis completed, or its
     *  frame was diff-skipped). If the pipeline slot was occupied so
     *  long that the bench schedule fell behind, re-anchor from
     *  now: missed slots are dropped, never compressed into a
     *  burst at the capture floor. */
    private fun benchSlotDone(why: String) {
        val now = System.currentTimeMillis()
        if (nextBenchDueMs in 1..now) benchRearm(now, why)
    }

    private fun benchBeginPhase(now: Long) {
        benchIntervalMs = benchPhaseInterval(benchPhaseIdx)
        benchPhaseStartMs = now
        benchPhaseEndMs = now + benchPhaseDuration(benchPhaseIdx)
        benchPhaseAtt = 0
        benchPhaseOk = 0
        benchWatchdog0 = watchdogTripsAll
        benchDue0 = dueSlotsAll
        benchDueBusy0 = dueBusyAll
        benchCaptureOk = 0
        benchStaticBypassLogged = false
        synchronized(benchAnalyses) { benchAnalyses.clear() }
        // The first phase capture should land promptly, not after
        // whatever idle wait was pending.
        if (nextCaptureDueMs > now) nextCaptureDueMs = now
        // v0.13.3: …and on the benchmark's own schedule, which is
        // the one the tick actually honours for our surface.
        nextBenchDueMs = now
        DebugLog.log(
            this,
            "BENCH phase ${benchPhaseIdx + 1}/4 ${benchPhaseName(benchPhaseIdx)} begin interval=${benchIntervalMs}ms dur=${benchPhaseDuration(benchPhaseIdx) / 1000}s"
        )
        DebugLog.log(this, "BENCH rearm why=phase-begin phase=${benchPhaseName(benchPhaseIdx)} interval=${benchIntervalMs}ms dueIn=0ms")
        benchSetStatus("Phase ${benchPhaseIdx + 1} of 4 · ${benchPhaseName(benchPhaseIdx)} cadence")
    }

    /** Score the finished phase; advance, or finish the run. */
    private fun benchScorePhase(now: Long) {
        val idx = benchPhaseIdx
        // v0.13.1: the phase's OWN captures only (see benchPhaseAtt).
        val att = benchPhaseAtt
        val ok = benchPhaseOk
        val succ = if (att > 0) ok.toDouble() / att else 0.0
        val watchdogDelta = watchdogTripsAll - benchWatchdog0
        val dueDelta = dueSlotsAll - benchDue0
        val dueBusyDelta = dueBusyAll - benchDueBusy0
        val skipRatio = if (dueDelta > 0) dueBusyDelta.toDouble() / dueDelta else 0.0
        val analysesSnap = synchronized(benchAnalyses) { ArrayList(benchAnalyses) }
        val costs: List<Long> = if (benchBasisModel) {
            analysesSnap.map { it[0] + it[1] }
        } else {
            List(benchCaptureOk) { 0L }
        }
        val med = perfMedian(costs)
        val p90 = perfP90(costs)
        val iv = benchIntervalMs
        // v0.13.2: too few samples is a MEASUREMENT failure, not a
        // phone failure — the phase never got to judge the device.
        // Abort without a cap (benchAbortUnmeasured); "failed the X
        // phase" is reserved for phases that measured and missed
        // their bars. (v0.13.1 scored this path through benchFinish
        // and wrote cap=floor from att=1, n=1.)
        if (att < BENCH_MIN_SAMPLES || costs.size < BENCH_MIN_SAMPLES) {
            DebugLog.log(
                this,
                "BENCH phase ${benchPhaseName(idx)} UNMEASURED att=$att ok=$ok succ=${(succ * 100).toInt()}% " +
                    "watchdog=$watchdogDelta n=${costs.size} med=${med}ms p90=${p90}ms — too few samples to score"
            )
            benchAbortUnmeasured("bench-unmeasured phase=${benchPhaseName(idx)} att=$att n=${costs.size}")
            return
        }
        val failReasons = ArrayList<String>()
        if (succ < BENCH_MIN_SUCCESS) failReasons += "succ=${(succ * 100).toInt()}%<98%"
        if (watchdogDelta != 0) failReasons += "watchdog=$watchdogDelta"
        // v0.13.1: skip_busy is NO LONGER a fail bar (see the retired
        // BENCH_MAX_SKIP_BUSY note) — skipRatio is still computed and
        // printed below as information.
        // Cost bars bind the IDLE phases only: the episode probe is
        // scored on the capture / watchdog bars (design §3 — "the
        // probe is scored on the capture/watchdog bars and stored
        // separately in cap_episode_ok").
        if (benchBasisModel && idx < 3) {
            if (med < 0 || med.toDouble() > BENCH_MED_COST_FRAC * iv) failReasons += "med=${med}ms>${(BENCH_MED_COST_FRAC * iv).toInt()}ms"
            if (p90 < 0 || p90.toDouble() > BENCH_P90_COST_FRAC * iv) failReasons += "p90=${p90}ms>${iv}ms"
        }
        val passed = failReasons.isEmpty()
        DebugLog.log(
            this,
            "BENCH phase ${benchPhaseName(idx)} ${if (passed) "PASS" else "FAIL"} att=$att ok=$ok succ=${(succ * 100).toInt()}% " +
                "watchdog=$watchdogDelta skipBusy=${(skipRatio * 100).toInt()}% n=${costs.size} med=${med}ms p90=${p90}ms" +
                if (failReasons.isEmpty()) "" else " reasons=${failReasons.joinToString(";")}"
        )
        if (passed && idx < 3) {
            benchPhaseIdx++
            benchBeginPhase(now)
            return
        }
        benchFinish(now, stopIdx = idx, stopPassed = passed)
    }

    /**
     * Write the cap for a completed run. The cap is the highest rung
     * whose idle phase PASSED; a failed episode probe drops it one
     * further rung (design §3, decision 4). Passing Strict + the
     * probe caps at Strict — no cap in force in practice.
     */
    private fun benchFinish(now: Long, stopIdx: Int, stopPassed: Boolean) {
        benchActive = false
        var cap = when {
            stopPassed && stopIdx == 3 -> 3 // probe passed after Strict: cap = strict (no constraint)
            stopPassed -> stopIdx + 1 // phases 0..2 map to rungs 1..3
            stopIdx == 3 -> 2 // idle climb reached Strict, probe failed: one rung below the idle result
            else -> stopIdx // failed phase idx caps at the rung below it (0 failed → FLOOR 0)
        }
        if (cap < 0) cap = 0
        if (cap > 3) cap = 3
        val episodeOk = !(stopIdx == 3 && !stopPassed)
        val analysesSnap = synchronized(benchAnalyses) { ArrayList(benchAnalyses) }
        val falMed = perfMedian(analysesSnap.map { it[0] })
        val clsMed = perfMedian(analysesSnap.map { it[1] })
        val detail = when {
            stopIdx == 3 && !stopPassed -> "Episode probe failed: 1 s bursts are not sustainable on this phone."
            stopPassed && stopIdx == 3 -> "Passed every rung including the 1 s episode probe."
            stopPassed -> "Passed up to ${benchPhaseName(stopIdx)}."
            else -> "Failed the ${benchPhaseName(stopIdx)} phase — see the BENCH lines in the debug log."
        }
        Prefs.setCap(
            this, cap, episodeOk,
            falMs = if (falMed >= 0) falMed else -1L,
            clsMs = if (clsMed >= 0) clsMed else -1L,
            failDetail = detail,
            device = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        // §4: the measurement replaces the governor's estimate.
        // v0.14.2: the release is a governor transition and is
        // logged as one — the pre-fix build cleared the rung in
        // silence (the 2026-10-30 12:06 export contains zero
        // governor event lines; the FLOOR cycle had to be
        // reconstructed from ALIVE fields a day later).
        val govBefore = Prefs.govRung(this)
        Prefs.clearGov(this)
        if (govBefore >= 0) {
            DebugLog.log(this, "GOV rung=${Prefs.rungName(govBefore)}->none why=benchmark-release cap=${Prefs.rungName(cap)}")
        }
        Prefs.get(this).edit().remove("gov_floor_banner").apply()
        govResting = false
        govFloorStrikes = 0
        DebugLog.log(
            this,
            "BENCH done cap=${Prefs.rungName(cap)} episodeOk=$episodeOk falMed=${falMed}ms clsMed=${clsMed}ms detail=$detail"
        )
        // v0.13.1: an explicit, timestamped RESULT record for the
        // benchmark screen — v0.13.0's activity keyed its completion
        // branch off the status string alone and sat on the last
        // phase screen after BENCH done (Alejandro: "it gets
        // stuck?"). The activity shows its result view when it sees
        // a result newer than the moment it opened.
        Prefs.get(this).edit()
            .putLong("bench_result_ts", System.currentTimeMillis())
            .putInt("bench_result_cap", cap)
            .putString("bench_result_detail", detail)
            .apply()
        benchSetStatus("Done · best mode for this phone: ${Prefs.rungName(cap)}")
    }

    private fun benchAbort(now: Long) {
        if (!benchActive) return
        benchActive = false
        DebugLog.log(this, "BENCH cancelled — nothing written (a cancelled benchmark has no opinion)")
        benchSetStatus("Benchmark cancelled — nothing was written.")
    }

    /**
     * v0.13.2: end a run that could not measure (starved phase, or
     * too few samples to score). Like a cancel, it writes NO cap and
     * clears NO governor state — the previous capability state
     * stands untouched, because the run produced no evidence about
     * the phone. Unlike a cancel it is LOUD: a FAIL line, and a
     * result record whose detail says the benchmark could not
     * measure — never "failed the X phase", which is reserved for
     * phases that actually measured and missed their bars. The
     * result record's cap is the cap still in force (Prefs.capRung),
     * so the result screen's number stays truthful.
     */
    private fun benchAbortUnmeasured(why: String) {
        if (!benchActive) return
        benchActive = false
        failLog("bench", why)
        val detail = "Could not measure this phone — the benchmark's own captures stalled, " +
            "so no mode was changed. See the FAIL line in the debug log."
        Prefs.get(this).edit()
            .putLong("bench_result_ts", System.currentTimeMillis())
            .putInt("bench_result_cap", Prefs.capRung(this))
            .putString("bench_result_detail", detail)
            .apply()
        benchSetStatus("Benchmark could not measure — nothing was written.")
        DebugLog.log(this, "BENCH done unmeasured ($why) — no cap written, previous capability state unchanged")
    }

    /** Per-tick benchmark housekeeping: cancel requests + phase ends. */
    private fun benchTick(now: Long) {
        if (Prefs.get(this).getBoolean("bench_cancel", false)) {
            Prefs.get(this).edit().remove("bench_cancel").apply()
            benchAbort(now)
            return
        }
        benchMaybeStart(now)
        if (!benchActive) return
        // v0.13.2 STARVATION GUARD: fewer than BENCH_MIN_SAMPLES
        // captures issued BENCH_STARVE_MS into a phase means the
        // issuance path itself is stalled — the phase is not
        // measuring, however long it sits. Abort loudly instead of
        // letting the phase end and convict the phone on att=1
        // (the 2026-10-29 23:45 field run).
        if (benchPhaseAtt < BENCH_MIN_SAMPLES && now - benchPhaseStartMs >= BENCH_STARVE_MS) {
            benchAbortUnmeasured("bench-starved phase=${benchPhaseName(benchPhaseIdx)} att=$benchPhaseAtt")
            return
        }
        if (now >= benchPhaseEndMs) benchScorePhase(now)
    }

    private fun tick() {
        if (!running) return
        // v0.11.10 POST-BLOCK SUS (Alejandro's order): for 180 s after
        // any real block, EVERY app is SUS-active — susActive() folds
        // the global window in, so cadence, scout, marker and outline
        // all follow; the local flag here keeps the v0.4.1 watch's
        // other job, suspending the skip sets below so no screen gets
        // a free look in front of any app. Re-entry previously got an
        // 8-10 s free look through the normal per-app cadences.
        val watchActive = postBlockSusActive()
        val next = if (watchActive) SUS_ANALYSIS_MS else intervalMs()
        // v0.11.4: SUS expiry is passive — spot a lapsed window on a
        // poll and close it out (logs SUS off once, drops the marker).
        // v0.11.6: the expiry goes through expireSus, which applies
        // the classifier refractory guard-rail first.
        expireSusLapsed() // v0.14.0: per-package map — every lapsed trip window expires on its own clock
        // v0.11.10: the post-block window's expiry is likewise
        // passive — spot the lapse, log its SUS off once, and clear
        // the deadline so the line fires exactly once. A trip window
        // live for some package is untouched: its suspicion runs on
        // its own clock (and its own expiry path above).
        if (postBlockSusUntilMs != 0L && System.currentTimeMillis() >= postBlockSusUntilMs) {
            postBlockSusUntilMs = 0L
            DebugLog.log(this, "SUS off src=post-block (180s window expired)")
        }
        // v0.11.12: the paranoid window's expiry is likewise
        // passive — log the off-transition once, clear the
        // deadline, and the refreshOverlay() below drops the
        // PARANOID marker within the same poll.
        if (paranoidUntilMs != 0L && System.currentTimeMillis() >= paranoidUntilMs) {
            paranoidUntilMs = 0L
            DebugLog.log(this, "PARANOID off (expired)")
        }
        Prefs.bump(this, "ticks")
        syncDebugOverlay() // v0.5.2: keep the live-scores readout in step with its toggle
        syncGtButton() // v0.11.9: the GT button lives and dies with the debug overlay
        syncScoreDots() // v0.16.0: keep the score dots in step with their pref and the counters
        refreshOverlay() // v0.11.4: keep the SUS MODE line in step with the suspicion state
        syncRegionOutline() // v0.10.10: keep the crop outline in step with the region + toggle

        // v0.1.3 ROOT-CAUSE FIX. Field log 2026-10-25 18:19-18:21: Alejandro
        // browsed explicit content non-stop in Google Images/Chrome, but the
        // accessibility EVENT stream flipped foregroundPkg to
        // com.android.launcher after ~1 s and never flipped back — and the
        // launcher was hard-skipped, so the guard took ZERO screenshots for
        // two full minutes while explicit content was on screen. The event
        // stream does not reliably name the window that is actually showing.
        // So: ask for the active window fresh every tick, and never let a
        // stale event label (including "launcher") suppress capture. Only
        // truly-safe windows (ourselves, system UI, Settings, permission
        // dialogs) are hard-skipped. takeScreenshot captures the physical
        // display regardless of which window claims focus.
        val winPkg = activeWindowPkg()
        if (winPkg != lastWinPkg) {
            dbg("WIN pkg=$winPkg (event pkg=$foregroundPkg)")
            lastWinPkg = winPkg
        }
        val pkg = winPkg ?: foregroundPkg
        // v0.14.0 WINDOW STACK: sample the visible windows and fold
        // the sample in (STACK line, stack pricing, visible-SUS
        // cache, evidence pruning). The stack ENGAGES only when it
        // read successfully, a benchmark is not running (the
        // benchmark owns its surface outright — the stack must not
        // add passes to, or gate, bench captures), and the one-shot
        // capture-bench is not armed on our own package (its
        // exception below is the legacy path by design).
        val capbenchArmed = Prefs.get(this).getBoolean("capbench", false)
        val stackEngaged = !benchActive && !(capbenchArmed && pkg == packageName)
        // v0.17.0: the sampled stack passes the live-session
        // leg filter before anything consumes it (the held
        // package's floating windows leave the judged set; the
        // filtered snapshot is what onStackSampled stores, what
        // prices the screen, and what verdicts attribute
        // against — one consistent set for the whole tick).
        val stack: StackSnapshot? = if (stackEngaged) sampleStack()?.let { applyCallHoldLegFilter(it) } else null
        if (stack != null) {
            onStackSampled(stack)
        } else if (stackEngaged) {
            // The stack read failed this tick — fall back to the
            // foreground label wholesale (§10): no stack pricing,
            // no stack gating, exactly v0.13.5 for this tick.
            currentStack = null
            stackPricedMs = null
            visibleSusCached = false
        }
        val judgedWins = stack?.judged ?: emptyList()
        val stackPrimaryPkg = stack?.primary()?.pkg
        // v0.6.0 QUARANTINE: keep the cooldown cover in step with the
        // foreground app. A quarantined app is not captured or graded at
        // all (Alejandro's ruling: no grading under the cover) — the cover
        // IS its interface until the wall-clock countdown ends.
        syncQuarantineCover()
        // While the cover is up, polls may report OUR package (the cover
        // itself); the covered app is still the one in front.
        val effPkg = if (pkg == packageName && quarCoverPkg != null) quarCoverPkg!! else pkg
        // v0.17.0 LIVE SESSION HOLD: the cheap-signal state
        // machine rides every tick — arm/exit decisions land
        // here, before any judging decision below (the wholesale
        // pause sits further down, past the heartbeat, so ALIVE
        // lines keep flowing through a held call).
        evalCallHold(System.currentTimeMillis(), effPkg, stack?.appWindows?.mapNotNull { it.pkg }?.toSet())
        // v0.13.0: benchmark housekeeping rides every tick (start /
        // cancel / phase ends) — phases are 20–30 s, far longer than
        // the poll, so per-tick precision is ample.
        benchTick(System.currentTimeMillis())
        // v0.11.9: GT housekeeping — a forgotten interval auto-closes
        // at GT_TIMEOUT_MS, and an interval closes (pkgchange) rather
        // than following Alejandro across apps. The foreground must
        // name a different package on 2 consecutive ticks first: the
        // window poll can flicker for a frame (dialogs, transitions),
        // and a label is worthless if a flicker truncates it. Our own
        // package (cover / UI in front) is never "foreign".
        val gp = gtPkg
        if (gp != null) {
            if (System.currentTimeMillis() - gtStartMs >= GT_TIMEOUT_MS) {
                closeGt("timeout")
            } else if (effPkg.isNotBlank() && effPkg != packageName && effPkg != gp) {
                if (++gtForeignTicks >= 2) closeGt("pkgchange")
            } else if (effPkg == gp) {
                gtForeignTicks = 0
            }
        }
        // v0.14.0: a quarantined package visible only as a window
        // (a PiP under its cover) also ends the tick here — the
        // cover is up over it (syncQuarantineCover keys on any
        // visible window of the package now) and there is nothing
        // lawful to grade under a cover.
        if ((effPkg.isNotBlank() && Prefs.quarUntil(this, effPkg) > System.currentTimeMillis()) ||
            judgedWins.any { Prefs.quarUntil(this, it.pkg!!) > System.currentTimeMillis() }
        ) {
            scheduleNext(); return
        }
        // v0.2.5 HEARTBEAT: an unconditional (not verbose-gated) line every
        // ~15 s, so any future stall is self-evident in an exported log —
        // the v0.2.4 failure left no trace at all.
        val nowHb = System.currentTimeMillis()
        if (nowHb - lastHeartbeatMs > 15_000L) {
            lastHeartbeatMs = nowHb
            heartbeatSeen = true // v0.13.0: governor warm-up bookkeeping
            // v0.11.13: capture health rides the heartbeat — attempts /
            // successes across BOTH capture consumers, failures split
            // with code 3 (INTERVAL_TIME_SHORT) named separately, and
            // the age of the last successful analysis of the package
            // in front. The v0.11.12-era failure mode (a loop alive
            // on paper, starved in fact) must be visible in one line.
            val f3 = capFailByCode[3] ?: 0
            val fTot = capFailByCode.values.sum()
            val lastOkAge = if (lastAnalysisOkPkg == effPkg) "${(nowHb - lastAnalysisOkMs).coerceAtLeast(0) / 1000}s" else "never"
            // v0.13.0 PERF (Alejandro's requirement): the rolling
            // performance summary — capture ok-ratio over the
            // governor's own window, Falconsai median + p90 and
            // classifier median over the last 20 analyses, and the
            // capability state (effective rung / governor / cap). A
            // phone that needs 30 s per image is unmistakable from
            // this one line: falMed/falP90 say it in milliseconds.
            val gOk = govWindow.count { it.ok }
            val perfOk = if (govWindow.isNotEmpty()) "${gOk * 100 / govWindow.size}%" else "-"
            val falMed = perfMedian(perfSamples.map { it[0] })
            val falP90 = perfP90(perfSamples.map { it[0] })
            val clsMed = perfMedian(perfSamples.map { it[1] })
            DebugLog.log(
                this,
                "ALIVE ticks=${Prefs.get(this).getInt("ticks", 0)} cap_try=${Prefs.get(this).getInt("cap_try", 0)} " +
                    "cap_ok=${Prefs.get(this).getInt("cap_ok", 0)} busy=$busy " +
                    "dueIn=${(nextCaptureDueMs - nowHb).coerceAtLeast(0)}ms win=$winPkg quar=${(Prefs.quarUntil(this, pkg) - nowHb).coerceAtLeast(0) / 1000}s pbsus=${(postBlockSusUntilMs - nowHb).coerceAtLeast(0) / 1000}s " +
                    "capAtt=$capAttAll capOkAll=$capOkAll capF3=$f3 capFOth=${fTot - f3} lastOkAge=$lastOkAge " +
                    "fails=$failTotal " + // v0.11.14: total FAIL lines emitted this session — the export's failure tally in its heartbeat
                    "perfOk=$perfOk falMed=${falMed}ms falP90=${falP90}ms clsMed=${clsMed}ms " +
                    "eff=${Prefs.rungName(Prefs.effRung(this))} gov=${Prefs.govRung(this)} cap=${Prefs.capRung(this)} " +
                    // v0.14.0 (§2): the stack rides the heartbeat —
                    // a blind or mispriced screen is diagnosable
                    // from one ALIVE line. stack=-1 = the stack
                    // model is not engaged this tick (benchmark /
                    // capture-bench / read failure — legacy label).
                    // v0.14.5: on an exempt-only stack the judged
                    // field names the sanity target (sanity:<pkg>)
                    // — the heartbeat must show the sanity path is
                    // what is running, not a judging gap.
                    "stack=${stack?.judged?.size ?: -1} judged=${stack?.let { st -> st.topPkg ?: st.sanityPkg?.let { sp -> "sanity:$sp" } } ?: "-"}" +
                    // v0.14.4: a building blind streak is visible on
                    // the heartbeat BEFORE it can fire — the streak
                    // as of the previous evaluation (the governor
                    // evaluates later in this same heartbeat block),
                    // with the age and bar it is measured against.
                    // Absent when no streak is building.
                    (if (govBlindStreak > 0) " blindStreak=$govBlindStreak age=${govBlindAgeMs / 1000}s bar=${govBlindBarMs / 1000}s" else "") +
                    // v0.13.3: while a benchmark runs, its schedule is
                    // on the heartbeat — dueIn here reads the BENCH
                    // schedule (nextBenchDueMs), so a starved phase
                    // (the 29–40 s signature) is diagnosable from
                    // this one line instead of a reconstruction.
                    if (benchActive) " bench=${benchPhaseName(benchPhaseIdx)} att=$benchPhaseAtt dueIn=${(nextBenchDueMs - nowHb).coerceAtLeast(0)}ms" else ""
            )
            // v0.12.1: reconcile the status-bar shield on the same
            // heartbeat — a POST_NOTIFICATIONS grant/revocation made
            // while the guard runs lands within ~15 s, no restart.
            syncStatusNotification()
            // v0.13.0: the governor evaluates on this same heartbeat
            // (capability design §2: "evaluates in the existing
            // ~15 s heartbeat block in tick()").
            evaluateGovernor(nowHb, effPkg)
        }
        // v0.11.13 BLIND bookkeeping (display only): how long has the
        // package in front — one the service should be analysing —
        // gone without a successful analysis? refreshOverlay renders
        // it as a "BLIND Ns" line past BLIND_AFTER_MS. Nothing acts
        // on it in this release; it exists so starvation is SEEN on
        // the overlay while it happens, not reconstructed from a log
        // afterwards (the 2026-10-28 Gallery session: the foreground
        // opinion froze on the launcher and the judging loop
        // produced nothing for 51 s under a happily ticking ALIVE).
        // v0.14.0: blindness is measured against the package the
        // stack says is being judged (the primary), not the
        // foreground label — a starved PiP over Settings is blind
        // time even though Settings itself is owed nothing.
        // v0.14.5: on an exempt-only stack, blindness is
        // measured against the SANITY target — the sanity
        // analyses are real analyses of that package (doCapture
        // stamps lastAnalysisOkPkg with it), so the BLIND
        // counter counts the sanity cadence's gaps and resets
        // on each sanity look, exactly as Alejandro's
        // never-suppress-the-flag ruling requires.
        val blindPkg = when {
            judgedWins.isNotEmpty() -> stackPrimaryPkg!!
            stack?.sanityPkg != null -> stack.sanityPkg!!
            else -> effPkg
        }
        if (blindPkg != blindFgPkg) {
            blindFgPkg = blindPkg
            blindFgSinceMs = nowHb
        }
        val shouldAnalyse = if (judgedWins.isNotEmpty()) true
            else if (stack?.sanityPkg != null) true
            else blindPkg.isNotBlank() && blindPkg != packageName && !isSettingsPkg(blindPkg) &&
                !(blindPkg in SKIP_EXACT && !watchActive)
        val blindBase = if (lastAnalysisOkPkg == blindPkg) lastAnalysisOkMs else blindFgSinceMs
        val newBlindLabel = if (shouldAnalyse && nowHb - blindBase > BLIND_AFTER_MS) {
            "BLIND ${(nowHb - blindBase) / 1000}s"
        } else null
        if (newBlindLabel != blindLabel) {
            blindLabel = newBlindLabel
            refreshOverlay()
        }
        // v0.13.0: due-slot accounting (the benchmark's skip_busy
        // bar): a due slot counts once, at the first poll that finds
        // it due; it is a busy-skip if the pipeline is busy then.
        if (nextCaptureDueMs in 1..nowHb && nextCaptureDueMs != lastCountedDueMs) {
            lastCountedDueMs = nextCaptureDueMs
            dueSlotsAll++
            if (busy) dueBusyAll++
        }
        // v0.13.3: while a benchmark runs against our own surface,
        // the BENCH schedule (nextBenchDueMs) outranks whatever the
        // normal paths baked into nextCaptureDueMs — the stale
        // pre-benchmark price of the run's first tick (40 s under a
        // FLOOR cap) and the previous phase's interval at
        // transitions both land there via doCapture's callbacks.
        // Clamp the shared due down to the bench schedule. The
        // moment the run ends (benchActive false) or the foreground
        // leaves the benchmark surface (pkg != ours), normal
        // pricing resumes untouched — no non-benchmark scheduling
        // changes.
        if (benchActive && pkg == packageName && nextBenchDueMs > 0L && nextCaptureDueMs > nextBenchDueMs) {
            nextCaptureDueMs = nextBenchDueMs
        }
        // Poll-only tick: the window bookkeeping above stays current, but no
        // screenshot is due yet.
        if (System.currentTimeMillis() < nextCaptureDueMs) {
            // v0.7.1: analysis finishes after the next capture is scheduled,
            // so an app that just turned suspicious would otherwise wait out
            // the relaxed idle rate before its burst even starts. Pull in.
            if (urgentFor(effPkg)) pullNextCaptureIn(nowHb)
            scheduleNext(); return
        }

        // Watchdog: a screenshot callback that never fires must not wedge the loop.
        if (busy && System.currentTimeMillis() - busySinceMs > BUSY_WATCHDOG_MS) {
            failLog("watchdog", "watchdog busy stuck >${BUSY_WATCHDOG_MS}ms, force-reset (callback never fired?)")
            watchdogTripsAll++ // v0.13.0: benchmark scoring counts these
            captureGen++ // v0.4.0: invalidate the wedged capture's callback if it ever arrives
            busy = false
        }
        if (busy) { Prefs.bump(this, "skip_busy"); scheduleNext(); return }
        // v0.11.2: a scout capture is in flight — never run two
        // takeScreenshot calls into the platform's rate limit; the
        // next poll (<= POLL_MS) retries. A wedged scout callback is
        // force-cleared on the same watchdog budget as a wedged
        // analysis capture.
        if (scoutBusy && System.currentTimeMillis() - scoutBusySinceMs > BUSY_WATCHDOG_MS) {
            failLog("watchdog", "watchdog scout busy stuck >${BUSY_WATCHDOG_MS}ms, force-reset")
            watchdogTripsAll++ // v0.13.0: benchmark scoring counts these
            scoutBusy = false
        }
        if (scoutBusy) { scheduleNext(); return }
        // v0.17.0 LIVE SESSION HOLD — THE PAUSE (Alejandro's
        // ruling: the app stops judging while calling). While
        // the held package's call surface is the foreground
        // content, no capture issues and no analysis runs for
        // it. The tick's housekeeping above (heartbeat, SUS /
        // paranoid expiry, the hold's own state machine, the
        // governor) keeps running on the cheap signals; the
        // pause re-tests its condition every tick, so it lifts
        // the moment the surface stops scoping to a call or the
        // audio mode leaves a call mode. A capture already in
        // flight when the hold armed lands normally — its
        // verdicts meet counters reset at entry and the
        // executeBlock refusal net.
        if (callHoldPkg != null && callHoldPkg == effPkg && callSurfaceHeldNow(effPkg)) {
            scheduleNext(); return
        }
        // Capture-bench exception: while armed from the UI, our OWN package is
        // analysable once, so the service can screenshot the fullscreen
        // reference photo shown by PhotoActivity.
        // v0.13.2: while a benchmark runs, benchActive ITSELF holds
        // this exception for the whole run. The one-shot capbench
        // pref cannot: the first stage analysis consumes it (the
        // CAPBENCH block below), which closed this gate for the rest
        // of the phase — phases starved at att=1 in the field
        // (2026-10-29 23:45) and on the emulator, in every prior cap
        // state. v0.13.0's field phase escaped only because a
        // post-block SUS window suspended the skip set outright.
        val capbench = Prefs.get(this).getBoolean("capbench", false)
        // v0.7.1: SETTINGS is exempt ABSOLUTELY — no captures even
        // during the post-block SUS window (which suspends the other
        // skip sets below).
        // v0.10.10: the LAUNCHER is no longer skipped here (see the
        // SKIP_SETTINGS comment): captures and full analysis run while
        // it is foreground at the normal "other apps" cadence, because
        // Recents preview cards inside it were a complete bypass. Any
        // conviction converts in executeBlock — never a launcher lock.
        // v0.14.5: the sanity state for this capture, if any —
        // set by the stack branch below when the judged set is
        // empty but an exempt surface is exposed. captureNext is
        // the due-interval this capture schedules after itself:
        // the ladder's `next` for judged captures, the sanity
        // price (or an episode's 1 s) for sanity captures.
        var sanityTarget: String? = null
        var captureNext = next
        if (stack != null) {
            // v0.14.0: exemption is a WINDOW property now. Capture
            // proceeds iff at least one judgeable window is
            // visible — Settings in front no longer blinds a PiP
            // floating over it. The judged set already
            // excludes every SKIP_EXACT / Settings package by
            // construction, so nothing below needs re-checking.
            // v0.14.5: a screen with NO judgeable window is no
            // longer skipped wholesale (Alejandro: no app
            // unchecked). When the snapshot names a sanity
            // target — an exposed exempt window, no shade up —
            // the tick falls through and captures FOR that
            // package at the sanity price; processFrame routes
            // the frame to the innocence-gated sanity path.
            // With no target (shade up, or nothing attributable
            // on screen) the skip stands exactly as before.
            if (judgedWins.isEmpty()) {
                val sp = stack.sanityPkg
                if (sp == null) {
                    dbg(
                        if (stack.shadeUp) "SKIP pkg=$pkg — shade up (occlusion pause, no captures)"
                        else "SKIP pkg=$pkg — no judgeable window (stack)"
                    )
                    Prefs.bump(this, "skip_pkg"); scheduleNext(); return
                }
                sanityTarget = sp
                captureNext = when {
                    // Episodes price a sanity capture exactly as
                    // they price any capture (the tick-top `next`
                    // is already SUS_ANALYSIS_MS under the
                    // post-block window); a trip live on the
                    // target itself densifies it to 1 s as well.
                    watchActive -> SUS_ANALYSIS_MS
                    susActive(sp) -> SUS_ANALYSIS_MS
                    else -> systemPriceMs()
                }
            }
        } else {
            if (isSettingsPkg(winPkg) || (winPkg == null && isSettingsPkg(pkg))) {
                dbg("SKIP pkg=$pkg — settings exempt (v0.7.1)")
                Prefs.bump(this, "skip_pkg"); scheduleNext(); return
            }
            if (pkg.isBlank() || (pkg in SKIP_EXACT && !watchActive && !((capbench || benchActive) && pkg == packageName))) {
                dbg("SKIP pkg=$pkg (blank or safe window)") // v0.2.5: was a silent dead-end
                Prefs.bump(this, "skip_pkg"); scheduleNext(); return
            }
        }

        // TEST block (armed from the UI with a ~3 s delay): fire the real
        // block path — overlay + Home — against whatever is in front, so the
        // ejection mechanism can be verified without any real content.
        val testDue = Prefs.get(this).getLong("test_block_due", 0L)
        if (testDue != 0L && System.currentTimeMillis() >= testDue) {
            Prefs.get(this).edit().remove("test_block_due").apply()
            mainHandler.post { testBlock(pkg) }
        }

        // v0.11.13: THE FLOOR GATE — the analysis is due, but the
        // platform's 1000 ms screenshot interval is law: if the
        // previous capture (either consumer) is still inside
        // CAPTURE_FLOOR_MS, defer this analysis to the first legal
        // slot instead of issuing a capture the system will refuse
        // with code 3. The poll loop wakes within POLL_MS of the
        // deferred due time, so the analysis lands on the slot.
        val nowFloor = System.currentTimeMillis()
        if (nowFloor - lastCaptureIssuedMs < CAPTURE_FLOOR_MS) {
            nextCaptureDueMs = lastCaptureIssuedMs + CAPTURE_FLOOR_MS
            scheduleNext(); return
        }

        // v0.14.0: the capture's package is the stack's primary
        // (the pace-setting judgeable window) when the stack is
        // live — doCapture's health books, the region session and
        // processFrame's dispatch all key on it — and the snapshot
        // rides along so the analysis attributes against THIS
        // tick's stack, not verdict time's.
        // v0.14.5: a sanity capture keys on the sanity target
        // instead, and its stamp (inFlightSanityPkg) routes the
        // frame to processFrameSanity — re-stamped at every
        // capture issue, exactly like inFlightStack, so a
        // discarded zombie callback can never leak it into a
        // later analysis.
        val capPkg = if (judgedWins.isNotEmpty()) stackPrimaryPkg!! else sanityTarget ?: pkg
        inFlightStack = if (judgedWins.isNotEmpty()) stack else null
        inFlightSanityPkg = sanityTarget
        busy = true
        busySinceMs = System.currentTimeMillis()
        Prefs.bump(this, "cap_try")
        dbg("CAP try pkg=$capPkg")
        // v0.5.2: the debug readout must never grade itself — hide it around
        // EVERY capture (the 60 ms pre-capture hide pattern survives from
        // the retired fade flicker: the compositor needs a few frames to
        // really drop the view). Restored the instant the screenshot lands
        // (doCapture's callbacks), before inference even runs.
        debugHiddenForCapture = debugView != null && debugView!!.visibility == View.VISIBLE
        // v0.11.9: the GT button hides around analysis captures on the
        // same pattern — it must never grade itself either. (Scout
        // captures do not hide it: it is static except on tap, sits in
        // ~2 grid cells at the bottom-end corner, and a tap's one-tick
        // change there is far below the region tracker's area floor.)
        gtHiddenForCapture = gtButton != null && gtButton!!.visibility == View.VISIBLE
        // v0.16.0: the score dots hide around captures on the same
        // pattern — display furniture must never grade itself.
        dotsHiddenForCapture = dotsRoot != null && dotsRoot!!.visibility == View.VISIBLE
        if (debugHiddenForCapture || gtHiddenForCapture || dotsHiddenForCapture) {
            if (debugHiddenForCapture) debugView?.visibility = View.INVISIBLE
            if (gtHiddenForCapture) gtButton?.visibility = View.INVISIBLE
            if (dotsHiddenForCapture) dotsRoot?.visibility = View.INVISIBLE
            mainHandler.postDelayed({ doCapture(capPkg, captureNext) }, FLICKER_HIDE_MS)
        } else {
            doCapture(capPkg, captureNext)
        }
    }

    /** Restore the debug readout after a capture, whatever happened. */
    private fun restoreFadeView() {
        if (debugHiddenForCapture) {
            debugHiddenForCapture = false
            if (Prefs.debugOverlay(this)) debugView?.visibility = View.VISIBLE
        }
        if (gtHiddenForCapture) {
            gtHiddenForCapture = false
            if (Prefs.debugOverlay(this)) gtButton?.visibility = View.VISIBLE
        }
        if (dotsHiddenForCapture) {
            dotsHiddenForCapture = false
            // The window only exists while it should be shown
            // (syncScoreDots owns existence); restore plainly.
            dotsRoot?.visibility = View.VISIBLE
        }
    }

    // ---- v0.5.2: live debug readout (small, top-left; Alejandro's order) ----

    private var debugView: TextView? = null
    private var debugHiddenForCapture = false
    // v0.11.9: the GT button — a separate, TAPPABLE window (the
    // readout is FLAG_NOT_TOUCHABLE, so the button cannot ride in it).
    private var gtButton: TextView? = null
    private var gtHiddenForCapture = false
    private var gtShownState: String? = null // "idle" | "open" — render cache, like overlayShown
    // v0.16.0: the score-dots window — dots + hide chip in one
    // small TAPPABLE window (tappable for the chip, like the GT
    // button; the debug readout itself is NOT_TOUCHABLE). It sits
    // directly under the readout, clustered with the SUS MODE line.
    private var dotsRoot: LinearLayout? = null
    private var dotsView: ScoreDotsView? = null
    private var dotsParams: WindowManager.LayoutParams? = null
    private var dotsHiddenForCapture = false
    private var dotsShownKey: String? = null // "pkg:strikes:points" render cache
    // v0.11.4: the readout's last body + package, kept so the SUS
    // MODE line can be added/dropped as suspicion comes and goes
    // BETWEEN analysed frames (refreshOverlay re-renders from this).
    @Volatile private var overlayBase: String? = null
    @Volatile private var overlayBasePkg: String? = null
    private var overlayShown: String? = null

    /** Add/remove the readout window to match the Settings toggle. */
    private fun syncDebugOverlay() {
        val want = Prefs.debugOverlay(this)
        if (want && debugView == null) {
            mainHandler.post {
                if (debugView != null || !Prefs.debugOverlay(this)) return@post
                val tv = TextView(this).apply {
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(android.graphics.Color.rgb(230, 237, 247))
                    // UI-polish Run 2 (presentation only): rounded ink-navy
                    // chip instead of the flat translucent black box.
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.argb(205, 11, 18, 32))
                        cornerRadius = 8 * resources.displayMetrics.density
                        setStroke((1 * resources.displayMetrics.density).toInt(),
                            android.graphics.Color.argb(160, 56, 189, 248))
                    }
                    setPadding(16, 10, 16, 10)
                    text = "ScreenGuard debug…"
                }
                val density = resources.displayMetrics.density
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (6 * density).toInt()
                    y = (6 * density).toInt()
                }
                try {
                    (getSystemService(WINDOW_SERVICE) as WindowManager).addView(tv, params)
                    debugView = tv
                    overlayShown = null // v0.11.4: force the re-render below onto the fresh view
                    refreshOverlay()
                    DebugLog.log(this, "DEBUG overlay shown")
                } catch (e: Exception) {
                    failLog("other", "overlay debug addView ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } else if (!want && debugView != null) {
            val v = debugView
            debugView = null
            overlayShown = null // v0.11.4: nothing is shown while the view is gone
            mainHandler.post {
                try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {
                    failLog("other", "window removeView (debug overlay) ${e.javaClass.simpleName}: ${e.message}")
                }
                DebugLog.log(this, "DEBUG overlay hidden")
            }
        }
    }

    /** Update the readout text (no-op while the overlay is off). */
    private fun setDebugText(text: String) {
        val v = debugView ?: return
        mainHandler.post { if (debugView === v) v.text = text }
    }

    /** v0.11.4: set the readout body for [pkg] and render it via
     *  [refreshOverlay] (which appends the SUS MODE line while
     *  suspicion is live for that package). */
    private fun showOverlay(pkg: String, body: String) {
        overlayBase = body
        overlayBasePkg = pkg
        refreshOverlay()
    }

    /**
     * v0.11.4: re-render the stored readout body, adding or dropping
     * the literal "SUS MODE" line to match the live suspicion state.
     * v0.11.12: a "PARANOID" line rides the same mechanism while
     * the paranoid window is open.
     * Called from tick() (so expiry drops the marker within a poll),
     * from noteSus / clearSus, and whenever a fresh body is set.
     * Same visibility rules as the rest of the overlay: no view, no
     * marker. Text changes only — never a window operation.
     */
    private fun refreshOverlay() {
        syncScoreDots() // v0.16.0: the score dots ride the same refresh path as the SUS MODE line
        if (debugView == null) return
        val body = overlayBase ?: return
        val pkg = overlayBasePkg ?: return
        var want = body
        if (susActive(pkg)) want += "\nSUS MODE"
        // v0.11.12: a PARANOID line rides the same mechanism while
        // the paranoid window is open (the window is global state,
        // so any package's readout carries it; its teeth apply
        // only while the launcher is in front).
        if (paranoidActive()) want += "\nPARANOID"
        // v0.17.0: a CALL HOLD line rides the same mechanism
        // while a live session hold is armed (global state, like
        // PARANOID; its teeth are per-package).
        if (callHoldPkg != null) want += "\nCALL HOLD"
        // v0.11.13: a BLIND line rides the same mechanism while the
        // foreground package is starved of analyses (computed in
        // tick; display only — nothing acts on it in this release).
        blindLabel?.let { want += "\n$it" }
        if (want != overlayShown) {
            overlayShown = want
            setDebugText(want)
        }
    }

    // ---- v0.16.0: score dots (Alejandro's order) ----
    // Two dot rows under the debug readout: RED = full-screen
    // strikes toward FULL_STRIKES_TO_CONVICT, YELLOW = crop points
    // toward CROP_ACC_TARGET, always for the currently judged
    // package. Shown while that package's SUS is live or either
    // counter is above zero; hidden when calm at zero. A display
    // feature only: the dots read the counters, never feed them,
    // and the window hides around captures exactly like the
    // readout and the GT chip. The ✕ chip beside the dots writes
    // the score_dots pref off (the Settings "Score dots" switch
    // is the same pref, and the way back on).

    /** Top offset for the dots window: directly under the debug
     *  readout when it is up, else the readout's own spot. */
    private fun dotsTopY(): Int {
        val density = resources.displayMetrics.density
        val base = (6 * density).toInt()
        val h = debugView?.height ?: 0
        return if (h > 0) base + h + (4 * density).toInt() else base
    }

    /** Reconcile the dots window with the pref and the live
     *  counters. Called from tick (beside syncDebugOverlay) and
     *  from refreshOverlay on every state change. Window work is
     *  posted to the main thread, like the other overlay syncs. */
    private fun syncScoreDots() {
        val pkg = overlayBasePkg ?: streakPkg ?: foregroundPkg
        val strikes = if (pendingBlockPkg == pkg) pendingHits else 0
        val points = accByPkg[pkg]?.points ?: 0
        val show = Prefs.scoreDots(this) && pkg.isNotBlank() &&
            (strikes > 0 || points > 0 || susActive(pkg))
        if (!show) {
            val v = dotsRoot ?: return
            dotsRoot = null
            dotsView = null
            dotsParams = null
            dotsShownKey = null
            mainHandler.post {
                try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {
                    failLog("other", "window removeView (score dots) ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            return
        }
        if (dotsRoot == null) {
            mainHandler.post {
                if (dotsRoot != null || !Prefs.scoreDots(this)) return@post
                val density = resources.displayMetrics.density
                val dots = ScoreDotsView(this).apply {
                    strikesMax = FULL_STRIKES_TO_CONVICT
                    pointsMax = CROP_ACC_TARGET
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.argb(205, 11, 18, 32))
                        cornerRadius = 8 * density
                        setStroke((1 * density).toInt(),
                            android.graphics.Color.argb(160, 56, 189, 248))
                    }
                }
                val chip = TextView(this).apply {
                    text = "✕"
                    textSize = 12f
                    setTextColor(android.graphics.Color.rgb(230, 237, 247))
                    gravity = Gravity.CENTER
                    setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                    setOnClickListener {
                        Prefs.setScoreDots(this@GuardService, false)
                        DebugLog.log(this@GuardService, "Score dots hidden from overlay chip")
                        syncScoreDots()
                    }
                }
                val root = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(dots)
                    addView(chip)
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (6 * density).toInt()
                    y = dotsTopY()
                }
                try {
                    (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, params)
                    dotsRoot = root
                    dotsView = dots
                    dotsParams = params
                    dotsShownKey = null // force the render below onto the fresh view
                    renderScoreDots()
                } catch (e: Exception) {
                    failLog("other", "overlay score-dots addView ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } else if ("$pkg:$strikes:$points" != dotsShownKey) {
            renderScoreDots()
        }
    }

    /** Push the live counters into the dots view (main thread);
     *  also re-seats the window under the readout if the readout
     *  changed height (SUS / BLIND lines come and go). */
    private fun renderScoreDots() {
        val dots = dotsView ?: return
        val root = dotsRoot ?: return
        val pkg = overlayBasePkg ?: streakPkg ?: foregroundPkg
        val strikes = if (pendingBlockPkg == pkg) pendingHits else 0
        val points = accByPkg[pkg]?.points ?: 0
        dotsShownKey = "$pkg:$strikes:$points"
        val y = dotsTopY()
        val params = dotsParams
        mainHandler.post {
            if (dotsView !== dots) return@post
            dots.strikes = strikes
            dots.points = points
            dots.invalidate()
            if (params != null && params.y != y) {
                params.y = y
                try {
                    (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(root, params)
                } catch (e: Exception) { /* racing a removal — the next sync reconciles */ }
            }
        }
    }

    // ---- v0.11.9: GT button (Alejandro's ground-truth tap) ----
    // A small tappable chip anchored bottom-end, clear of the
    // top-left readout. Its lifecycle follows the debug-overlay
    // toggle (syncGtButton, called from tick beside
    // syncDebugOverlay). The button's appearance reflects ONLY the
    // GT interval state — idle: muted grey "GT ○"; open: solid red
    // "GT ●" — nothing else (in particular NOT suspicion) drives it.

    /** Add/remove the GT button window to match the debug-overlay toggle. */
    private fun syncGtButton() {
        val want = Prefs.debugOverlay(this)
        if (want && gtButton == null) {
            mainHandler.post {
                if (gtButton != null || !Prefs.debugOverlay(this)) return@post
                val density = resources.displayMetrics.density
                val tv = TextView(this).apply {
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                    setOnClickListener { onGtTap() }
                }
                val params = WindowManager.LayoutParams(
                    (58 * density).toInt(),
                    (48 * density).toInt(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    x = (12 * density).toInt()
                    y = (12 * density).toInt()
                }
                try {
                    (getSystemService(WINDOW_SERVICE) as WindowManager).addView(tv, params)
                    gtButton = tv
                    gtShownState = null // force the render below onto the fresh view
                    refreshGtButton()
                    DebugLog.log(this, "GT button shown")
                } catch (e: Exception) {
                    failLog("other", "overlay GT-button addView ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } else if (!want && gtButton != null) {
            val v = gtButton
            gtButton = null
            gtShownState = null
            mainHandler.post {
                try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {
                    failLog("other", "window removeView (GT button) ${e.javaClass.simpleName}: ${e.message}")
                }
                DebugLog.log(this, "GT button hidden")
            }
        }
    }

    /** GT button tap (main thread, via the click listener): toggle
     *  the interval. Opening names the CURRENT foreground package —
     *  the same window resolution the guard itself trusts. */
    private fun onGtTap() {
        if (gtPkg != null) {
            closeGt("tap")
            return
        }
        val pkg = (lastWinPkg ?: foregroundPkg).ifBlank { streakPkg ?: "" }
        if (pkg.isBlank()) return
        openGt(pkg)
    }

    /** Re-render the button for the live GT state (no-op when the
     *  rendered state already matches). Colour is the state: muted
     *  grey outline style when idle, solid red while an interval is
     *  open — readable at a glance over video content. */
    private fun refreshGtButton() {
        val v = gtButton ?: return
        val state = if (gtPkg != null) "open" else "idle"
        if (state == gtShownState) return
        gtShownState = state
        mainHandler.post {
            if (gtButton !== v) return@post
            val density = resources.displayMetrics.density
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * density
                if (state == "open") {
                    setColor(android.graphics.Color.rgb(204, 0, 0))
                    setStroke((2 * density).toInt(), android.graphics.Color.rgb(122, 0, 0))
                } else {
                    setColor(android.graphics.Color.argb(110, 48, 48, 48))
                    setStroke((2 * density).toInt(), android.graphics.Color.argb(225, 175, 175, 175))
                }
            }
            v.background = bg
            v.setTextColor(
                if (state == "open") android.graphics.Color.WHITE
                else android.graphics.Color.argb(235, 215, 215, 215)
            )
            v.text = if (state == "open") "GT ●" else "GT ○"
        }
    }

    /** v0.10.10: keep the purple crop outline in step with the debug
     *  overlay toggle and the tracker's ACTIVE region (Alejandro's
     *  visual check on the tracker). Shown only while the debug
     *  overlay is on AND a region is active for the CURRENT foreground
     *  package; hidden on region suspend/drop, package change, or
     *  toggle-off. Cosmetic and failure-isolated: any window error
     *  just drops the outline, never the guard. The tracker box is in
     *  grid cells over the captured frame; the capture IS the display,
     *  so the same cell→pixel mapping as cropToRegion gives display
     *  coordinates directly. Called every tick.
     *  v0.11.9: the stroke is PURPLE normally and RED while SUS mode
     *  is active for the outlined package (active and retained boxes
     *  alike), returning to purple when suspicion ends. */
    private fun syncRegionOutline() {
        try {
            val box = if (Prefs.debugOverlay(this)) regionVisBox else null
            val curPkg = lastWinPkg ?: foregroundPkg
            val show = box != null && regionVisPkg == curPkg && regionVisW > 0 && regionVisH > 0
            if (!show) {
                if (outlineView != null) {
                    val v = outlineView
                    outlineView = null
                    outlineKey = null
                    mainHandler.post {
                        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {
                            failLog("other", "window removeView (region outline) ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                    DebugLog.log(this, "REGION outline off")
                }
                return
            }
            val b = box!!
            val l = b.l * regionVisW / MotionRegionTracker.COLS
            val t = b.t * regionVisH / MotionRegionTracker.ROWS
            val r = b.r * regionVisW / MotionRegionTracker.COLS
            val bot = b.b * regionVisH / MotionRegionTracker.ROWS
            // v0.11.9: the outline's colour carries the SUS state for
            // the outlined package — purple normally, RED while SUS
            // is active (Alejandro's correction of the v0.11.4 fixed
            // purple). The colour rides in the key so a SUS flip with
            // an unmoved box still re-enters the update path below.
            val colorHex = if (susActive(curPkg)) RegionOutlineView.OUTLINE_COLOR_SUS_HEX
                else RegionOutlineView.OUTLINE_COLOR_HEX
            val key = "$curPkg:$l,$t,$r,$bot:$colorHex"
            if (outlineView != null && outlineKey == key) return
            outlineKey = key
            mainHandler.post {
                try {
                    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                    val w = (r - l).coerceAtLeast(2)
                    val h = (bot - t).coerceAtLeast(2)
                    val existing = outlineView
                    if (existing == null) {
                        val v = RegionOutlineView(this)
                        v.setOutlineColor(colorHex) // v0.11.9: purple, or red while SUS is active
                        val params = WindowManager.LayoutParams(
                            w, h,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT
                        ).apply {
                            gravity = Gravity.TOP or Gravity.START
                            x = l; y = t
                        }
                        wm.addView(v, params)
                        outlineView = v
                        DebugLog.log(this, "REGION outline on box=[$l,$t,$r,$bot] pkg=$curPkg")
                    } else {
                        existing.setOutlineColor(colorHex) // v0.11.9: follows SUS flips on an unmoved box
                        val params = existing.layoutParams as WindowManager.LayoutParams
                        params.x = l; params.y = t
                        params.width = w; params.height = h
                        wm.updateViewLayout(existing, params)
                    }
                } catch (e: Exception) {
                    // Cosmetic only — never let it near the pipeline.
                    // v0.11.14: was a bare swallow; marked via the
                    // throttled channel (this path runs per tick).
                    failLogThrottled("outline", "other", "region outline window ${e.javaClass.simpleName}: ${e.message}")
                    outlineView = null
                    outlineKey = null
                }
            }
        } catch (e: Exception) {
            // Cosmetic only — never let it near the pipeline.
            failLogThrottled("outline", "other", "region outline sync ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun doCapture(pkg: String, next: Long) {
        val gen = ++captureGen
        // v0.11.13: stamp the shared channel at ISSUE — every
        // consumer's floor arithmetic (and the ALIVE health
        // counters) keys off this moment, not off callback timing.
        lastCaptureIssuedMs = System.currentTimeMillis()
        capAttAll++
        // v0.13.1: a running benchmark counts only its own stage's
        // captures (the scout stands down in scoutTick while
        // benchActive; other packages' captures are not the phase's).
        if (benchActive && pkg == packageName) {
            benchPhaseAtt++
            // v0.13.3: the issued capture advances the benchmark's
            // own schedule by the phase interval, anchored at the
            // issue stamp — whatever price this capture's callbacks
            // bake into nextCaptureDueMs, the tick's bench clamp
            // prices the NEXT slot from this schedule.
            benchRearm(lastCaptureIssuedMs, "issue")
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    if (gen != captureGen) {
                        // v0.4.0: zombie callback from a watchdog-unwedged
                        // capture — close its buffer and touch nothing else.
                        try { result.hardwareBuffer.close() } catch (e: Exception) {
                            failLog("capture", "capture zombie buffer close ${e.javaClass.simpleName}: ${e.message} pkg=$pkg")
                        }
                        DebugLog.log(this@GuardService, "CAP late onSuccess discarded (gen=$gen, current=$captureGen) pkg=$pkg")
                        return
                    }
                    restoreFadeView()
                    Prefs.bump(this@GuardService, "cap_ok")
                    capOkAll++
                    govNoteCapture(true, 0) // v0.13.0: governor window
                    // v0.13.1: phase counters + the captures-basis
                    // fallback count the benchmark stage's captures only.
                    if (benchActive && pkg == packageName) {
                        benchPhaseOk++
                        benchCaptureOk++
                    }
                    // v0.11.13: this capture is now an analysis of
                    // pkg — the BLIND / lastOkAge health measures
                    // count from here.
                    lastAnalysisOkPkg = pkg
                    lastAnalysisOkMs = System.currentTimeMillis()
                    val hw = result.hardwareBuffer
                    dbg("CAP ok ${hw.width}x${hw.height} pkg=$pkg")
                    val bmp = hardwareBufferToBitmap(hw)
                    hw.close()
                    if (bmp == null) {
                        // v0.11.14: the FAIL line itself (with the
                        // exception identity) is emitted inside
                        // hardwareBufferToBitmap; this is its context.
                        DebugLog.log(this@GuardService, "CAP bitmap unusable pkg=$pkg (FAIL logged above)")
                        busy = false
                        nextCaptureDueMs = System.currentTimeMillis() + next
                        scheduleNext(); return
                    }
                    inferExec.execute {
                        try {
                            processFrame(bmp, pkg)
                        } catch (e: Exception) {
                            failLog("model", "infer crash ${e.javaClass.simpleName}: ${e.message} pkg=$pkg")
                        } finally {
                            busy = false
                        }
                    }
                    nextCaptureDueMs = System.currentTimeMillis() + next
                    scheduleNext()
                }

                override fun onFailure(errorCode: Int) {
                    if (gen != captureGen) {
                        DebugLog.log(this@GuardService, "CAP late onFailure discarded (gen=$gen, current=$captureGen) pkg=$pkg")
                        return
                    }
                    restoreFadeView()
                    Prefs.bump(this@GuardService, "cap_fail")
                    capFailByCode = capFailByCode + (errorCode to ((capFailByCode[errorCode] ?: 0) + 1))
                    govNoteCapture(false, errorCode) // v0.13.0: governor window
                    busy = false
                    if (errorCode == 3) {
                        // v0.11.13: INTERVAL_TIME_SHORT is a SCHEDULING
                        // signal, not an error — the capture was asked
                        // for inside the platform's 1000 ms floor (the
                        // floor gate makes this rare: another consumer
                        // of the channel, e.g. a wedged-callback retry,
                        // can still beat us to a slot). NO backoff: the
                        // analysis retries at the next legal slot. The
                        // pre-v0.11.13 code doubled the wait (to 6 s in
                        // the field) on exactly this code — the
                        // punishment WAS the blindfold. (Its cadence
                        // "learner" also keyed on -5, a code this API
                        // never sends, so it never fired; removed.)
                        failLog(
                            "capture",
                            "capture code=$errorCode (${errorName(errorCode)}) pkg=$pkg retry=next legal slot (no backoff)"
                        )
                        nextCaptureDueMs = lastCaptureIssuedMs + CAPTURE_FLOOR_MS
                    } else {
                        // Any other failure keeps a modest backoff —
                        // the normal interval plus a penalty capped at
                        // SCOUT_MAX_PERIOD_MS (2000 ms) — never the old
                        // open-ended doubling.
                        val backoffMs = minOf(next, SCOUT_MAX_PERIOD_MS)
                        failLog(
                            "capture",
                            "capture code=$errorCode (${errorName(errorCode)}) pkg=$pkg backoff=${backoffMs}ms"
                        )
                        nextCaptureDueMs = System.currentTimeMillis() + next + backoffMs
                    }
                    scheduleNext()
                }
            })
        } catch (e: Exception) {
            restoreFadeView()
            failLog("capture", "capture exception ${e.javaClass.simpleName}: ${e.message} pkg=$pkg")
            govNoteCapture(false, -1) // v0.13.0: an issued attempt that threw is a failed attempt (no platform code)
            busy = false
            nextCaptureDueMs = System.currentTimeMillis() + next + minOf(next, SCOUT_MAX_PERIOD_MS)
            scheduleNext()
        }
    }

    // v0.11.13: the TRUE AOSP TakeScreenshotCallback error table
    // (AccessibilityService ERROR_TAKE_SCREENSHOT_* constants, all
    // POSITIVE). The pre-v0.11.13 table mapped negative codes this
    // API never sends, so every failure — hundreds of code 3s —
    // logged "(unknown)" and the INTERVAL_TIME_SHORT mechanism sat
    // unread in the logs for days.
    private fun errorName(code: Int): String = when (code) {
        1 -> "INTERNAL_ERROR"
        2 -> "NO_ACCESSIBILITY_ACCESS"
        3 -> "INTERVAL_TIME_SHORT"
        4 -> "INVALID_DISPLAY"
        5 -> "INVALID_WINDOW"
        6 -> "SECURE_WINDOW"
        else -> "unknown"
    }

    private fun hardwareBufferToBitmap(buffer: HardwareBuffer): Bitmap? {
        return try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, null)
            if (hw == null) {
                // v0.11.14: a null wrap is a conversion failure with
                // no exception to name — mark it here so neither
                // consumer's null-bitmap path is a silent dead end.
                failLog("capture", "bitmap conversion wrapHardwareBuffer returned null")
                return null
            }
            // v0.1.6 tried Canvas.drawBitmap into a software bitmap: FAILED on
            // 100% of captures — Android forbids drawing HARDWARE bitmaps into
            // software canvases ("software rendering doesn't support hardware
            // bitmaps"). Reverted to copy() (v0.1.6), which mechanically works;
            // whether its PIXELS are faithful is what the v0.1.6 capture-bench
            // determines (fullscreen known photo -> screenshot -> detector).
            val copy = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            copy
        } catch (e: Exception) {
            // v0.11.14: was a bare swallow returning null — the
            // frame vanished with no trace of why. Marked with the
            // exception identity; callers add their package context.
            failLog("capture", "bitmap conversion ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 4x4 mean-luminance grid of a thumbnail (16 ints): lets us "see" a frame's
     *  structure (photo vs thumbnail-grid vs scramble) in the log without ever
     *  saving an image. */
    private fun grid4x4(thumb: IntArray): String {
        val sb = StringBuilder()
        for (gy in 0 until 4) {
            for (gx in 0 until 4) {
                var sum = 0; var n = 0
                for (y in gy * 6 until (gy + 1) * 6) {
                    for (x in gx * 8 until (gx + 1) * 8) {
                        sum += thumb[y * 32 + x]; n++
                    }
                }
                sb.append(sum / n).append(',')
            }
        }
        return sb.toString().trimEnd(',')
    }

    /** 32x24 greyscale thumbnail used for cheap frame-change detection. */
    private fun thumbnail(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, 32, 24, true)
        val px = IntArray(32 * 24)
        small.getPixels(px, 0, 32, 0, 0, 32, 24)
        if (small !== bmp) small.recycle()
        return IntArray(px.size) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
        }
    }

    /** v0.10.8: 32x18 mean-luminance grid for the motion-region tracker
     *  (the same downscale-to-cells trick as [thumbnail], at the grid
     *  size the offline motion prototype was benched with). */
    private fun trackerGrid(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, MotionRegionTracker.COLS, MotionRegionTracker.ROWS, true)
        val px = IntArray(MotionRegionTracker.CELLS)
        small.getPixels(px, 0, MotionRegionTracker.COLS, 0, 0, MotionRegionTracker.COLS, MotionRegionTracker.ROWS)
        if (small !== bmp) small.recycle()
        return IntArray(px.size) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
        }
    }

    /** v0.10.8: crop [bmp] to a tracker cell box (pixel coords, clamped, min 1 px). */
    private fun cropToRegion(bmp: Bitmap, box: MotionRegionTracker.Box): Bitmap? {
        val l = (box.l * bmp.width / MotionRegionTracker.COLS).coerceIn(0, bmp.width - 1)
        val t = (box.t * bmp.height / MotionRegionTracker.ROWS).coerceIn(0, bmp.height - 1)
        val r = (box.r * bmp.width / MotionRegionTracker.COLS).coerceIn(l + 1, bmp.width)
        val b = (box.b * bmp.height / MotionRegionTracker.ROWS).coerceIn(t + 1, bmp.height)
        return try {
            Bitmap.createBitmap(bmp, l, t, r - l, b - t)
        } catch (e: Exception) {
            // v0.11.14: was a bare swallow — a failed crop silently
            // skipped the crop pass for the frame. Marked via the
            // throttled channel (this runs per analysed frame).
            failLogThrottled("crop", "other", "region crop ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Mean absolute greyscale difference vs the previous frame; also stores the new thumb. */
    private fun frameDiff(thumb: IntArray): Double {
        val prev = lastThumb ?: run { lastThumb = thumb; return 999.0 }
        if (prev.size != thumb.size) { lastThumb = thumb; return 999.0 }
        var diff = 0L
        for (i in thumb.indices) diff += abs(thumb[i] - prev[i])
        lastThumb = thumb
        return diff.toDouble() / thumb.size
    }

    private var staticStreak = 0

    /**
     * v0.14.0 dispatch — the analysis entry point doCapture's
     * callback has always called. Reads the capture tick's stack
     * snapshot (inFlightStack, stamped in tick):
     *  - no snapshot / no judgeable window → the legacy engine on
     *    the label package, untouched (benchmark, capture-bench,
     *    stack-read failure);
     *  - exactly ONE judgeable window → the legacy engine on THAT
     *    window's package: the PiP-over-Settings headline case runs
     *    the full v0.13.5 machinery, merely aimed at the window
     *    that is actually showing content;
     *  - two or more judgeable windows → processFrameMulti.
     */
    private fun processFrame(bmp: Bitmap, pkg: String) {
        analysisFalPasses = 0
        analysisClsPasses = 0
        // v0.14.5: the sanity stamp outranks the stack dispatch —
        // a sanity capture's snapshot has an empty judged set,
        // which the dispatch below would otherwise hand to the
        // legacy engine WITH its conviction machinery. The stamp
        // is the gate: sanity frames go only to
        // processFrameSanity.
        val sanityPkg = inFlightSanityPkg
        inFlightSanityPkg = null
        if (sanityPkg != null) {
            processFrameSanity(bmp, sanityPkg)
            return
        }
        val snap = inFlightStack
        inFlightStack = null
        when {
            snap == null || snap.judged.isEmpty() -> processFrameLegacy(bmp, pkg)
            snap.judged.size == 1 -> processFrameLegacy(bmp, snap.judged[0].pkg!!)
            else -> processFrameMulti(bmp, snap)
        }
    }

    /**
     * v0.14.5 — THE SANITY PATH, and the structural innocence
     * gate for exempt packages (Settings, SystemUI, ScreenGuard
     * itself, the permission controllers). A sanity frame is
     * run through both models on the FULL FRAME ONLY — there
     * are no judgeable windows on an exempt-only screen, so no
     * window crops exist, and no ZoD burst runs (it would have
     * no conviction consumer) — and it feeds the tracker, the
     * SCORE history, the PERF ring and the GT peaks, so the
     * guard is observably alive on these surfaces.
     *
     * STRUCTURAL INNOCENCE: this function deliberately contains
     * NO reference to the conviction machinery — no
     * handleSignal, no whole-frame pending, no crop
     * accumulator, no secure-streak, no ZoD, no executeBlock,
     * no quarantine. Its scores' ONLY effects are the SUS
     * triggers at the bottom (noteSus / noteSusCls — an exempt
     * surface rendering hot content densifies to 1 s while it
     * lasts, and the screen is already hot if a judgeable
     * window later appears over it) and the GT peaks. The
     * secure/incognito path cannot fire here either: BROWSERS
     * and the exempt sets are disjoint by construction (a
     * browser is judgeable, never a sanity target), and this
     * path never runs the black-frame test. A block can
     * therefore never name an exempt package; executeBlock
     * additionally refuses exempt packages outright, as a
     * second wall behind this gate.
     */
    private fun processFrameSanity(bmp: Bitmap, pkg: String) {
        // Cooldown blind window (legacy parity): right after a
        // block elsewhere, skip frames entirely rather than let
        // the cover's own pixels feed anything.
        if (System.currentTimeMillis() < cooldownUntilMs) {
            bmp.recycle()
            return
        }
        val fal = falModel
        if (fal == null) {
            // Dropped-model visibility (legacy parity): a latched-off
            // Falconsai turns the guard into a theatre prop while
            // still capturing (the exact shape of the NudeNet
            // lesson). Logged ONCE through failLog at the legacy
            // site; this path just drops the frame.
            if (!falDropLogged) {
                falDropLogged = true
                failLog("model", "frame dropped (sanity): Falconsai not available (see the FAL state line at service start) pkg=$pkg")
            }
            bmp.recycle()
            return
        }
        val thumb = thumbnail(bmp)
        val lum = thumb.average()
        // Tracker feed (legacy parity): the motion-region
        // tracker keeps learning this surface, so if a judgeable
        // window appears over it the region state is warm. The
        // region box is never mirrored to the overlay here —
        // exempt surfaces carry no outline.
        beginRegionSession(pkg)
        try {
            regionTracker.onFrame(pkg, trackerGrid(bmp))
        } catch (e: Exception) {
            if (!regionErrorLogged) {
                regionErrorLogged = true
                failLog("other", "region tracker ${e.javaClass.simpleName}: ${e.message} (region disabled, whole-frame continues)")
            }
        }
        val falFull = falScore(fal, bmp)
        var falMsTotal = fal.lastMs
        var clsMsTotal = 0L
        var clsFull: Float? = null
        val cls = clsModel
        if (falFull != null && cls != null) {
            clsFull = clsScore(cls, bmp)
            if (clsFull != null) {
                clsMsTotal += cls.lastMs
            }
        }
        bmp.recycle()
        if (falFull == null) return
        checks++
        Prefs.putStatus(this, "last_check_ms", System.currentTimeMillis())
        Prefs.putStatus(this, "checks", checks)
        Prefs.putStatus(this, "last_top_class", "falconsai nsfw")
        Prefs.putStatus(this, "last_top_score", falFull)
        Prefs.putStatus(this, "last_latency_ms", falMsTotal)
        dbg("ANALYSED pkg=$pkg sanity=1 lum=${"%.1f".format(lum)} falMs=$falMsTotal clsMs=$clsMsTotal grid=[${grid4x4(thumb)}]")
        // v0.13.0 PERF ring (legacy parity): sanity analyses
        // moved the needle, so they count — and they feed the
        // governor's latency arm like any other pass-set.
        perfSamples.addLast(longArrayOf(falMsTotal, clsMsTotal, analysisFalPasses.toLong(), analysisClsPasses.toLong()))
        while (perfSamples.size > PERF_RING) perfSamples.removeFirst()
        // v0.11.9 GT peaks (instrument only): a GT interval
        // opened on an exempt surface still records what the
        // models saw — it can never record a block.
        if (gtPkg == pkg) {
            if (falFull > gtPeakFalF) gtPeakFalF = falFull
            val cf = clsFull ?: -1f
            if (cf > gtPeakClsF) gtPeakClsF = cf
            if (gtFirstHotElapsedMs < 0L && falFull >= FAL_STRONG_BAR) {
                gtFirstHotElapsedMs = System.currentTimeMillis() - gtStartMs
            }
        }
        val f2 = { v: Float? -> if (v != null) "%.2f".format(v) else "-" }
        ScoreHistory.record(
            ScoreHistory.Sample(
                tsMs = System.currentTimeMillis(),
                pkg = pkg,
                falFull = falFull,
                falCrop = null,
                clsFull = clsFull,
                clsCrop = null,
                falZoom = null
            )
        )
        DebugLog.log(this, "SCORE pkg=$pkg falF=${f2(falFull)} falC=- falZ=- clsF=${f2(clsFull)} clsC=- stack=0 sanity=1")
        DebugLog.log(this, "SANITY analysed pkg=$pkg falF=${f2(falFull)} clsF=${f2(clsFull)} — exempt surface: SUS trips only, conviction machinery not fed")
        // SUS triggers — the ONLY behavioural output of a sanity
        // frame. (Falconsai full, then the classifier at this
        // package's trip bar, mirroring the legacy judgement
        // order's suspect sources.)
        if (falFull >= SUS_BAR) noteSus(pkg, falFull, "full-sanity")
        val cf2 = clsFull
        if (cf2 != null && cf2 >= clsTripBar(pkg)) noteSusCls(pkg, cf2, "cls-full-sanity")
        if (debugView != null) {
            val clsTxt = if (clsFull != null) "%.2f".format(clsFull) else "--"
            showOverlay(pkg, "$pkg\nFAL ${"%.2f".format(falFull)} san\nCLS $clsTxt san")
        }
    }

    private fun processFrameLegacy(bmp: Bitmap, pkg: String) {
        // Post-block blind window: never evaluate frames while our own
        // overlay / the Home transition may be on screen.
        if (System.currentTimeMillis() < cooldownUntilMs) {
            Prefs.bump(this, "cooldown_skip")
            dbg("COOLDOWN frame ignored pkg=$pkg (${cooldownUntilMs - System.currentTimeMillis()}ms left)")
            bmp.recycle(); return
        }
        val thumb = thumbnail(bmp)
        val lum = thumb.average()
        // v0.10.8: feed the motion-region tracker BEFORE any early
        // return below, so it sees every captured frame of this package
        // in capture order. The returned box (if a region is ACTIVE)
        // steers only the Falconsai crop pass further down;
        // nothing else in this frame's handling reads it.
        beginRegionSession(pkg) // v0.11.2: first-capture timing for REGION first (the scout usually starts the session)
        val regionBox = try {
            regionTracker.onFrame(pkg, trackerGrid(bmp))
        } catch (e: Exception) {
            if (!regionErrorLogged) {
                regionErrorLogged = true
                failLog("other", "region tracker ${e.javaClass.simpleName}: ${e.message} (region disabled, whole-frame continues)")
            }
            null
        }
        // v0.10.10: mirror the tracker's verdict for the visual crop
        // outline (cosmetic only — syncRegionOutline reads this from
        // tick(); a suspended/dropped region returns null here, which
        // is exactly what hides the outline; since v0.11.1 a RETAINED
        // region returns its last-known box, so the outline follows
        // it through stillness too).
        regionVisBox = regionBox
        if (regionBox != null) {
            regionVisPkg = pkg
            regionVisW = bmp.width
            regionVisH = bmp.height
            noteRegionFirst(pkg) // v0.11.2 instrumentation (usually already logged by the scout feed)
        }
        // v0.12.2 ZoD: aim-lock maintenance against this analysis —
        // relocation (the tracker box has genuinely moved vs the
        // lock's parent: changed AND IoU < MOVE_IOU, the design's
        // test) and the time-based expiry are evaluated here, where
        // the fresh tracker box arrives; the cooled exit is
        // evaluated after scoring (zodAfterScored). While locked,
        // the outline shows the LOCKED box, not the tracker's.
        if (zodLockedFor(pkg)) {
            val parent = zodLockParent
            if (regionBox != null && parent != null && regionBox != parent &&
                zodIou(regionBox, parent) < MotionRegionTracker.MOVE_IOU
            ) {
                clearZodLock("reloc")
            }
            if (zodLockedFor(pkg) && System.currentTimeMillis() > zodLockExpiryMs) {
                clearZodLock("timeout")
            }
            if (zodLockedFor(pkg) && regionBox != null) {
                regionVisBox = zodFracToCellBox(zodLockBoxFrac!!)
            }
        }
        // SECURE-WINDOW (incognito) CHECK — before diff-skip and before
        // any model pass: secure content is composited BLACK, so no
        // model can ever see it; a static black frame would also be
        // diff-skipped forever. The blackness itself is the signal (see
        // constants), judged on luminance alone — it works even while
        // the model is unavailable.
        if (pkg in BROWSERS && Prefs.blockSecure(this)) {
            // v0.2.3: measure the INTERIOR of the frame (the central
            // 50% x 50% of the 32x24 thumb), not the whole frame. v0.2.2
            // field log 2026-10-25 22:05: the first secure frame fired FADE
            // START, but every later frame carried OUR OWN fade overlay —
            // the red border lit the edge cells (grid [17,4,4,17...]) and
            // the whole-frame mean rose to 13 — so the test failed, the
            // frame scored 0.00 as "content gone", the fade drained, the
            // overlay came off, the frame went pure black again: an endless
            // START/CLEAR cycle (~13 in 55 s) and incognito never blocked.
            // The interior reads ~4 under our overlay, and the leaky
            // status-bar strip along the top is excluded as well.
            var iSum = 0; var iBlack = 0; var iN = 0
            for (y in 6 until 18) {
                for (x in 8 until 24) {
                    val v = thumb[y * 32 + x]
                    iSum += v
                    if (v <= SECURE_BLACK_LUM) iBlack++
                    iN++
                }
            }
            val iFrac = iBlack.toDouble() / iN
            val iLum = iSum.toDouble() / iN
            if (iFrac >= SECURE_BLACK_FRAC && iLum <= SECURE_MEAN_LUM) {
                Prefs.bump(this, "secure_frames")
                DebugLog.log(
                    this,
                    "SECURE frame pkg=$pkg iFrac=${"%.2f".format(iFrac)} iLum=${"%.0f".format(iLum)} " +
                            "grid=[${grid4x4(thumb)}]"
                )
                bmp.recycle()
                if (debugView != null) showOverlay(pkg, "$pkg\nFAL — secure frame\n(no pixels — incognito)")
                handleSignal(pkg, secure = true, falFull = null, falCrop = null, fresh = true)
                return
            }
        }
        val diff = frameDiff(thumb)
        // v0.13.4: while a benchmark phase runs on our own surface,
        // its captures BYPASS the static gate below — every issued
        // bench capture is analysed and becomes a phase sample.
        // Diff-skip is a battery optimisation for real foregrounds;
        // inside a measurement it only destroys samples: the bench
        // pattern's motion (tiny bars drifting on the luminance
        // grid) reads diff 0.2–0.5 against the 3.0 bar, so the field
        // run of 2026-10-30 10:24 issued 8 captures, analysed 2 and
        // aborted the phase UNMEASURED. The bypass keys on the same
        // benchActive state (and own-package target) as the bench
        // schedule clamp in tick(); with no run live it is
        // unreachable and the gate behaves exactly as before.
        val benchBypass = benchActive && pkg == packageName
        if (benchBypass) {
            if (diff <= DIFF_THRESHOLD && !benchStaticBypassLogged) {
                benchStaticBypassLogged = true
                DebugLog.log(this, "BENCH static-gate bypassed for phase samples")
            }
        } else if (diff <= DIFF_THRESHOLD) {
            // Static screen. v0.1.3 field lesson: a static EXPLICIT image was
            // analysed once (possibly mid-transition) and then skipped forever.
            // Recheck every 8th static frame (~12 s) regardless of diff.
            staticStreak++
            if (staticStreak % 8 != 0) {
                if (pendingBlockPkg == pkg) {
                    // v0.6.0: a pending block suspends the diff-skip — its
                    // confirming frame must be a FRESH analysis, and it must
                    // not wait for the 8th-frame recheck. Fall through.
                    dbg("FRAME pkg=$pkg static — diff-skip suspended (block pending)")
                } else {
                    Prefs.bump(this, "diff_skip")
                    dbg("FRAME pkg=$pkg diff=${"%.1f".format(diff)} lum=${"%.0f".format(lum)} SKIP static #$staticStreak")
                    // v0.13.3: a diff-skipped bench frame still ends
                    // its slot (the animated stage never produces
                    // one, but the schedule must not depend on that).
                    if (benchActive && pkg == packageName) benchSlotDone("skip-late")
                    // A static screen means the last-seen content is STILL
                    // there: re-feed the response engine the last signal so a
                    // static explicit image keeps registering between the
                    // periodic rechecks instead of stalling.
                    if (streakPkg == pkg) handleSignal(pkg, secure = lastSignalSecure, falFull = lastFalFull, falCrop = lastFalCrop, fresh = false)
                    bmp.recycle(); return
                }
            }
            dbg("FRAME pkg=$pkg static recheck #$staticStreak lum=${"%.0f".format(lum)}")
        } else {
            staticStreak = 0
        }
        // v0.11.0: the whole engine. Pass A — Falconsai judges the whole
        // frame. Pass B — whenever the motion-region tracker hands us a
        // region (freshly derived, or RETAINED through stillness since
        // v0.11.1), Falconsai judges that region's crop as its own whole
        // picture (BENCH-ALL: Falconsai needs the crop for embedded
        // content and is a full/crop-full judge ONLY — no tiles exist
        // anymore). The secure check above already ran on luminance
        // alone, so a latched-off model never blinds the incognito
        // path; pixel grading simply waits for the model.
        // v0.11.6: alongside the judge, the restored classifier runs
        // its two passes as the SUS tripwire (see the CLS block
        // below) — it grades nothing, it only knocks.
        val fal = falModel
        if (fal == null) {
            if (!falDropLogged) {
                falDropLogged = true
                failLog("model", "frame dropped: Falconsai not available (see the FAL state line at service start) pkg=$pkg")
            }
            bmp.recycle(); return
        }
        val falFull = falScore(fal, bmp)
        val falFullMs = fal.lastMs
        var falCrop: Float? = null
        // v0.12.2 ZoD per-analysis state: the burst's best verdict
        // (observability only — SCORE falZ + the graph's FAL ZOOM
        // series), and whether this analysis's falC ends up
        // zoom-sourced — served from the aim lock, or the locking
        // verdict itself — which the accumulator's +1 zoom cap and
        // the lock's exit bookkeeping key off (handleInstantSignal /
        // zodAfterScored).
        var falZoom: Float? = null
        var cropServedFromLock = false
        var burstLockSourced = false
        var falMsTotal = falFullMs
        if (falFull != null) {
            DebugLog.log(this, "FAL pkg=$pkg src=full nsfw=${"%.2f".format(falFull)} falMs=$falFullMs")
        }
        // v0.11.6: the tripwire classifier reads the same pictures
        // (full frame here; the region crop inside Pass B below) —
        // STRUCTURAL INNOCENCE: clsFull/clsCrop are local to this
        // function, are written ONLY to the CLS/SCORE log lines and
        // read ONLY by the SUS trigger further down. handleSignal's
        // signature cannot receive them, so no code path leads from
        // a classifier score to a block, a pending park, or the
        // crop accumulator. Gated on falFull != null: the tripwire
        // exists to make Falconsai look more often — with Falconsai
        // dead there is nothing to densify (and a FAL run failure
        // just latched it off inside falScore).
        var clsFull: Float? = null
        var clsCrop: Float? = null
        // v0.13.0: total classifier cost of this analysis (both
        // passes), the CLS half of the PERF / benchmark cost record.
        var clsMsTotal = 0L
        val cls = clsModel
        if (falFull != null && cls != null) {
            clsFull = clsScore(cls, bmp)
            if (clsFull != null) {
                clsMsTotal += cls.lastMs
                DebugLog.log(this, "CLS pkg=$pkg src=full unsafe=${"%.2f".format(clsFull)} clsMs=${cls.lastMs}")
            }
        }
        // v0.11.1: Pass B is INDEPENDENT of Pass A's score — it runs
        // whenever the tracker hands us a region (fresh or retained),
        // even when the whole frame just read 0.00: embedded content
        // is precisely what a whole-frame verdict cannot see, so the
        // crop must never wait for the full screen to suspect
        // anything. The only gate below is the model being alive:
        // falFull == null means the run just failed and latched FAL
        // off inside falScore, so there is nothing to grade with.
        if (falFull != null && regionBox != null) {
            // v0.12.2 ZoD crop source (design §5.4): while the aim
            // lock is live for this package, the crop — Falconsai's
            // AND the classifier's, the same bitmap as always — is
            // taken from the LOCKED box instead of the tracker box;
            // the tracker keeps running underneath for relocation
            // detection. The pass still requires a served tracker
            // box: on a suspended interval (scroll) no crop runs,
            // exactly as before ZoD.
            val lockFrac = if (zodLockedFor(pkg)) zodLockBoxFrac else null
            val cropBmp = if (lockFrac != null) cropToFrac(bmp, lockFrac) else cropToRegion(bmp, regionBox)
            if (cropBmp != null) {
                if (lockFrac != null) cropServedFromLock = true
                falCrop = falScore(fal, cropBmp)
                val falCropMs = fal.lastMs
                falMsTotal += falCropMs
                if (falCrop != null) {
                    DebugLog.log(this, "FAL pkg=$pkg src=crop nsfw=${"%.2f".format(falCrop)} falMs=$falCropMs")
                }
                // v0.11.6: the tripwire judges the same crop bitmap
                // (before it is recycled) — its second and last pass
                // this frame. No tiles, ever, for either model.
                if (cls != null && clsFull != null) {
                    clsCrop = clsScore(cls, cropBmp)
                    if (clsCrop != null) {
                        clsMsTotal += cls.lastMs
                        DebugLog.log(this, "CLS pkg=$pkg src=crop unsafe=${"%.2f".format(clsCrop)} clsMs=${cls.lastMs}")
                    }
                }
                cropBmp.recycle()
            }
        }
        // v0.12.2 ZoD BURST (design §5.4): immediately after the
        // normal passes, before the SCORE line — a crop was served,
        // no lock is active, and the trigger gates inside
        // runZodBurst decide whether the classifier/Falconsai
        // disagreement earns Falconsai a tighter, motion-aimed
        // look. The bitmap is still alive here and is reused (the
        // zooms never capture). In LIVE mode a locking verdict
        // REPLACES falCrop for this analysis — it is scored by the
        // SUS trigger, GT peaks and handleInstantSignal exactly
        // like a crop verdict, under the +1 zoom-points cap; the
        // burst's best verdict rides along as falZoom for the
        // SCORE line and the graph, scored nowhere.
        if (falFull != null && falCrop != null && regionBox != null && !zodLockedFor(pkg)) {
            val (bz, lv) = runZodBurst(bmp, pkg, regionBox, fal, falFull, falCrop, clsFull, clsCrop)
            falZoom = bz
            if (lv != null) {
                falCrop = lv
                burstLockSourced = true
            }
        }
        lastFalFull = falFull
        lastFalCrop = falCrop
        bmp.recycle()
        if (falFull == null) return // a run failure latched FAL off inside falScore — nothing more to grade with
        checks++
        Prefs.putStatus(this, "last_check_ms", System.currentTimeMillis())
        Prefs.putStatus(this, "checks", checks)
        Prefs.putStatus(this, "last_top_class", "falconsai nsfw")
        Prefs.putStatus(this, "last_top_score", falFull)
        Prefs.putStatus(this, "last_latency_ms", falMsTotal)
        dbg(
            "ANALYSED pkg=$pkg diff=${"%.1f".format(diff)} lum=${"%.0f".format(lum)} falMs=$falMsTotal grid=[${grid4x4(thumb)}]"
        )
        // v0.13.0 PERF: this analysis's model costs join the rolling
        // ring (the ALIVE line's falMed/falP90/clsMed and the
        // governor's latency arm read it), and a running benchmark
        // scores them against its phase bars.
        // v0.14.0: the entry also carries this analysis's pass
        // counts ([2]=FAL passes, [3]=CLS passes) — the governor's
        // latency arm prices cost per pass-set from them (§5).
        perfSamples.addLast(longArrayOf(falMsTotal, clsMsTotal, analysisFalPasses.toLong(), analysisClsPasses.toLong()))
        while (perfSamples.size > PERF_RING) perfSamples.removeFirst()
        if (benchActive) {
            synchronized(benchAnalyses) { benchAnalyses.add(longArrayOf(falMsTotal, clsMsTotal)) }
            // v0.13.3: the bench slot this analysis occupied has
            // ended — if it overran the bench schedule, re-anchor.
            if (pkg == packageName) benchSlotDone("complete-late")
        }
        // Observability: record this analysed frame's scores for the
        // Home score graph (FAL FULL + FAL CROP; v0.11.7: CLS FULL +
        // CLS CROP ride along — full debug visibility for the
        // tripwire, Alejandro's order), and mirror them into the
        // debug log as one SCORE line so exported logs carry the
        // same data for offline plotting. Nothing in the detection
        // path reads either back.
        // v0.11.9: GT interval tracking rides the same fresh analysis
        // — peaks for all four passes and the first >= 0.90 Falconsai
        // verdict (either pass), for the interval's own package only.
        if (gtPkg == pkg) {
            if (falFull > gtPeakFalF) gtPeakFalF = falFull
            if (falCrop != null && falCrop > gtPeakFalC) gtPeakFalC = falCrop
            if (clsFull != null && clsFull > gtPeakClsF) gtPeakClsF = clsFull
            if (clsCrop != null && clsCrop > gtPeakClsC) gtPeakClsC = clsCrop
            if (gtFirstHotElapsedMs < 0L &&
                (falFull >= FAL_STRONG_BAR || (falCrop ?: 0f) >= FAL_STRONG_BAR)
            ) {
                gtFirstHotElapsedMs = System.currentTimeMillis() - gtStartMs
            }
        }
        run {
            ScoreHistory.record(
                ScoreHistory.Sample(
                    tsMs = System.currentTimeMillis(),
                    pkg = pkg,
                    falFull = falFull,
                    falCrop = falCrop,
                    clsFull = clsFull,
                    clsCrop = clsCrop,
                    falZoom = falZoom // v0.12.2: the ZoD burst's best verdict ("-" series gap when no burst ran)
                )
            )
            val f2 = { v: Float -> "%.2f".format(v) }
            DebugLog.log(
                this,
                "SCORE falF=${f2(falFull)} falC=${falCrop?.let { f2(it) } ?: "-"} " +
                    "falZ=${falZoom?.let { f2(it) } ?: "-"} " + // v0.12.2: after falC, per the ZoD spec §5.5
                    "clsF=${clsFull?.let { f2(it) } ?: "-"} clsC=${clsCrop?.let { f2(it) } ?: "-"}"
            )
        }
        // v0.11.11 FIX B step 1 — NODE-BOUNDS PROBE (log only): for
        // risky apps, walk the active window's accessibility tree and
        // log the candidate per-picture rectangles it exposes. The
        // picture-pass question — can Falconsai be fed ONE post's true
        // rect instead of the motion union box? — lives or dies on
        // whether X/Chrome publish usable bounds here. The probe feeds
        // NOTHING: no score, pass, region or decision reads it back.
        if (pkg in RISKY) logNodeProbe(pkg, bmp.width, bmp.height)
        // v0.11.4 SUS trigger: a fresh verdict at/above SUS_BAR on
        // EITHER pass (re)starts this package's 15 s suspicion
        // window — 1 s analysis, ~1 s scout floor, SUS MODE on the
        // overlay — so warm content is sampled densely instead of
        // at the idle rate. Placed before the overlay update so the
        // triggering frame's own readout already carries the
        // marker, and before handleSignal so a conviction from this
        // same frame clears suspicion again in executeBlock (the
        // post-block SUS window takes over from there).
        // v0.11.6: the tripwire classifier is a SECOND trigger
        // source, checked after Falconsai (a Falconsai trip on the
        // same frame wins and marks the window warm): a fresh
        // classifier reading at/above the package's effective CLS
        // trip bar (v0.11.8: clsTripBar — 0.30 in risky apps,
        // 0.50 elsewhere) on either of ITS passes (re)trips the
        // same window via noteSusCls, subject to the refractory. Fresh frames only — this block runs
        // exclusively on freshly analysed frames (static re-feeds
        // replay through handleSignal, never through here), so a
        // replayed score can no more trip SUS than it can convict.
        run {
            val c = falCrop
            when {
                falFull >= SUS_BAR && (c == null || falFull >= c) -> noteSus(pkg, falFull, "full")
                c != null && c >= SUS_BAR -> noteSus(pkg, c, "crop")
            }
            val cf = clsFull
            val cc = clsCrop
            val clsBar = clsTripBar(pkg) // v0.11.8: 0.30 risky / 0.50 other
            when {
                cf != null && cf >= clsBar && (cc == null || cf >= cc) -> noteSusCls(pkg, cf, "cls-full")
                cc != null && cc >= clsBar -> noteSusCls(pkg, cc, "cls-crop")
            }
        }
        // v0.5.2: feed the live debug readout with this frame's numbers.
        // v0.11.7: a CLS line mirrors the FAL line (full debug
        // visibility for the tripwire) — "CLS —" when the classifier
        // produced no reading on this frame (unavailable or run
        // failed), the crop value tagged on only when a crop pass ran.
        if (debugView != null) {
            showOverlay(
                pkg,
                "$pkg\nFAL ${"%.2f".format(falFull)}" +
                    (falCrop?.let { " · crop ${"%.2f".format(it)}" } ?: "") +
                    "\nCLS " + (clsFull?.let {
                        "%.2f".format(it) + (clsCrop?.let { c -> " · crop ${"%.2f".format(c)}" } ?: "")
                    } ?: "—")
            )
        }
        // v0.13.2: NOT while a benchmark runs — the stage's analyses
        // are the phase's samples, not the photo capture-bench, and
        // consuming the one-shot pref mid-run was half of the phase
        // starvation mechanism (the tick gate now keys on benchActive
        // as well, but the two features stay fully separate).
        if (Prefs.get(this).getBoolean("capbench", false) && pkg == packageName && !benchActive) {
            Prefs.get(this).edit().remove("capbench").apply()
            DebugLog.log(
                this,
                "CAPBENCH screenshot-of-known-photo: falFull=${"%.2f".format(falFull)} " +
                        "falCrop=${falCrop?.let { "%.2f".format(it) } ?: "-"} grid=[${grid4x4(thumb)}] " +
                        "(known photo: clothed beach-volleyball player — expect falFull near 0)"
            )
        }
        val lgBefore = launcherGuardCount // v0.11.12: conviction detector for the hair trigger below
        handleSignal(
            pkg,
            secure = false,
            falFull = falFull,
            falCrop = falCrop,
            fresh = true,
            // v0.12.2: this analysis's crop evidence is zoom-sourced
            // when it was served from the aim lock or IS the locking
            // verdict — the accumulator caps its points (design §5.4).
            cropZoomSourced = cropServedFromLock || burstLockSourced
        )
        // v0.11.12 PARANOID HAIR TRIGGER (Alejandro's design):
        // checked AFTER handleSignal, so a strict conviction on
        // THIS frame takes the normal LAUNCHER GUARD path (and
        // restarts the paranoid window) instead of being shadowed
        // by a mere cover — the launcher-guard counter tells the
        // two apart. While the window is open and the launcher is
        // in front, ANY of the four verdicts at/above PARANOID_BAR
        // fires the guard action at once. Fresh analyses only:
        // processFrame runs exclusively on fresh frames (static
        // re-feeds replay through handleSignal, never through
        // here), so a replayed score can no more cover than it can
        // convict. Once per analysis at most — this block runs
        // once per processFrame call.
        if (launcherGuardCount == lgBefore && paranoidLauncherActive(pkg)) {
            val peak = maxOf(falFull, falCrop ?: 0f, clsFull ?: 0f, clsCrop ?: 0f)
            if (peak >= PARANOID_BAR) fireParanoidCover(pkg, falFull, falCrop, clsFull, clsCrop)
        }
    }

    /**
     * Response entry point. v0.6.0: the fade engine is CUT (Alejandro's
     * ruling) — instant response is the only mode. [fresh] marks a signal
     * from a frame analysed right now, as opposed to a static re-feed
     * replaying the last frame's scores; the conviction machinery
     * counts fresh frames only, so a single replayed frame can never
     * convict by repetition. v0.11.0: the signal is just the two Falconsai scores —
     * [falFull] (whole frame) and [falCrop] (motion-region crop, when a
     * region was active) — plus the luminance-based [secure] flag.
     */
    // ---- v0.14.0: MULTI-WINDOW ANALYSIS (design §4–§8) ----
    // Runs when the capture snapshot holds TWO OR MORE judgeable
    // windows. One judgeable window never reaches here (the legacy
    // engine takes it whole, aimed at that window's package), and
    // neither does the benchmark (its surface is our own package —
    // never judgeable, so its snapshots are always empty).

    /** Crop [bmp] to a pixel rect (clamped, min 1 px) — window
     *  bounds live in the screenshot's own coordinate space. */
    private fun cropToRect(bmp: Bitmap, r: Rect): Bitmap? {
        val l = r.left.coerceIn(0, bmp.width - 1)
        val t = r.top.coerceIn(0, bmp.height - 1)
        val rr = r.right.coerceIn(l + 1, bmp.width)
        val b = r.bottom.coerceIn(t + 1, bmp.height)
        return try {
            Bitmap.createBitmap(bmp, l, t, rr - l, b - t)
        } catch (e: Exception) {
            failLogThrottled("wincrop", "other", "window crop ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun winAreaFrac(w: StackWindow, bmp: Bitmap): Double =
        (w.bounds.width().toDouble() * w.bounds.height()) / (bmp.width.toDouble() * bmp.height)

    /**
     * §6: attribute the motion-region crop geometrically against
     * the capture snapshot — the tracker's region centroid → the
     * judged window containing it (topmost on ties); no container
     * → the judged window with maximum IoU against the region
     * box; no overlap at all → null (heat over an unjudged surface
     * only: it can warm the pending, it can name nobody).
     */
    private fun attributeMotion(snap: StackSnapshot, regionBox: MotionRegionTracker.Box, bmpW: Int, bmpH: Int): StackWindow? {
        val regionRect = Rect(
            regionBox.l * bmpW / MotionRegionTracker.COLS,
            regionBox.t * bmpH / MotionRegionTracker.ROWS,
            regionBox.r * bmpW / MotionRegionTracker.COLS,
            regionBox.b * bmpH / MotionRegionTracker.ROWS
        )
        val centroid = try { regionTracker.recentCentroidIn(regionBox) } catch (e: Exception) { null }
        if (centroid != null) {
            val cx = (centroid.first * bmpW / MotionRegionTracker.COLS).toInt()
            val cy = (centroid.second * bmpH / MotionRegionTracker.ROWS).toInt()
            val containing = snap.judged.filter { it.bounds.contains(cx, cy) }.maxByOrNull { it.layer }
            if (containing != null) return containing
        }
        var best: StackWindow? = null
        var bestIou = 0.0
        for (w in snap.judged) {
            val v = rectIou(regionRect, w.bounds)
            if (v > bestIou) {
                bestIou = v
                best = w
            }
        }
        return best
    }

    // ---- multi-window pending (D1-B) ----

    private fun multiPendingCheckExpiry(now: Long) {
        if (multiPendingParkedMs > 0L && now - multiPendingWarmthMs > PENDING_EXPIRY_MS) {
            DebugLog.log(this, "PENDING BLOCK expired stack (10s, no warmth)")
            multiPendingParkedMs = 0L
            multiPendingWarmthMs = 0L
            multiPendingPeak = 0f
        }
    }

    /** Refresh a parked multi pending's warmth (any fresh
     *  attributed verdict >= SUS_BAR); an already-dead pending is
     *  expired here first so a late verdict cannot revive it. */
    private fun multiPendingWarm(now: Long, peak: Float) {
        if (multiPendingParkedMs == 0L) return
        multiPendingCheckExpiry(now)
        if (multiPendingParkedMs == 0L) return
        multiPendingWarmthMs = now
        if (peak > multiPendingPeak) multiPendingPeak = peak
    }

    private fun multiPendingAlive(now: Long): Boolean =
        multiPendingParkedMs > 0L && now - multiPendingWarmthMs <= PENDING_EXPIRY_MS

    // ---- per-window ZoD (§4.1) ----

    /** Release the per-window aim lock (if any) and log once.
     *  [why]: cooled | reloc | timeout | win-gone | block. */
    private fun clearZodWinLock(why: String) {
        val p = zodWinLockPkg ?: return
        val had = zodWinLockFrac != null
        zodWinLockPkg = null
        zodWinLockRect = null
        zodWinLockFrac = null
        zodWinLockExpiryMs = 0L
        zodWinLockCold = 0
        if (!had) return
        DebugLog.log(this, "ZOOM unlock pkg=$p why=$why (window)")
        if (why == "cooled") {
            zodCoolPkg = p
            zodCoolUntilMs = System.currentTimeMillis() + ZOD_COOL_FRUITLESS_MS
        }
    }

    /** The window lock's verdict-driven exits, mirroring
     *  zodAfterScored: a lock-served verdict >= ZOD_WARM refreshes
     *  it; cold verdicts count toward the cooled release; the
     *  time expiry is checked on every call. */
    private fun zodWinAfterScored(pkg: String, fal: Float?) {
        if (zodWinLockPkg != pkg || zodWinLockFrac == null) return
        val now = System.currentTimeMillis()
        if (fal != null) {
            when {
                fal >= ZOD_WARM -> {
                    zodWinLockExpiryMs = now + ZOD_LOCK_EXPIRY_MS
                    zodWinLockCold = 0
                }
                fal < ZOD_FALC_MAX -> {
                    zodWinLockCold++
                    if (zodWinLockCold >= ZOD_LOCK_COLD_RELEASE) {
                        clearZodWinLock("cooled")
                        return
                    }
                }
                else -> zodWinLockCold = 0
            }
        }
        if (now > zodWinLockExpiryMs) clearZodWinLock("timeout")
    }

    /**
     * The ZoD burst composed inside ONE window (§4.1): the parent
     * box is the tracker box ∩ the window's bounds, the MB rung is
     * the latest interval span clipped to that parent, the AC
     * rungs centre on the motion centroid clipped inside the
     * window — a zoom inside the X PiP never reaches across the
     * Settings beneath it. Same gates, ladder, budget and
     * cooldowns as runZodBurst; the lock is stored as fractions
     * WITHIN the window rect plus the rect itself, so it follows
     * the window and dies when the window moves or leaves.
     * Returns (best verdict, locking verdict, Falconsai ms spent).
     */
    private fun runZodBurstWin(
        bmp: Bitmap, w: StackWindow, parentBox: MotionRegionTracker.Box,
        fal: FalconModel, falFull: Float?, falWin: Float,
        clsFull: Float?, clsWin: Float?
    ): Triple<Float?, Float?, Long> {
        val none = Triple<Float?, Float?, Long>(null, null, 0L)
        val pkg = w.pkg!!
        if (falWin >= ZOD_FALC_MAX) return none
        val bar = clsTripBar(pkg)
        val clsHot = (clsWin != null && clsWin >= bar) || (clsFull != null && clsFull >= bar)
        if (!clsHot) return none
        if (clsRefractoryActive(pkg)) return none
        if (!(susActive(pkg) || (falFull ?: 0f) >= SUS_BAR || clsHot)) return none
        val now0 = System.currentTimeMillis()
        if (zodCoolPkg == pkg && now0 < zodCoolUntilMs) return none
        // Parent in pixels: tracker box ∩ window bounds.
        val bw = bmp.width
        val bh = bmp.height
        val parentPx = Rect(
            parentBox.l * bw / MotionRegionTracker.COLS,
            parentBox.t * bh / MotionRegionTracker.ROWS,
            parentBox.r * bw / MotionRegionTracker.COLS,
            parentBox.b * bh / MotionRegionTracker.ROWS
        )
        if (!parentPx.intersect(w.bounds)) return none // no motion inside this window
        val span = try { regionTracker.lastIntervalSpan() } catch (e: Exception) { null }
        val centroid: Pair<Float, Float>? = span?.let { it.centroidCol to it.centroidRow }
            ?: try { regionTracker.recentCentroidIn(parentBox) } catch (e: Exception) { null }
        if (span == null && centroid == null) return none
        class Rung(val name: String, val frac: FloatArray)
        val rungs = ArrayList<Rung>(3)
        val winL = w.bounds.left.toFloat() / bw
        val winT = w.bounds.top.toFloat() / bh
        val winR = w.bounds.right.toFloat() / bw
        val winB = w.bounds.bottom.toFloat() / bh
        val parentFrac = (parentPx.width().toDouble() * parentPx.height()) / (bw.toDouble() * bh)
        if (span != null && span.cells >= ZOD_MIN_CELLS) {
            val mbPx = Rect(
                span.box.l * bw / MotionRegionTracker.COLS,
                span.box.t * bh / MotionRegionTracker.ROWS,
                span.box.r * bw / MotionRegionTracker.COLS,
                span.box.b * bh / MotionRegionTracker.ROWS
            )
            if (mbPx.intersect(parentPx)) {
                val mbScreenFrac = (mbPx.width().toDouble() * mbPx.height()) / (bw.toDouble() * bh)
                if (mbScreenFrac >= ZOD_MB_MIN_SCREEN_FRAC && mbScreenFrac <= ZOD_MB_MAX_SCREEN_FRAC &&
                    mbScreenFrac <= ZOD_MB_MAX_PARENT_FRAC * parentFrac
                ) {
                    rungs.add(
                        Rung(
                            "MB", floatArrayOf(
                                mbPx.left.toFloat() / bw, mbPx.top.toFloat() / bh,
                                mbPx.right.toFloat() / bw, mbPx.bottom.toFloat() / bh
                            )
                        )
                    )
                }
            }
        }
        if (centroid != null) {
            // The AC machinery works in cell space: the clipped
            // parent as a cell box, then the rung clipped back
            // inside the window's fraction rect.
            val parentCells = MotionRegionTracker.Box(
                (parentPx.left * MotionRegionTracker.COLS / bw).coerceIn(0, MotionRegionTracker.COLS - 1),
                (parentPx.top * MotionRegionTracker.ROWS / bh).coerceIn(0, MotionRegionTracker.ROWS - 1),
                (parentPx.right * MotionRegionTracker.COLS / bw).coerceIn(1, MotionRegionTracker.COLS),
                (parentPx.bottom * MotionRegionTracker.ROWS / bh).coerceIn(1, MotionRegionTracker.ROWS)
            )
            for ((name, s) in listOf("AC05" to 0.5, "AC04" to 0.4)) {
                val f = zodAcWindow(bmp, parentCells, centroid.first, centroid.second, s)
                val cf = floatArrayOf(
                    f[0].coerceIn(winL, winR), f[1].coerceIn(winT, winB),
                    f[2].coerceIn(winL, winR), f[3].coerceIn(winT, winB)
                )
                if (cf[2] <= cf[0] || cf[3] <= cf[1]) continue
                val winFracA = (cf[2] - cf[0]).toDouble() * (cf[3] - cf[1]).toDouble()
                if (winFracA <= ZOD_MB_MAX_PARENT_FRAC * parentFrac) rungs.add(Rung(name, cf))
            }
        }
        if (rungs.isEmpty()) return none
        var best: Float? = null
        var lockName: String? = null
        var lockFrac: FloatArray? = null
        var lockVerdict: Float? = null
        var spentMs = 0L
        val rungLog = ArrayList<String>(3)
        for (rung in rungs) {
            val nowB = System.currentTimeMillis()
            while (zodPassTimes.isNotEmpty() && nowB - zodPassTimes.first() > 60_000L) zodPassTimes.removeFirst()
            if (zodPassTimes.size >= ZOD_PASS_BUDGET_PER_MIN) break
            val rb = cropToFrac(bmp, rung.frac)
            if (rb == null) {
                rungLog.add("${rung.name}:ERR")
                continue
            }
            zodPassTimes.addLast(System.currentTimeMillis())
            val v = falScore(fal, rb)
            spentMs += fal.lastMs
            rb.recycle()
            if (v == null) {
                rungLog.add("${rung.name}:ERR")
                continue
            }
            rungLog.add("${rung.name}:${"%.3f".format(v)}")
            if (best == null || v > best) best = v
            if (v >= ZOD_LOCK_BAR) {
                lockName = rung.name
                lockFrac = rung.frac
                lockVerdict = v
                break
            }
            if (v >= ZOD_WARM) break
        }
        if (rungLog.isEmpty()) return none
        val now2 = System.currentTimeMillis()
        var coolMs = 0L
        if (lockVerdict != null && ZOD_MODE_LIVE) {
            // Bitmap fractions → fractions WITHIN the window rect.
            val lf = lockFrac!!
            zodWinLockFrac = floatArrayOf(
                (lf[0] - winL) / (winR - winL), (lf[1] - winT) / (winB - winT),
                (lf[2] - winL) / (winR - winL), (lf[3] - winT) / (winB - winT)
            )
            zodWinLockPkg = pkg
            zodWinLockRect = Rect(w.bounds)
            zodWinLockExpiryMs = now2 + ZOD_LOCK_EXPIRY_MS
            zodWinLockCold = 0
        } else if (lockVerdict == null) {
            coolMs = if (best != null && best >= ZOD_WARM) ZOD_COOL_WARM_MS else ZOD_COOL_FRUITLESS_MS
            zodCoolPkg = pkg
            zodCoolUntilMs = now2 + coolMs
        }
        val f2 = { v: Float? -> v?.let { "%.2f".format(it) } ?: "-" }
        val sb = StringBuilder()
        sb.append(if (ZOD_MODE_LIVE) "ZOOM" else "ZOOM(shadow)")
        sb.append(" pkg=$pkg win=1 trigClsC=${f2(clsWin)} trigClsF=${f2(clsFull)} falW=${f2(falWin)}")
        sb.append(" box=[${parentPx.left},${parentPx.top},${parentPx.right},${parentPx.bottom}] rungs=${rungLog.joinToString(",")}")
        if (ZOD_MODE_LIVE) sb.append(" lock=${lockName ?: "none"}")
        else sb.append(" wouldLock=${lockName ?: "none"}")
        if (lockName == null) sb.append(" cool=${coolMs / 1000}s")
        DebugLog.log(this, sb.toString())
        return Triple(best, if (ZOD_MODE_LIVE) lockVerdict else null, spentMs)
    }

    /** Per-analysis state for a multi-window run. */
    private class MultiRun {
        val smallPool = SmallPool()
        var paranoidCoverFired = false
        var falZoomBest: Float? = null
    }

    /** One window pass-set's outcome. */
    private class WinPassResult(
        val fal: Float?, val cls: Float?, val zoomSourced: Boolean,
        val falMs: Long, val clsMs: Long
    )

    /**
     * One judgeable window's pass-set (§4): Falconsai + classifier
     * on the window's exact bounds — or, while the per-window ZoD
     * lock is live for it, on the locked sub-box (the window lock
     * replaces the window crop exactly as the legacy lock replaces
     * the tracker crop). Logs the WIN PASS line. Null when the
     * crop itself failed (already fail-logged inside cropToRect).
     */
    private fun runWindowPass(bmp: Bitmap, w: StackWindow, fal: FalconModel, cls: SceneClassifier?): WinPassResult? {
        val lockFrac = if (zodWinLockPkg == w.pkg) zodWinLockFrac else null
        val rect: Rect = if (lockFrac != null) {
            val wb = w.bounds
            Rect(
                wb.left + (lockFrac[0] * wb.width()).toInt(),
                wb.top + (lockFrac[1] * wb.height()).toInt(),
                wb.left + (lockFrac[2] * wb.width()).toInt(),
                wb.top + (lockFrac[3] * wb.height()).toInt()
            )
        } else {
            w.bounds
        }
        val crop = cropToRect(bmp, rect) ?: return null
        val f = falScore(fal, crop)
        val fMs = fal.lastMs
        var c: Float? = null
        var cMs = 0L
        if (cls != null && f != null) {
            c = clsScore(cls, crop)
            cMs = cls.lastMs
        }
        crop.recycle()
        if (f != null) {
            DebugLog.log(
                this,
                "WIN PASS pkg=${w.pkg} rect=${bstr(rect)} fal=${"%.2f".format(f)} " +
                    "cls=${c?.let { "%.2f".format(it) } ?: "-"} small=${if (w.small) 1 else 0} lock=${if (lockFrac != null) 1 else 0}"
            )
        }
        return WinPassResult(f, c, lockFrac != null, fMs, cMs)
    }

    /**
     * Route ONE attributed crop-family verdict (window crop,
     * motion crop, or zoom locking verdict) inside a multi-window
     * analysis: GT peaks for the interval's own package, SUS trips
     * (Falconsai at SUS_BAR; the classifier at THIS window's own
     * bar, §3), the attention burst, D1-B localisation of a parked
     * whole-frame pending, the paranoid hair trigger on
     * launcher-attributed verdicts only, and the crop accumulator.
     * Returns true when a block fired (the caller stops serving
     * further passes — the screen it would grade is going away).
     */
    private fun routeMultiCropVerdict(
        run: MultiRun, w: StackWindow, fal: Float, cls: Float?,
        zoomSourced: Boolean, src: String
    ): Boolean {
        val pkg = w.pkg!!
        val now = System.currentTimeMillis()
        if (gtPkg == pkg) {
            if (fal > gtPeakFalC) gtPeakFalC = fal
            if (cls != null && cls > gtPeakClsC) gtPeakClsC = cls
            if (gtFirstHotElapsedMs < 0L && fal >= FAL_STRONG_BAR) {
                gtFirstHotElapsedMs = now - gtStartMs
            }
        }
        if (fal >= SUS_BAR) noteSus(pkg, fal, src)
        if (cls != null && cls >= clsTripBar(pkg)) noteSusCls(pkg, cls, "cls-$src")
        if (fal >= Prefs.suspFal(this)) noteSuspicion(pkg, "scene-model warmth (stack)")
        // D1-B: the heat must localise before it can name anyone.
        if (multiPendingParkedMs > 0L) {
            if (fal >= SUS_BAR) multiPendingWarm(now, fal)
            if (fal >= STACK_LOCALISE_BAR && multiPendingAlive(now)) {
                val peak = multiPendingPeak
                multiPendingParkedMs = 0L
                multiPendingWarmthMs = 0L
                multiPendingPeak = 0f
                val attr = "Scene model — full-screen hit localised to window (peak ${"%.2f".format(peak)})"
                DebugLog.log(this, "INSTANT BLOCK pkg=$pkg — $attr")
                executeBlock(pkg, secure = false, attribution = attr)
                return true
            }
        }
        // PARANOID (§3): the hair trigger applies to verdicts
        // attributed to the LAUNCHER window while the window is
        // open — a PiP floating over the launcher is judged as its
        // own app at its own bars; paranoid does not splash onto
        // it. Once per analysis.
        if (!run.paranoidCoverFired && paranoidActive() && isLauncherPkg(pkg)) {
            if (maxOf(fal, cls ?: 0f) >= PARANOID_BAR) {
                run.paranoidCoverFired = true
                fireParanoidCover(pkg, fal, null, cls, null)
            }
        }
        return feedCropAccumulator(pkg, fal, zoomSourced, w.small, run.smallPool)
    }

    /**
     * The multi-window analysis (§4): P1 full frame (skipped when
     * the judged windows tile >= 90% of the display, D3) → P2 the
     * hottest window's pass-set → P3 the motion-region crop → P4
     * the remaining windows, hottest first, under the 2.4 s
     * per-analysis budget with rotating deferral → P5 ZoD composed
     * per window. Every verdict attributes against [snap], the
     * capture tick's snapshot. The secure/incognito luminance path
     * does not run here: its interior-black test reads the whole
     * frame, which on a shared screen is two apps' pixels — a
     * browser window in a split keeps its per-window passes
     * instead (its FLAG_SECURE half reads black in its own crop
     * and simply scores cold; the mode is not blocked in split —
     * stated plainly, as §10 states its residuals).
     */
    private fun processFrameMulti(bmp: Bitmap, snap: StackSnapshot) {
        val now0 = System.currentTimeMillis()
        if (now0 < cooldownUntilMs) {
            Prefs.bump(this, "cooldown_skip")
            dbg("COOLDOWN frame ignored stack (${cooldownUntilMs - now0}ms left)")
            bmp.recycle(); return
        }
        val primary = snap.primary()!!
        val primaryPkg = primary.pkg!!
        val thumb = thumbnail(bmp)
        val lum = thumb.average()
        val sessKey = snap.trackerKey()
        beginRegionSession(sessKey)
        val regionBox = try {
            regionTracker.onFrame(sessKey, trackerGrid(bmp))
        } catch (e: Exception) {
            if (!regionErrorLogged) {
                regionErrorLogged = true
                failLog("other", "region tracker ${e.javaClass.simpleName}: ${e.message} (region disabled, whole-frame continues)")
            }
            null
        }
        regionVisBox = regionBox
        if (regionBox != null) {
            regionVisPkg = primaryPkg
            regionVisW = bmp.width
            regionVisH = bmp.height
            noteRegionFirst(sessKey)
        }
        // Window-lock relocation against THIS snapshot (leaving
        // the judged set is pruned in onStackSampled).
        run {
            val lp = zodWinLockPkg
            val lr = zodWinLockRect
            if (lp != null && lr != null) {
                val cur = snap.judged.firstOrNull { it.pkg == lp }
                if (cur != null && rectIou(cur.bounds, lr) < MotionRegionTracker.MOVE_IOU) clearZodWinLock("reloc")
            }
        }
        multiPendingCheckExpiry(now0)
        val diff = frameDiff(thumb)
        if (diff <= DIFF_THRESHOLD) {
            staticStreak++
            if (staticStreak % 8 != 0) {
                Prefs.bump(this, "diff_skip")
                dbg("FRAME stack diff=${"%.1f".format(diff)} lum=${"%.0f".format(lum)} SKIP static #$staticStreak")
                bmp.recycle(); return
            }
            dbg("FRAME stack static recheck #$staticStreak lum=${"%.0f".format(lum)}")
        } else {
            staticStreak = 0
        }
        val fal = falModel
        if (fal == null) {
            if (!falDropLogged) {
                falDropLogged = true
                failLog("model", "frame dropped: Falconsai not available (see the FAL state line at service start) stack")
            }
            bmp.recycle(); return
        }
        val cls = clsModel
        val run = MultiRun()
        var elapsedMs = 0L
        var falMsTotal = 0L
        var clsMsTotal = 0L
        // ---- P1: full frame ----
        var falFull: Float? = null
        var clsFull: Float? = null
        val skipFull = snap.tileFrac >= STACK_TILE_SKIP_FRAC
        if (skipFull) {
            DebugLog.log(this, "PASS skip-full tiled=${(snap.tileFrac * 100).toInt()}% (D3)")
        } else {
            falFull = falScore(fal, bmp)
            falMsTotal += fal.lastMs
            elapsedMs += fal.lastMs
            if (falFull != null) {
                DebugLog.log(this, "FAL pkg=$primaryPkg src=full nsfw=${"%.2f".format(falFull)} falMs=${fal.lastMs} (stack)")
                if (cls != null) {
                    clsFull = clsScore(cls, bmp)
                    if (clsFull != null) {
                        clsMsTotal += cls.lastMs
                        elapsedMs += cls.lastMs
                        DebugLog.log(this, "CLS pkg=$primaryPkg src=full unsafe=${"%.2f".format(clsFull)} clsMs=${cls.lastMs} (stack)")
                    }
                }
            }
        }
        if (falFull == null && !skipFull) {
            bmp.recycle(); return // a run failure latched FAL off inside falScore — nothing more to grade with
        }
        // Full-frame routing — no geometry (§6): trips key on the
        // primary (cadence is screen-wide anyway) and the pending
        // is D1-B's: it parks here and can only convict through a
        // localising crop verdict (routeMultiCropVerdict).
        if (falFull != null) {
            if (gtPkg == primaryPkg) {
                if (falFull > gtPeakFalF) gtPeakFalF = falFull
                if (clsFull != null && clsFull > gtPeakClsF) gtPeakClsF = clsFull
            }
            if (falFull >= SUS_BAR) noteSus(primaryPkg, falFull, "full(stack)")
            if (clsFull != null && clsFull >= clsTripBar(primaryPkg)) noteSusCls(primaryPkg, clsFull, "cls-full(stack)")
            if (falFull >= Prefs.suspFal(this)) noteSuspicion(primaryPkg, "scene-model warmth (stack full)")
            if (falFull >= SUS_BAR) multiPendingWarm(System.currentTimeMillis(), falFull)
            if (falFull >= FAL_STRONG_BAR && multiPendingParkedMs == 0L) {
                multiPendingParkedMs = System.currentTimeMillis()
                multiPendingWarmthMs = multiPendingParkedMs
                multiPendingPeak = falFull
                DebugLog.log(this, "PENDING BLOCK stack — Scene model — ${"%.2f".format(falFull)} (full screen) (parked, multi-window: convicts only on localisation)")
            }
        }
        // Per-window results (ZoD candidacy + SCORE): pkg -> (fal, cls).
        val winResults = HashMap<String, Pair<Float?, Float?>>()
        // Fullscreen dedupe (§4): a judged window covering >= 95%
        // of the display IS the full frame — when P1 ran, P1 was
        // its pass and its verdicts are the full verdicts.
        val fsWin = snap.judged.firstOrNull { winAreaFrac(it, bmp) >= STACK_FULLSCREEN_FRAC }
        if (fsWin != null && !skipFull && falFull != null) {
            winResults[fsWin.pkg!!] = falFull to clsFull
        }
        var blocked = false
        // ---- P2 + P4 serve order: the primary (hottest) window
        // first — always served, with P1 and P3 it is the
        // guaranteed core, as full+crop always were; the budget
        // cap governs the marginal later passes + ZoD — then the
        // remaining windows, deferred ones first (rotation), then
        // hottest tier, then topmost. A fullscreen window served
        // by P1 (dedupe) drops out; when P1 was SKIPPED (D3) it
        // stays in the order and gets its own window pass. ----
        val serveOrder = ArrayList<StackWindow>()
        run {
            val fsServedByP1 = fsWin != null && !skipFull
            val rest = snap.judged.filter { it !== primary && !(fsServedByP1 && it === fsWin) }
                .sortedWith(
                    compareBy<StackWindow> { if (it.pkg!! in deferredWinPkgs) 0 else 1 }
                        .thenBy { tierRank(it.tier) }
                        .thenByDescending { it.layer }
                )
            if (!(primary === fsWin && fsServedByP1)) serveOrder.add(primary)
            serveOrder.addAll(rest)
        }
        var serveIdx = 0
        while (!blocked && serveIdx < serveOrder.size) {
            if (falModel == null) break // FAL latched off inside a pass — nothing left to grade with
            val w = serveOrder[serveIdx]
            val isCore = serveIdx == 0 // P2 — the primary always runs
            if (!isCore && elapsedMs >= ANALYSIS_BUDGET_MS) {
                // §5: the cap binds — defer this and every later
                // window; they rotate to the front of the next
                // analysis (P4 ordering reads deferredWinPkgs).
                for (k in serveIdx until serveOrder.size) {
                    val dw = serveOrder[k]
                    if (deferredWinPkgs.add(dw.pkg!!)) {
                        DebugLog.log(this, "PASS defer pkg=${dw.pkg} (analysis budget ${ANALYSIS_BUDGET_MS}ms spent)")
                    }
                }
                break
            }
            serveIdx++
            val res = runWindowPass(bmp, w, fal, cls) ?: continue
            elapsedMs += res.falMs + res.clsMs
            falMsTotal += res.falMs
            clsMsTotal += res.clsMs
            deferredWinPkgs.remove(w.pkg!!)
            if (res.fal == null) continue // FAL died mid-analysis (latched inside falScore)
            winResults[w.pkg!!] = res.fal to res.cls
            if (zodWinLockPkg == w.pkg) zodWinAfterScored(w.pkg!!, res.fal)
            blocked = routeMultiCropVerdict(run, w, res.fal, res.cls, res.zoomSourced, "win-crop")
            if (falModel == null) break // latched off inside a pass — stop serving
        }
        // ---- P3: the motion-region crop (core — always served
        // when a region exists, exactly as Pass B always was) ----
        if (!blocked && regionBox != null && falModel != null) {
            val motionWin = attributeMotion(snap, regionBox, bmp.width, bmp.height)
            val cropBmp = cropToRegion(bmp, regionBox)
            if (cropBmp != null) {
                val fC = falScore(fal, cropBmp)
                val fCMs = fal.lastMs
                falMsTotal += fCMs
                elapsedMs += fCMs
                var cC: Float? = null
                if (cls != null && fC != null) {
                    cC = clsScore(cls, cropBmp)
                    if (cC != null) {
                        clsMsTotal += cls.lastMs
                        elapsedMs += cls.lastMs
                    }
                }
                cropBmp.recycle()
                if (fC != null) {
                    if (motionWin != null) {
                        DebugLog.log(this, "WIN PASS pkg=${motionWin.pkg} src=motion fal=${"%.2f".format(fC)} cls=${cC?.let { "%.2f".format(it) } ?: "-"}")
                        blocked = routeMultiCropVerdict(run, motionWin, fC, cC, zoomSourced = false, src = "motion-crop")
                    } else {
                        DebugLog.log(this, "WINATTR none fal=${"%.2f".format(fC)} (motion over unjudged surface)")
                        if (fC >= SUS_BAR) multiPendingWarm(System.currentTimeMillis(), fC)
                    }
                }
            }
        }
        // ---- P5: ZoD, composed per window ----
        if (!blocked && regionBox != null && falModel != null) {
            val candidates = snap.judged
                .filter { w ->
                    val r = winResults[w.pkg!!]
                    r?.first != null && r.first!! < ZOD_FALC_MAX && zodWinLockPkg != w.pkg
                }
                .sortedWith(compareBy<StackWindow> { tierRank(it.tier) }.thenByDescending { it.layer })
            val zw = candidates.firstOrNull()
            if (zw != null) {
                if (elapsedMs >= ANALYSIS_BUDGET_MS) {
                    if (deferredWinPkgs.add(zw.pkg!!)) {
                        DebugLog.log(this, "PASS defer pkg=${zw.pkg} pass=zod (analysis budget ${ANALYSIS_BUDGET_MS}ms spent)")
                    }
                } else {
                    val r = winResults[zw.pkg!!]!!
                    val (bz, lv, zMs) = runZodBurstWin(bmp, zw, regionBox, fal, falFull, r.first!!, clsFull, r.second)
                    elapsedMs += zMs
                    falMsTotal += zMs
                    if (bz != null && (run.falZoomBest == null || bz > run.falZoomBest!!)) run.falZoomBest = bz
                    if (lv != null) {
                        blocked = routeMultiCropVerdict(run, zw, lv, null, zoomSourced = true, src = "zoom-win")
                    }
                }
            }
        }
        bmp.recycle()
        checks++
        Prefs.putStatus(this, "last_check_ms", System.currentTimeMillis())
        Prefs.putStatus(this, "checks", checks)
        Prefs.putStatus(this, "last_top_class", "falconsai nsfw")
        val primaryRes = winResults[primaryPkg]
        Prefs.putStatus(this, "last_top_score", falFull ?: primaryRes?.first ?: 0f)
        Prefs.putStatus(this, "last_latency_ms", falMsTotal + clsMsTotal)
        dbg("ANALYSED stack n=${snap.judged.size} diff=${"%.1f".format(diff)} lum=${"%.0f".format(lum)} falMs=$falMsTotal grid=[${grid4x4(thumb)}]")
        perfSamples.addLast(longArrayOf(falMsTotal, clsMsTotal, analysisFalPasses.toLong(), analysisClsPasses.toLong()))
        while (perfSamples.size > PERF_RING) perfSamples.removeFirst()
        lastFalFull = falFull
        lastFalCrop = primaryRes?.first
        if (falFull != null) {
            ScoreHistory.record(
                ScoreHistory.Sample(
                    tsMs = System.currentTimeMillis(),
                    pkg = primaryPkg,
                    falFull = falFull,
                    falCrop = primaryRes?.first,
                    clsFull = clsFull,
                    clsCrop = primaryRes?.second,
                    falZoom = run.falZoomBest
                )
            )
        }
        val f2 = { v: Float? -> v?.let { "%.2f".format(it) } ?: "-" }
        DebugLog.log(
            this,
            "SCORE falF=${f2(falFull)} falC=${f2(primaryRes?.first)} falZ=${f2(run.falZoomBest)} " +
                "clsF=${f2(clsFull)} clsC=${f2(primaryRes?.second)} stack=${snap.judged.size}"
        )
        if (debugView != null) {
            showOverlay(
                primaryPkg,
                "$primaryPkg +${snap.judged.size - 1} win\nFAL ${f2(primaryRes?.first)} · full ${f2(falFull)}" +
                    "\nCLS " + (primaryRes?.second?.let { "%.2f".format(it) } ?: "—")
            )
        }
    }

    /**
     * v0.11.11 FIX B step 1 — NODE-BOUNDS PROBE. Walks the active
     * window's accessibility node tree (GuardService IS the
     * AccessibilityService, so rootInActiveWindow is the lightest
     * correct route — no new plumbing) and logs ONE line per risky-app
     * analysis:
     *   NODES pkg=<pkg> n=<candidates> seen=<visited> rects=[x,y,w,h:Class;...]
     * with ` trunc=1` appended when a walk cap cut the traversal, or
     *   NODES pkg=<pkg> n=0 err=<root-null|ExceptionName>
     * when no tree could be read. Candidates are VISIBLE nodes (the
     * root excluded — it is the window itself, not a picture) whose
     * screen bounds cover >= NODES_MIN_AREA_PCT% of the screen,
     * biggest first, capped at NODES_MAX_RECTS; coordinates are
     * screen pixels (getBoundsInScreen, the screenshot's own space);
     * the class is the node's className's short tail. `n` counts ALL
     * candidates found (before the cap); `seen` counts nodes visited,
     * so a capped walk is distinguishable from a sparse tree.
     *
     * LOG ONLY: the result feeds no score, pass, region or decision —
     * it exists so field logs can show whether X/Chrome expose
     * per-picture rectangles worth a future picture pass. The walk is
     * defensive: iterative DFS (no recursion depth risk), depth /
     * visited / wall-clock caps, every obtained child recycled, the
     * whole thing try/caught — a failed walk logs its marker and can
     * never stall or crash the analysis loop.
     */
    private fun logNodeProbe(pkg: String, screenW: Int, screenH: Int) {
        try {
            val root = rootInActiveWindow
            if (root == null) {
                DebugLog.log(this, "NODES pkg=$pkg n=0 err=root-null")
                return
            }
            val minArea = (screenW.toLong() * screenH * NODES_MIN_AREA_PCT / 100).coerceAtLeast(1)
            val deadline = System.currentTimeMillis() + NODES_WALK_BUDGET_MS
            var visited = 0
            var truncated = false
            val found = ArrayList<Pair<Long, String>>(16) // (area, "x,y,w,h:Class")
            val stack = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
            stack.add(root to 0)
            while (stack.isNotEmpty()) {
                if (visited >= NODES_MAX_VISITED || System.currentTimeMillis() > deadline) {
                    truncated = true
                    break
                }
                val (node, depth) = stack.removeAt(stack.size - 1)
                visited++
                if (depth > 0 && node.isVisibleToUser) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    val w = r.width()
                    val h = r.height()
                    if (w > 0 && h > 0) {
                        val area = w.toLong() * h
                        if (area >= minArea) {
                            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
                            found.add(area to "${r.left},${r.top},$w,$h:$cls")
                        }
                    }
                }
                if (depth < NODES_MAX_DEPTH) {
                    val n = node.childCount
                    for (i in n - 1 downTo 0) {
                        val child = try { node.getChild(i) } catch (e: Exception) { null }
                        if (child != null) stack.add(child to depth + 1)
                    }
                }
                if (node !== root) node.recycle()
            }
            // Early bail: recycle whatever the caps left unvisited.
            while (stack.isNotEmpty()) {
                val (leftover, _) = stack.removeAt(stack.size - 1)
                if (leftover !== root) leftover.recycle()
            }
            found.sortByDescending { it.first }
            val rects = found.take(NODES_MAX_RECTS).joinToString(";") { it.second }
            DebugLog.log(
                this,
                "NODES pkg=$pkg n=${found.size} seen=$visited rects=[$rects]" +
                    if (truncated) " trunc=1" else ""
            )
        } catch (e: Exception) {
            DebugLog.log(this, "NODES pkg=$pkg n=0 err=${e.javaClass.simpleName}")
            // v0.11.14: the probe's own data line above keeps its
            // designed format; the failure ALSO enters the FAIL
            // channel so a dead probe is counted, not just noted.
            failLog("other", "nodes probe ${e.javaClass.simpleName}: ${e.message} pkg=$pkg")
        }
    }

    /** v0.14.0 (D4): one analysis's shared budget of positive
     *  points from SMALL windows — all small windows combined may
     *  contribute at most STACK_SMALL_POINT_CAP points per
     *  analysis, so a postage-stamp window needs at least four
     *  separate hot analyses to convict (§8). */
    private class SmallPool(var used: Int = 0)

    /**
     * Feed ONE fresh crop-family verdict into [pkg]'s accumulator —
     * the v0.10.9 points machinery (bars, the v0.11.11 SUS hold,
     * the v0.12.2 zoom point cap), extracted in v0.14.0 so the
     * legacy path and the multi-window path feed IDENTICAL bars
     * into per-package state (accByPkg). [small] verdicts share
     * [smallPool]'s per-analysis positive cap (D4). Returns true
     * when the verdict convicted (the block has fired; the caller
     * stops processing this frame, as the inlined block's return
     * did).
     */
    private fun feedCropAccumulator(
        pkg: String, score: Float, zoomSourced: Boolean,
        small: Boolean, smallPool: SmallPool
    ): Boolean {
        // v0.11.11 FIX A (Alejandro, from GT-labelled field data):
        // while SUS is live for this package — its own trip window
        // OR the post-block global window, i.e. whenever
        // susActive(pkg) — a drain-band verdict (< CROP_ACC_FLOOR)
        // HOLDS the points instead of draining one. Field evidence:
        // falC 1.00 -> 2/4, then single cold frames 0.00/0.02 ->
        // 0/4 in 7 s — at SUS's 1 s cadence one cold frame (often
        // just a badly-placed crop window that tick) erased half
        // a perfect strike. In calm mode the -1 drain applies
        // exactly as before, so stale evidence still dies when
        // content genuinely cools. Gains and bars untouched; the
        // hold is visible in the CROP ACC line as a 'hold-sus'
        // marker (delta shown as +0).
        val heldSus = score < CROP_ACC_FLOOR && susActive(pkg)
        var delta = when {
            score >= CROP_ACC_HOT -> 2
            score >= CROP_ACC_WARM -> 1
            score >= CROP_ACC_FLOOR -> 0
            heldSus -> 0
            else -> -1
        }
        // v0.12.2 ZoD (design §3.4/§5.4): the design's one argued
        // exception to the bars — a POSITIVE delta sourced from a
        // zoom verdict (the locking verdict, or any verdict
        // served from the aim lock) is capped at
        // ZOD_ZOOM_POINT_CAP per analysis, so fragment-class
        // evidence must sustain across >= 4 analyses to convict
        // instead of landing as an instant +2 strike. The bars,
        // the drain and the hold are untouched; the cap is
        // visible in the CROP ACC line as a ' zoom-cap' marker.
        var zoomCapped = false
        if (zoomSourced && delta > ZOD_ZOOM_POINT_CAP) {
            delta = ZOD_ZOOM_POINT_CAP
            zoomCapped = true
        }
        // v0.14.0 (D4, window-stack §8): a POSITIVE delta from a
        // small window draws on the analysis-wide small pool —
        // capped, and visible as a ' small-cap' marker.
        var smallCapped = false
        if (small && delta > 0) {
            val allowed = (STACK_SMALL_POINT_CAP - smallPool.used).coerceAtLeast(0)
            if (delta > allowed) {
                delta = allowed
                smallCapped = true
            }
            smallPool.used += delta
        }
        val acc = accByPkg.getOrPut(pkg) { AccState() }
        if (delta > 0 && score > acc.peak) acc.peak = score
        acc.points = (acc.points + delta).coerceIn(0, CROP_ACC_CAP)
        if (acc.points == 0) {
            // The accumulation has fully drained — the next points
            // start a new one, peak included.
            acc.peak = 0f
        }
        if (delta != 0 || heldSus || acc.points > 0 || score >= CROP_ACC_FLOOR) {
            DebugLog.log(this, "CROP ACC pkg=$pkg fal=${"%.2f".format(score)} pts=${acc.points}/$CROP_ACC_TARGET (${"%+d".format(delta)})" + (if (heldSus) " hold-sus" else "") + (if (zoomCapped) " zoom-cap" else "") + (if (smallCapped) " small-cap" else ""))
        }
        if (acc.points >= CROP_ACC_TARGET) {
            val attr = "Scene model (crop) — ${acc.points} pts accumulated (peak ${"%.2f".format(acc.peak)})"
            resetCropAcc()
            DebugLog.log(this, "INSTANT BLOCK pkg=$pkg — $attr")
            executeBlock(pkg, secure = false, attribution = attr)
            return true
        }
        return false
    }

    private fun handleSignal(pkg: String, secure: Boolean, falFull: Float?, falCrop: Float?, fresh: Boolean, cropZoomSourced: Boolean = false) {
        handleInstantSignal(pkg, secure, falFull, falCrop, fresh, cropZoomSourced)
    }

    private fun handleInstantSignal(pkg: String, secure: Boolean, falFull: Float? = null, falCrop: Float? = null, fresh: Boolean = true, cropZoomSourced: Boolean = false) {
        // v0.3.1: blocking is no longer gated to the RISKY set (Alejandro's
        // order) — any analysed app can be blocked. RISKY now only drives
        // the faster idle cadence. The never-analyse SKIP sets still apply.
        if (streakPkg != pkg) {
            // v0.11.4: suspicion belongs to the previous package's
            // session — a different foreground package clears it.
            // v0.14.0: clearSus empties the per-package map (in
            // legacy flows trips only ever key on the analysed
            // package, so this is the old single-slot clear).
            clearSus("package change")
            // v0.11.9: an open GT interval belongs to its starting
            // package — an analysed foreground change closes it (the
            // tick-level check covers never-analysed apps as well).
            if (gtPkg != null && gtPkg != pkg) closeGt("pkgchange")
            streakPkg = pkg
            instantSecureStreak = 0
            if (pendingBlockPkg != pkg) {
                pendingBlockPkg = null
                pendingHits = 0
                pendingLastWarmthMs = 0L
                pendingPeak = 0f
            }
            // v0.12.2: the ZoD aim lock belongs to its package — an
            // analysed foreground change releases it (design §3.3),
            // beside the accumulator reset it parallels.
            if (zodLockPkg != null && zodLockPkg != pkg) clearZodLock("pkgchange")
            resetCropAcc()
            attentionPkg = null
            attentionUntilMs = 0L
        }
        lastSignalSecure = secure
        if (secure) {
            if (fresh) noteSuspicion(pkg, "secure frame")
            instantSecureStreak++
            val secureNeed = Prefs.secureFrames(this)
            DebugLog.log(this, "SECURE streak $instantSecureStreak/$secureNeed pkg=$pkg")
            if (instantSecureStreak >= secureNeed) {
                instantSecureStreak = 0
                DebugLog.log(this, "INSTANT BLOCK pkg=$pkg (secure confirmed $secureNeed frames)")
                executeBlock(pkg, secure = true, attribution = "Incognito / secure window")
            }
            return
        }
        // v0.11.5 WHOLE-FRAME PENDING, REFORMED (Alejandro approved the
        // exact fix): the pre-v0.11.5 confirmation machinery — signal-
        // grade hits toward a configurable count inside a 3 s window,
        // and above all the CLEAN-FRAME DROP — was built for the old
        // incumbent classifier, which genuinely spiked on innocent
        // frames (the 2026-10-26 spider video raised the hit count to
        // 3). Falconsai does not spike: its benign full-frame ceiling
        // is 0.011 across the whole BENCH-ALL corpus, so a >= 0.90
        // whole-frame verdict is conviction-grade, not a spike. The
        // field proof (2026-10-28 log): four pendings parked at
        // 1.00/0.99/0.99/0.92 while Alejandro scrolled explicit content
        // on X, and EVERY one was dropped because the next frame — a
        // different post — read clean; the block came ~100 s later via
        // the crop accumulator. A clean frame of another post is not
        // an alibi. Now:
        //  - NO frame, however clean, drops or resets a parked pending.
        //  - A FRESH whole-frame verdict >= FAL_STRONG_BAR while the
        //    pending lives adds a strike; the FULL_STRIKES_TO_CONVICT-th
        //    strike convicts (v0.11.5: the count was 2; v0.16.0: 4).
        //  - The pending dies ONLY by expiry — PENDING_EXPIRY_MS after
        //    the last warmth, warmth being any fresh verdict (full or
        //    crop) >= SUS_BAR for this package (SUS MODE is normally
        //    running concurrently, sampling at 1 s) — or by package
        //    change / any block, handled at their own sites.
        // Expiry is evaluated on every signal for the pending package
        // (fresh or static re-feed, so it lands promptly even on a
        // still screen); only fresh frames refresh warmth or convict —
        // a re-feed replays an old verdict and proves nothing new.
        // Paper-replayed against the 2026-10-28 field log before this
        // build: the 10:39:33 and 10:40:30 episodes convict ~1.1 s
        // after parking; the isolated 10:39:04 and 10:40:19 parks
        // expire unconvicted.
        if (pendingBlockPkg == pkg) {
            val nowW = System.currentTimeMillis()
            if (nowW - pendingLastWarmthMs > PENDING_EXPIRY_MS) {
                DebugLog.log(this, "PENDING BLOCK expired pkg=$pkg (10s, no warmth)")
                pendingBlockPkg = null
                pendingHits = 0
                pendingLastWarmthMs = 0L
                pendingPeak = 0f
            } else if (fresh) {
                if ((falFull ?: 0f) >= SUS_BAR || (falCrop ?: 0f) >= SUS_BAR) {
                    pendingLastWarmthMs = nowW
                }
                if (falFull != null && falFull >= FAL_STRONG_BAR) {
                    if (falFull > pendingPeak) pendingPeak = falFull
                    // v0.16.0: strike counting — each further fresh
                    // conviction-grade full verdict while the pending
                    // lives adds a strike; only the
                    // FULL_STRIKES_TO_CONVICT-th convicts. Intermediate
                    // strikes log their count so the climb is visible
                    // in the export (and on the score dots).
                    pendingHits++
                    if (pendingHits >= FULL_STRIKES_TO_CONVICT) {
                        val attr = "Scene model — $FULL_STRIKES_TO_CONVICT full-screen hits (peak ${"%.2f".format(pendingPeak)})"
                        pendingBlockPkg = null
                        pendingHits = 0
                        pendingLastWarmthMs = 0L
                        pendingPeak = 0f
                        DebugLog.log(this, "INSTANT BLOCK pkg=$pkg — $attr")
                        executeBlock(pkg, secure = false, attribution = attr)
                        return
                    }
                    DebugLog.log(this, "PENDING BLOCK pkg=$pkg — Scene model — ${"%.2f".format(falFull)} (full screen) (hit $pendingHits/$FULL_STRIKES_TO_CONVICT)")
                }
            }
        }
        instantSecureStreak = 0
        // v0.7.1 (Alejandro): ANY suspicious frame starts the max cadence —
        // not just conviction-track signals. v0.11.0: the warmth bar reads
        // either Falconsai pass (Prefs.suspFal); the conviction logic
        // below is unchanged by it.
        if (fresh && ((falFull ?: 0f) >= Prefs.suspFal(this) || (falCrop ?: 0f) >= Prefs.suspFal(this))) {
            noteSuspicion(pkg, "scene-model warmth")
        }
        // v0.10.9 CROP ACCUMULATOR (Alejandro's one-standard-per-source
        // design): a CROP verdict judges the content's own whole screen —
        // the motion region IS the playing content, undiluted by feed
        // chrome — so crop evidence accumulates like whole-screen proof:
        // each fresh crop verdict earns points toward conviction
        // (>=0.90: +2; >=0.75: +1; 0.60–0.75: hold; <0.60: −1, floor 0,
        // cap 8), and CROP_ACC_TARGET points convicts. v0.11.11: the
        // <0.60 drain becomes a HOLD while SUS is live for the package
        // (see heldSus below) — the bars themselves are untouched. Crop verdicts
        // NEVER feed the whole-frame machinery below (persistence
        // queue) — one machinery per source, no double counting. Points
        // persist per package while no region is active; they reset on
        // package change and on any block, and drain only via clean
        // crop verdicts.
        // v0.11.0: the v0.10.9 backup WITNESS is gone — it was a
        // detector/corroboration artifact, and Falconsai's benign record
        // (worst 0.011 across ~150 benign whole pictures) means no
        // benign crop ever reaches even the hold band, let alone earns
        // points. The old crop STRONG park (a single >= 0.90 crop
        // parking a pending block) is subsumed: a hot crop now earns
        // +2 here, so conviction takes four hot frames (8 points,
        // v0.16.0) and one frame never can.
        if (fresh && falCrop != null) {
            // v0.14.0: the points machinery lives in
            // feedCropAccumulator (extracted so the multi-window
            // path feeds the identical bars into per-package
            // state); this call is its original site with its
            // original arguments — behaviour identical, and a
            // conviction returns from handleInstantSignal here
            // exactly as the inlined block did.
            if (feedCropAccumulator(pkg, falCrop, cropZoomSourced, small = false, smallPool = SmallPool())) return
        }
        // v0.11.5 WHOLE-FRAME PARK — a full-screen score is a whole
        // picture's verdict, not a fragment, so it needs no partner and
        // no witness (the v0.10.9 disciplines existed because tile
        // fragments and a second fallible model were in play; both are
        // gone). A fresh verdict >= FAL_STRONG_BAR parks the pending
        // block as strike 1; the reformed pending machinery above
        // completes it on the FULL_STRIKES_TO_CONVICT-th such verdict,
        // or lets it expire after 10 s without warmth. (A
        // >= FAL_STRONG_BAR frame that arrives while a pending is
        // already parked for this package never reaches here — the
        // machinery above counted it as a strike.)
        // v0.11.5: the v0.7.0 PERSISTENCE route (N strong frames in a
        // window convicting outright) is REMOVED, not merely bypassed:
        // the reformed pending subsumes it — strikes inside a 10 s
        // warmth window are strictly more responsive than the old
        // fixed-window count, and one counter fewer can starve or
        // double-count.
        if (fresh && falFull != null && falFull >= FAL_STRONG_BAR) {
            if (pendingBlockPkg != pkg) {
                pendingBlockPkg = pkg
                pendingHits = 1
                pendingLastWarmthMs = System.currentTimeMillis()
                pendingPeak = falFull
                DebugLog.log(this, "PENDING BLOCK pkg=$pkg — Scene model — ${"%.2f".format(falFull)} (full screen) (hit 1/$FULL_STRIKES_TO_CONVICT)")
            }
            return
        }
        // v0.12.2 ZoD: the aim lock's verdict-driven exits are
        // evaluated here, after this analysis has been scored
        // (design §5.4) — cooled and timeout; relocation, package
        // change and blocks clear the lock at their own sites.
        // (The conviction returns above pass through executeBlock,
        // which clears the lock with why=block before this line.)
        zodAfterScored(pkg, fresh, falCrop, cropZoomSourced)
    }

    /** Fire a confirmed block. v0.6.0 QUARANTINE paradigm (Alejandro's
     *  design): with Enforce on, the app is COVERED for an escalating
     *  wall-clock cooldown (the fixed ladder: 10 s -> 20 s -> 40 s
     *  -> 80 s -> 160 s -> 320 s -> 600 s cap, v0.18.0) — no Home ejection, no
     *  grading under the cover; the cover is managed by
     *  [syncQuarantineCover] from tick(). With Enforce off the block stays
     *  a warn-only message. [attribution] names the firing model (v0.5.1)
     *  and is shown on the cover / message and in the event log. */
    /** v0.15.1: cooldown for a level, computed ONLY from the
     *  compile-time ladder — Prefs.QUAR_BASE_SEC doubling per level,
     *  capped at Prefs.QUAR_MAX_SEC (10/20/40/80/160/320/600). No stored value
     *  is read: quarantine duration is app behaviour, not a user
     *  setting. (Through v0.15.0 this read the quar_base_s /
     *  quar_max_s prefs — the v0.8.0 settings detour.) */
    private fun quarDurationSec(level: Int): Long {
        val maxSec = Prefs.QUAR_MAX_SEC.toLong()
        var d = Prefs.QUAR_BASE_SEC.toLong()
        repeat((level - 1).coerceAtLeast(0)) { d = minOf(maxSec, d * 2) }
        return d
    }

    private fun executeBlock(pkg: String, secure: Boolean, attribution: String? = null) {
        // v0.14.5 SECOND WALL: an exempt package (Settings,
        // SystemUI, ScreenGuard itself, a permission controller)
        // can never be blocked or quarantined. The first wall is
        // structural — processFrameSanity, the only path an
        // exempt package's frames can take, never calls this
        // function — so reaching here with an exempt package
        // means a future code path regressed; refuse loudly
        // rather than brick the phone's Settings.
        if (isExemptWindowPkg(pkg)) {
            failLog("block-refused", "FAIL block-refused pkg=$pkg — exempt packages are sanity-checked, never blocked (v0.14.5)")
            return
        }
        // v0.17.0 LIVE SESSION HOLD safety net: a conviction that
        // still computes for the held package — a transition race
        // (a capture issued just before the hold armed, a verdict
        // landing just after) — is refused, loudly. The refusal
        // applies while the package's call surface is the judged
        // content, or while the package is not foreground at all
        // (its floating call window's legs are filtered upstream;
        // a conviction naming it anyway is a race). A NON-call
        // surface of the held package in front — chats opened
        // mid-call — does NOT refuse: the design's hole analysis
        // keeps that surface convictable.
        val heldPkg = callHoldPkg
        if (heldPkg != null && pkg == heldPkg) {
            val fgNow = lastWinPkg ?: foregroundPkg
            if (fgNow != pkg || callSurfaceLegs(pkg) != null) {
                failLog("block-refused", "FAIL block-refused pkg=$pkg src=call-hold — live session hold active (v0.17.0)")
                return
            }
        }
        // v0.11.9: a block (quarantine or the launcher-guard
        // conversion below) during a GT interval closes it here —
        // the GT END line lands right after the caller's INSTANT
        // BLOCK line. Instrument only; no state below reads it.
        closeGt("block")
        // v0.11.4: any block ends TRIP suspicion — the global
        // post-block SUS window (180 s, all apps; armed below, past
        // the launcher-guard return) takes over the dense sampling.
        clearSus("block")
        // v0.12.2: any block also releases the ZoD aim lock
        // (design §3.3) — whatever evidence path fired, the locked
        // crop's work is done.
        clearZodLock("block")
        clearZodWinLock("block") // v0.14.0: the per-window lock likewise
        // v0.10.10 LAUNCHER LIGHT RESPONSE (Alejandro): a conviction
        // with the LAUNCHER in front means the evidence came from app
        // previews in Recents (or the home screen itself) — the guard
        // cannot know which app's preview convicted — and the launcher
        // must NEVER receive a cooldown, an escalation level, or a
        // quarantine: locking the launcher would brick the phone in
        // practice. Convert, whatever evidence path fired: collapse
        // Recents with the Home action, show the generic cover for
        // exactly LAUNCHER_COVER_MS, and change NO per-app state —
        // no cooldown for any package, no escalation counter moves.
        if (isLauncherPkg(pkg)) {
            pendingBlockPkg = null
            pendingHits = 0
            pendingLastWarmthMs = 0L
            pendingPeak = 0f
            resetCropAcc()
            DebugLog.log(this, "LAUNCHER GUARD pkg=$pkg — content in previews → home + 3s cover (no cooldown)")
            Prefs.appendLog(this, ts() + " LAUNCHER GUARD $pkg — explicit content detected in app previews; sent Home, 3s cover, no cooldown")
            launcherGuardCount++ // v0.11.12: processFrame reads this to tell a same-frame conviction from a hair-trigger frame
            // v0.11.12 PARANOID MODE (Alejandro's design): a
            // conviction on this surface — explicit content proven
            // where no quarantine can ever apply — opens (or
            // restarts) the paranoid window: for 300 s the
            // launcher samples at SUS cadences and ANY verdict
            // >= 0.30 fires Home + the 3 s cover at once (see
            // armParanoid / fireParanoidCover). No per-app state
            // moves here beyond that window: still no cooldown,
            // no escalation level, for any package.
            armParanoid()
            mainHandler.post {
                performGlobalAction(GLOBAL_ACTION_HOME)
                showLauncherCover()
            }
            return
        }
        val enforce = Prefs.enforceMode(this)
        pendingBlockPkg = null
        pendingHits = 0
        pendingLastWarmthMs = 0L
        pendingPeak = 0f
        resetCropAcc() // v0.10.9: any block, any source, empties the crop accumulator
        blocks++
        Prefs.putStatus(this, "blocks", blocks)
        if (secure) Prefs.bump(this, "secure_blocks")
        cooldownUntilMs = System.currentTimeMillis() + BLOCK_BLIND_MS
        // v0.11.10: arm (or re-arm) the global post-block SUS window
        // (Alejandro's order) — for POST_BLOCK_SUS_MS every app is
        // SUS-active while this one sits in quarantine. This site is
        // AFTER the launcher-guard early-return above, so a LAUNCHER
        // GUARD never arms or re-arms it; a further real block
        // restarts the 180 s. Arming touches no trip-SUS or
        // refractory state: the window is not a trip.
        postBlockSusUntilMs = System.currentTimeMillis() + POST_BLOCK_SUS_MS
        DebugLog.log(this, "SUS on pkg=$pkg src=post-block (180s, all apps)")
        // v0.5.0: a block during a pending unlock cancels the request — the
        // moment of weakness announces itself and the door closes.
        if (Prefs.lockState(this) == Prefs.LOCK_TIMER) {
            Prefs.cancelUnlock(this)
            DebugLog.log(this, "UNLOCK REQUEST CANCELLED — block during the unlock wait")
        }
        val attr = attribution ?: if (secure) "Incognito / secure window" else "Scene model"
        if (debugView != null) {
            overlayShown = null // v0.11.4: the block text bypasses the stored body — force the next refresh to re-render it
            setDebugText("⛔ BLOCKED $pkg\n$attr")
        }
        if (enforce) {
            val now = System.currentTimeMillis()
            val last = Prefs.quarLastBlock(this, pkg)
            val prevLevel = Prefs.quarLevel(this, pkg)
            val level = if (now - last > Prefs.quarResetMs(this)) 1 else minOf(prevLevel + 1, 12)
            val durSec = quarDurationSec(level)
            Prefs.setQuarantine(this, pkg, now + durSec * 1000L, level, now, attr)
            Prefs.appendLog(this, ts() + " BLOCKED $pkg — quarantine ${durSec}s (block #$level) — $attr")
            DebugLog.log(this, "QUARANTINE pkg=$pkg level=$level dur=${durSec}s — $attr")
            // v0.6.1 FIELD FIX (Alejandro): the block itself EJECTS to Home,
            // exactly as before v0.6.0 — the user lands on the launcher and
            // can kill the app from recents. v0.6.0 instead raised the cover
            // in place, and its full-screen touchable window swallowed even
            // the Home gesture, trapping the user on the block screen.
            // The cover now exists ONLY for re-entry during the cooldown.
            mainHandler.post {
                if (secure) {
                    showOverlayMessage(
                        "Private / incognito window",
                        "This screen is capture-proof (incognito), so it can't be safety-checked. Cooldown ${durSec}s (block #$level).",
                        enforce
                    )
                } else {
                    showOverlayMessage("Adult content detected", "$attr — cooldown ${durSec}s (block #$level)", enforce)
                }
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } else {
            mainHandler.post {
                if (secure) {
                    showOverlayMessage(
                        "Private / incognito window",
                        "This screen is capture-proof (incognito), so it can't be safety-checked.",
                        enforce
                    )
                } else {
                    showOverlayMessage("Adult content detected", attr, enforce)
                }
                Prefs.appendLog(this, ts() + " WARN-ONLY: $pkg not closed — $attr")
                DebugLog.log(this, "WARN-ONLY: $pkg not closed")
            }
        }
    }

    // ---- v0.11.2: region scout loop ----
    // Decoupled from the analysis cadence (Alejandro's v0.11.1 field
    // report: the crop took ~5 s to do anything). The old coupling
    // was structural: the tracker only sampled on analysis ticks
    // (3 s risky at Balanced), so it needed a baseline capture plus
    // >= 2 change intervals AT ANALYSIS CADENCE before the first
    // candidate could exist — ~5-6 s to first crop. The scout
    // captures on its own fast timer (500 ms risky / 1 s other),
    // reduces each capture to the tracker's 32x18 luminance grid and
    // DISCARDS the bitmap: no model runs on a scout capture, nothing
    // is analysed or stored. A region typically goes ACTIVE ~1 scout
    // period after a watched app appears, and the next analysis tick
    // simply consumes the freshest box — analysis never waits for
    // tracker warm-up again. (v0.11.3: what the tracker derives from
    // these feeds is the accumulated recent-motion region — see
    // MotionRegionTracker; the scout loop itself is unchanged.)
    //
    // Eligibility mirrors the analysis path exactly: the scout runs
    // only when the service would capture anyway (same foreground
    // resolution, same skip sets, Settings exempt absolutely, the
    // launcher captured as the analysis path captures it for the
    // launcher guard), never under a quarantine cover, and never
    // during the post-block blind window. Captures are serialised
    // against the analysis path (busy / scoutBusy) so the two loops
    // never fire takeScreenshot into each other.
    //
    // v0.11.13 SERIALISATION (the capture-floor fix): busy flags
    // alone never stopped the two loops from firing into the
    // platform's 1000 ms screenshot interval — each loop's capture
    // landed inside the other's floor shadow and drew code 3
    // (INTERVAL_TIME_SHORT), 72% of all attempts in a measured log
    // slice. The scout now ticks at CAPTURE_FLOOR_MS in every mode
    // and passes two gates before capturing (see scoutTick): the
    // shared floor since the last issued capture, and analysis
    // priority — an analysis due within the floor window owns the
    // slot, so a scout capture can never push an analysis into a
    // refusal again. Where an analysis runs, its own bitmap feeds
    // the tracker in processFrame, so in SUS (analysis every
    // ~1 s) the scout simply stands down and the tracker is fed
    // by the analyses themselves.

    private val scoutRunnable = Runnable {
        try {
            scoutTick()
        } catch (e: Exception) {
            // Same never-die-silently rule as the analysis loop.
            try {
                failLog("other", "scout tick crash ${e.javaClass.simpleName}: ${e.message} — scout re-armed")
            } catch (_: Exception) {
                // Logger self-guard — see the tick crash handler.
            }
        }
        scheduleScout() // dedupes via removeCallbacks, like scheduleNext
    }

    private fun scheduleScout() {
        mainHandler.removeCallbacks(scoutRunnable)
        if (!running) return
        mainHandler.postDelayed(scoutRunnable, scoutPeriodMs())
    }

    /** Effective scout period: v0.11.13 — CAPTURE_FLOOR_MS in EVERY
     *  mode (risky, SUS and paranoid included; the sub-second scout
     *  periods are gone, see the section header), stretched only by
     *  the non-rate-limit failure backoff, capped. */
    private fun scoutPeriodMs(): Long {
        return (CAPTURE_FLOOR_MS * scoutBackoffMult).coerceAtMost(SCOUT_MAX_PERIOD_MS)
    }

    /**
     * The package the scout may capture right now, resolved and
     * filtered exactly as [tick] resolves and filters its analysis
     * captures — or null when the scout must stay silent this tick.
     */
    private fun scoutTargetPkg(): String? {
        if (!running) return null
        // v0.14.0: while the stack model is live (fresh sample, not
        // the benchmark — scoutTick already stood down for it), the
        // scout's target is the stack's tracker session: the single
        // judged package, or the shared-screen set signature. The
        // analyses feed the tracker under the same key (processFrame
        // dispatch / trackerKey), so scout and analysis never flip
        // the session between them. No judgeable window → nothing
        // to scout; a quarantined window visible → its cover is
        // what a capture would grid, so stay silent; SYSTEM-only
        // screens keep the v0.13.0 stand-down unless an escalation
        // (a visible package's SUS, post-block, paranoid-with-
        // launcher) wakes the scout, exactly as before.
        val sNow = currentStack
        if (sNow != null && System.currentTimeMillis() - sNow.tsMs < 3_000L) {
            if (sNow.judged.isEmpty()) return null
            val nowS = System.currentTimeMillis()
            if (sNow.judged.any { Prefs.quarUntil(this, it.pkg!!) > nowS }) return null
            // v0.17.0: the scout stands down when every judged
            // window belongs to the held package — there is
            // nothing lawful to scout (the foreground case is the
            // call surface itself; the floating case is the held
            // call window, whose legs the analyses skip too).
            if (callHoldPkg != null && sNow.judged.all { it.pkg == callHoldPkg }) return null
            if (nowS < cooldownUntilMs) return null
            val escalated = sNow.judged.any { susActive(it.pkg!!) } || postBlockSusActive() ||
                (paranoidActive() && sNow.judged.any { isLauncherPkg(it.pkg!!) })
            if (!escalated && sNow.judged.all { it.tier == Prefs.TIER_SYSTEM }) return null
            return sNow.trackerKey()
        }
        val winPkg = activeWindowPkg()
        val pkg = winPkg ?: foregroundPkg
        // While a quarantine cover is up, polls may report OUR package;
        // the covered app is still the one in front (as in tick()).
        val effPkg = if (pkg == packageName && quarCoverPkg != null) quarCoverPkg!! else pkg
        if (effPkg.isBlank()) return null
        // A quarantined app is never captured (tick returns first).
        if (Prefs.quarUntil(this, effPkg) > System.currentTimeMillis()) return null
        // Post-block blind window: processFrame discards such frames;
        // scouting under our own block overlay would only grid it.
        if (System.currentTimeMillis() < cooldownUntilMs) return null
        // Settings exempt absolutely — even during the post-block
        // SUS window, which suspends the other skip sets (as in tick()).
        if (isSettingsPkg(winPkg) || (winPkg == null && isSettingsPkg(effPkg))) return null
        val watchActive = postBlockSusActive()
        val capbench = Prefs.get(this).getBoolean("capbench", false)
        if (effPkg in SKIP_EXACT && !watchActive && !(capbench && effPkg == packageName)) return null
        // v0.17.0: the same stand-down on the legacy label path —
        // while the held package's call surface is in front, the
        // scout does not grid the call.
        if (callSurfaceHeldNow(effPkg)) return null
        return effPkg
    }

    private fun scoutTick() {
        if (!running) return
        // v0.13.0: the scout stands down for the whole benchmark run
        // (capability design §3: the measurement owns the channel).
        if (benchActive) return
        // A wedged scout callback gets the same watchdog budget as a
        // wedged analysis capture (tick() force-clears scoutBusy too).
        if (scoutBusy && System.currentTimeMillis() - scoutBusySinceMs > BUSY_WATCHDOG_MS) {
            failLog("watchdog", "watchdog scout busy stuck >${BUSY_WATCHDOG_MS}ms, force-reset (callback never fired?)")
            watchdogTripsAll++ // v0.13.0: benchmark scoring counts these
            scoutBusy = false
        }
        // The analysis path owns the screenshot channel while it (or
        // its inference) is in flight; the scout skips its turn
        // rather than queueing a second takeScreenshot.
        if (busy || scoutBusy) return
        // v0.11.13: the two serialisation gates (see the section
        // header). FLOOR: a capture issued inside CAPTURE_FLOOR_MS
        // of the previous one is a guaranteed code-3 refusal —
        // skip this tick; the next tick (~1 floor later) is legal.
        // ANALYSIS PRIORITY: an analysis due within the floor
        // window owns the upcoming slot — if the scout captured
        // now, the analysis would arrive inside ITS floor shadow
        // and be refused (the exact starvation this release fixes).
        val nowS = System.currentTimeMillis()
        if (nowS - lastCaptureIssuedMs < CAPTURE_FLOOR_MS) return
        if (nextCaptureDueMs <= nowS + CAPTURE_FLOOR_MS) return
        val pkg = scoutTargetPkg() ?: return
        // v0.13.0 (tiers design §4): the scout STANDS DOWN in SYSTEM
        // foregrounds — a stationary utility screen has nothing for
        // the tracker to learn, and the sanity analyses still feed
        // it. Any escalation (a SUS window for this package, the
        // post-block window, paranoid) wakes it instantly: those
        // states are escalation, whatever the tier.
        if (tierOf(pkg) == Prefs.TIER_SYSTEM && !susActive(pkg) && !postBlockSusActive() && !paranoidActive()) return
        beginRegionSession(pkg)
        doScoutCapture(pkg)
    }

    private fun doScoutCapture(pkg: String) {
        scoutBusy = true
        scoutBusySinceMs = System.currentTimeMillis()
        // v0.11.13: same issue stamp + health counters as the
        // analysis path — one channel, one set of books.
        lastCaptureIssuedMs = scoutBusySinceMs
        capAttAll++
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    capOkAll++
                    govNoteCapture(true, 0) // v0.13.0: governor window (both consumers feed it)
                    val hw = result.hardwareBuffer
                    val bmp = hardwareBufferToBitmap(hw)
                    try { hw.close() } catch (e: Exception) {
                        failLog("capture", "scout buffer close ${e.javaClass.simpleName}: ${e.message} pkg=$pkg")
                    }
                    if (bmp == null) {
                        // v0.11.14: was a fully silent return — the
                        // FAIL line (with identity) is emitted inside
                        // hardwareBufferToBitmap; this is its context.
                        DebugLog.log(this@GuardService, "SCOUT bitmap unusable pkg=$pkg (FAIL logged above)")
                        scoutBusy = false
                        return
                    }
                    try {
                        scoutExec.execute {
                            try {
                                val box = try {
                                    regionTracker.onFrame(pkg, trackerGrid(bmp), singleInterval = true)
                                } catch (e: Exception) {
                                    if (!regionErrorLogged) {
                                        regionErrorLogged = true
                                        failLog("other", "region tracker ${e.javaClass.simpleName}: ${e.message} (region disabled, whole-frame continues)")
                                    }
                                    null
                                }
                                if (box != null) {
                                    // The purple outline follows the
                                    // freshest box, scout-fed included.
                                    regionVisBox = box
                                    regionVisPkg = pkg
                                    regionVisW = bmp.width
                                    regionVisH = bmp.height
                                    noteRegionFirst(pkg)
                                }
                                scoutNoteSuccess()
                            } catch (e: Exception) {
                                failLog("other", "scout grid ${e.javaClass.simpleName}: ${e.message} pkg=$pkg")
                            } finally {
                                bmp.recycle()
                                scoutBusy = false
                            }
                        }
                    } catch (e: Exception) {
                        // v0.11.14: was a bare swallow (executor
                        // rejected the grid work — frame dropped
                        // without a trace). Marked now.
                        failLog("other", "scout dispatch ${e.javaClass.simpleName}: ${e.message} pkg=$pkg")
                        bmp.recycle()
                        scoutBusy = false
                    }
                }

                override fun onFailure(errorCode: Int) {
                    scoutBusy = false
                    scoutNoteFailure(errorCode)
                }
            })
        } catch (e: Exception) {
            scoutBusy = false
            failLog("capture", "scout-capture exception ${e.javaClass.simpleName}: ${e.message} pkg=$pkg")
            govNoteCapture(false, -1) // v0.13.0: governor window
        }
    }

    private fun scoutNoteSuccess() {
        if (scoutBackoffMult > 1 && ++scoutOkStreak >= SCOUT_BACKOFF_RESET_SUCCESSES) {
            scoutBackoffMult = 1
            scoutOkStreak = 0
            DebugLog.log(this, "SCOUT backoff cleared — period back to base")
        }
    }

    private fun scoutNoteFailure(errorCode: Int) {
        capFailByCode = capFailByCode + (errorCode to ((capFailByCode[errorCode] ?: 0) + 1))
        govNoteCapture(false, errorCode) // v0.13.0: governor window (both consumers feed it)
        scoutOkStreak = 0
        if (errorCode == 3) {
            // v0.11.13: INTERVAL_TIME_SHORT — a scheduling signal,
            // not an error (see doCapture's code-3 branch). NO
            // backoff: the scout's next tick is already a full
            // floor away, which is the whole remedy. The
            // pre-v0.11.13 branch keyed on -5 — a code this API
            // never sends — so the scout's rate-limit backoff never
            // engaged in the field either; the refusals it was
            // meant to answer just fell through to a throttled
            // generic log while the starvation continued.
            // v0.11.14: the refusal is still MARKED (every failure
            // is) — the FAIL line replaces the old throttled one.
            failLog("capture", "scout-capture code=$errorCode (${errorName(errorCode)})")
            return
        }
        // v0.11.14: every scout failure enters the FAIL channel —
        // the pre-v0.11.14 code logged these only once per 30 s,
        // which is how a scout-side failure storm could hide in
        // plain sight between the throttled lines.
        failLog("capture", "scout-capture code=$errorCode (${errorName(errorCode)})")
        // Any other failure stretches only the scout's own period
        // (doubling, capped by scoutPeriodMs at SCOUT_MAX_PERIOD_MS).
        val newMult = minOf(4, scoutBackoffMult * 2)
        if (newMult != scoutBackoffMult) {
            scoutBackoffMult = newMult
            DebugLog.log(this, "SCOUT backoff x$newMult (capture failure code=$errorCode ${errorName(errorCode)}) — scout period now ${scoutPeriodMs()}ms")
        }
    }

    /** Begin (or continue) the foreground session used by the REGION
     *  first timing: a session starts at the first capture — scout or
     *  analysis — of a package that is not the session's own. */
    private fun beginRegionSession(pkg: String) {
        if (regionSessionPkg != pkg) {
            regionSessionPkg = pkg
            regionSessionStartMs = System.currentTimeMillis()
            regionSessionLogged = false
        }
    }

    /** Log `REGION first pkg=… in NNNms` once per foreground session:
     *  how long the session's first active region took to appear. */
    private fun noteRegionFirst(pkg: String) {
        if (regionSessionPkg != pkg || regionSessionLogged) return
        regionSessionLogged = true
        DebugLog.log(this, "REGION first pkg=$pkg in ${System.currentTimeMillis() - regionSessionStartMs}ms")
    }

    // ---- v0.6.0: quarantine cover ----

    private var quarView: TextView? = null
    private var quarShownPkg: String? = null
    private var quarShownSecond = -1L
    /** The package the cover is currently shown for (null = cover down).
     *  Tracked explicitly because the cover perturbs the foreground
     *  reading it is keyed to — see syncQuarantineCover. */
    private var quarCoverPkg: String? = null

    private fun expireQuarantine(pkg: String) {
        Prefs.clearQuarUntil(this, pkg)
        // The static-image trap (Alejandro's point): a frozen explicit
        // screen has no frame diff, so without this reset it would sit
        // ungraded indefinitely after the cover lifts.
        lastThumb = null
        staticStreak = 0
        DebugLog.log(this, "QUARANTINE expired pkg=$pkg — diff baseline reset, next frame graded fresh")
    }

    /**
     * Keep the cooldown cover in step with the foreground app. v0.6.1
     * state machine, after the v0.6.0 field failure: the cover appeared
     * and disappeared for seconds because showing it made the window poll
     * report OUR OWN package, which the old logic read as "user left" and
     * hid it — then the next poll saw the app again and re-showed it.
     * Now: a poll reporting our own package while a cover is up means the
     * cover itself, never a departure; the cover lifts only on a
     * confident different foreground (another app, the launcher) or at
     * expiry. Called every tick.
     */
    private fun syncQuarantineCover() {
        val now = System.currentTimeMillis()
        val rawFg = lastWinPkg ?: foregroundPkg
        val fg = if (rawFg == packageName && quarCoverPkg != null) quarCoverPkg!! else rawFg
        if (fg.isNotBlank() && fg != packageName) {
            val until = Prefs.quarUntil(this, fg)
            if (until in 1..now) expireQuarantine(fg)
        }
        // v0.14.0 (§7): the cover keys on ANY VISIBLE WINDOW of the
        // quarantined package, not just the foreground label — a
        // quarantined app floating as a PiP over Settings holds its
        // (full-screen) cover for the whole cooldown, a PiP
        // reappearing during cooldown re-covers on the next tick,
        // and the cover lifts when the package is neither
        // foreground nor visible. Expiry for stack-visible packages
        // runs here too (the fg-only check above never sees a
        // PiP-only package's clock run out).
        val stackNow = currentStack
        if (stackNow != null) {
            for (w in stackNow.judged) {
                val u = Prefs.quarUntil(this, w.pkg!!)
                if (u in 1..now) expireQuarantine(w.pkg!!)
            }
        }
        val stackQuarPkg = stackNow?.judged
            ?.filter { Prefs.quarUntil(this, it.pkg!!) > now }
            ?.maxByOrNull { it.layer }?.pkg
        var effFg = fg
        if (stackQuarPkg != null && Prefs.quarUntil(this, effFg) <= now) effFg = stackQuarPkg
        val covered = quarCoverPkg
        // While the covered package is still visible as a window,
        // it IS the cover's foreground (the countdown refreshes
        // through the show branch below) and the departure branch
        // must not read the Settings beneath its PiP as a leaving.
        if (covered != null && covered == stackQuarPkg) effFg = covered
        val coveredVisible = covered != null &&
            stackNow?.judged?.any { it.pkg == covered } == true
        when {
            effFg.isNotBlank() && effFg != packageName && Prefs.quarUntil(this, effFg) > now -> {
                quarCoverPkg = effFg
                showQuarantineCover(effFg, Prefs.quarAttr(this, effFg), Prefs.quarUntil(this, effFg) - now, Prefs.quarLevel(this, effFg))
            }
            covered != null && Prefs.quarUntil(this, covered) <= now -> {
                quarCoverPkg = null
                hideQuarantineCover()
            }
            effFg.isNotBlank() && rawFg != packageName && effFg != covered && !coveredVisible -> {
                quarCoverPkg = null
                hideQuarantineCover()
            }
            // else: ambiguous reading (blank, or our own package with no
            // cover up) — leave the cover exactly as it is.
        }
    }

    private fun ensureQuarView(wm: WindowManager): TextView? {
        quarView?.let { return it }
        val v = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.rgb(230, 237, 247))
            // UI-polish Run 2 (presentation only): ink-navy cover.
            setBackgroundColor(android.graphics.Color.argb(242, 11, 18, 32))
            setLineSpacing(0f, 1.3f)
            setPadding(48, 48, 48, 48)
        }
        // v0.6.1: the cover must NEVER trap. NOT_TOUCHABLE + NOT_FOCUSABLE:
        // it is a visual wall only — every touch and every system gesture
        // (Home, recents, back) passes straight through, so the user can
        // leave or kill the covered app at any moment. v0.6.0's touchable
        // full-screen window swallowed the Home gesture and locked the
        // whole interface; with a bad threshold that would brick the phone
        // in practice.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        return try {
            wm.addView(v, params)
            quarView = v
            v
        } catch (e: Exception) {
            failLog("other", "cover quarantine addView ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun showQuarantineCover(pkg: String, attr: String, remainMs: Long, level: Int) {
        val remainSec = (remainMs + 999) / 1000
        mainHandler.post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val v = ensureQuarView(wm) ?: return@post
            if (quarShownPkg != pkg || quarShownSecond != remainSec) {
                quarShownPkg = pkg
                quarShownSecond = remainSec
                val mmss = "%d:%02d".format(remainSec / 60, remainSec % 60)
                val nextNote = if (quarDurationSec(level) >= Prefs.quarMaxSec(this)) "maximum cooldown" else "next block: ${quarDurationSec(level + 1)}s"
                // UI-polish Run 4 (presentation only): same text,
                // spans only — the title line and the cooldown timer
                // are bold so the cover reads at a glance. Characters
                // are byte-identical to the pre-Run-4 string.
                val coverText = "Blocked by ScreenGuard\n\n$pkg\n$attr\n\nCooldown $mmss\n(block #$level — $nextNote)"
                val spanned = android.text.SpannableString(coverText)
                spanned.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    0, "Blocked by ScreenGuard".length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val cdStart = coverText.indexOf("Cooldown $mmss")
                if (cdStart >= 0) {
                    spanned.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        cdStart, cdStart + "Cooldown $mmss".length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                v.text = spanned
            }
        }
    }

    private fun hideQuarantineCover() {
        val v = quarView ?: return
        quarView = null
        quarShownPkg = null
        quarShownSecond = -1L
        mainHandler.post {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {
                failLog("other", "window removeView (quarantine cover) ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ---- v0.10.10: launcher light-response cover (fixed 3 s) ----

    private var launcherCoverView: TextView? = null
    private var launcherCoverGen = 0

    /** Show the generic preview-block cover for exactly
     *  [LAUNCHER_COVER_MS], then dismiss it. A separate view from the
     *  quarantine cover (whose lifecycle is the cooldown state
     *  machine); the text is deliberately generic — the guard cannot
     *  know which app's preview convicted. NOT_TOUCHABLE +
     *  NOT_FOCUSABLE, like the quarantine cover: a visual wall only,
     *  so even during those 3 s the user is never trapped. Runs on the
     *  main thread (posted from executeBlock). */
    private fun showLauncherCover() {
        val gen = ++launcherCoverGen
        hideLauncherCover()
        try {
            val v = TextView(this).apply {
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.rgb(230, 237, 247))
                // UI-polish Run 2 (presentation only): same ink-navy
                // recipe as the quarantine cover.
                setBackgroundColor(android.graphics.Color.argb(242, 11, 18, 32))
                setLineSpacing(0f, 1.3f)
                setPadding(48, 48, 48, 48)
                // UI-polish Run 4 (presentation only): title line
                // bold via spans — text byte-identical, as the
                // quarantine cover above.
                val lt = "Blocked by ScreenGuard\n\nBlocked: explicit content detected in app previews."
                text = android.text.SpannableString(lt).apply {
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        0, "Blocked by ScreenGuard".length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            )
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, params)
            launcherCoverView = v
        } catch (e: Exception) {
            failLog("other", "cover launcher addView ${e.javaClass.simpleName}: ${e.message}")
        }
        mainHandler.postDelayed({
            // A re-trigger bumps the generation and keeps its own cover.
            if (gen == launcherCoverGen) hideLauncherCover()
        }, LAUNCHER_COVER_MS)
    }

    private fun hideLauncherCover() {
        val v = launcherCoverView ?: return
        launcherCoverView = null
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {
            failLog("other", "window removeView (launcher cover) ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** v0.3.0 instant mode: confirmation state for the current package. */
    private var streakPkg: String? = null
    private var instantSecureStreak = 0

    /** v0.4.0: generation counter for screenshot captures — the watchdog
     *  bumps it to invalidate a wedged capture whose callback may still
     *  arrive later and must then be discarded. */
    private var captureGen = 0

    /** v0.11.10: global post-block SUS deadline (armed in
     *  executeBlock, past the launcher-guard return; a further
     *  block re-arms it). While live, susActive() is true for
     *  every package. In-memory only, like the v0.4.1 watch it
     *  replaces: a service restart drops it. */
    private var postBlockSusUntilMs = 0L

    /** v0.11.12: paranoid window deadline (armed in executeBlock's
     *  launcher-guard branch; a fresh launcher-guard conviction
     *  re-arms it — trips and covers never do). In-memory only,
     *  like the post-block window: a service restart drops it. */
    private var paranoidUntilMs = 0L

    /** v0.11.12: launcher-guard conviction counter, so
     *  processFrame can tell a same-frame conviction (which
     *  already fired the guard action and restarted the paranoid
     *  window) from a merely warm frame that should take the
     *  hair trigger instead. */
    private var launcherGuardCount = 0

    /** v0.11.0: last analysed frame's Falconsai scores (for static
     *  re-feeds, which replay the last frame's signal). */
    private var lastFalFull: Float? = null
    private var lastFalCrop: Float? = null

    /** v0.10.10: visual crop outline state — the tracker's ACTIVE box
     *  from the most recent analysed frame, mirrored for the cosmetic
     *  overlay (syncRegionOutline reads these from tick()). */
    @Volatile private var regionVisBox: MotionRegionTracker.Box? = null
    @Volatile private var regionVisPkg: String? = null
    @Volatile private var regionVisW = 0
    @Volatile private var regionVisH = 0
    private var outlineView: RegionOutlineView? = null
    private var outlineKey: String? = null

    /** v0.10.8: live motion-region tracker (Alejandro's crop idea,
     *  benched in BENCH-CROP-2026-10-27.md). Fed every captured frame;
     *  when it reports a stable ACTIVE region (a playing video),
     *  Falconsai additionally judges that region's crop as its own
     *  whole picture. Failure-isolated at the call site: a tracker
     *  fault disables the region for that frame only. */
    private val regionTracker = MotionRegionTracker { line -> DebugLog.log(this, line) }
    @Volatile private var regionErrorLogged = false

    // ---- v0.11.0: Falconsai crash-loop latch (the v0.10.1 Freepik
    // pattern, carried to the only model) ----
    // A native death inside the FAL stage leaves no Java trace, so it
    // is detected from OUTSIDE the process: an attempt flag is
    // committed (sync) just before the first FAL inference of a process
    // and cleared by the first success, which also marks FAL proven
    // for this install. If a service start finds the attempt flag set
    // with FAL never proven, the previous process died inside FAL —
    // latch FAL off for this versionCode (a newer build clears the
    // latch and tries again). The failure (stage + detail) is
    // persisted in Prefs, stated at every service start, and shown on
    // the Home screen — never silent (the v0.10.2 lesson).

    private fun appVersionCode(): Int = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionCode
    } catch (e: Exception) {
        // v0.11.14: was a bare swallow — a failed version query
        // silently degrades the FAL latch's version comparison.
        failLog("other", "version query ${e.javaClass.simpleName}: ${e.message}")
        -1
    }

    /**
     * Resolve the FAL latch at service start. A latch naming THIS
     * versionCode keeps FAL off. A latch naming a different one means a
     * newer build arrived — clear it and try FAL again. And if a
     * previous process left the attempt flag set without ever proving
     * FAL, it died inside the FAL stage: latch off now. Either way the
     * state is stated to BOTH log sinks at every start.
     */
    private fun resolveFalLatch() {
        val vc = appVersionCode()
        val latchedVc = Prefs.falLatchedVersion(this)
        when {
            latchedVc == vc && vc >= 0 -> falLatchedOff = true
            latchedVc >= 0 -> Prefs.clearFalLatch(this) // stale latch from another versionCode: retry FAL
        }
        if (!falLatchedOff && Prefs.falAttemptInProgress(this) && !Prefs.falProven(this)) {
            // No exception was ever recorded — the previous process died
            // natively inside the FAL stage. Persist that as the failure
            // too, so the Home screen can say why FAL is off.
            Prefs.setFalFailure(
                this, "run (suspected native death)",
                "previous process died during the first FAL inference (attempt flag left set, no exception recorded)"
            )
            Prefs.latchFalOff(this, vc)
            falLatchedOff = true
        }
        val line = if (falLatchedOff) {
            val st = Prefs.falFailStage(this)
            val dt = Prefs.falFailDetail(this)
            if (st.isNotBlank()) "FAL state: LATCHED OFF ($st: $dt)"
            else "FAL state: LATCHED OFF (reason not recorded)"
        } else {
            "FAL state: ARMED"
        }
        DebugLog.log(this, line)
        Prefs.appendLog(this, ts() + " " + line)
    }

    /**
     * Latch FAL off from inside the FAL stage after a Java-level
     * failure. [stage] ("load" / "run") and [detail] (exception class +
     * message) are persisted via Prefs.setFalFailure — written to BOTH
     * log sinks here — so the reason survives process death, a cleared
     * debug log, and shows on the Home screen. Pixel grading stops;
     * the luminance-based secure/incognito path keeps working.
     */
    private fun latchFalOffNow(stage: String, detail: String) {
        falLatchedOff = true
        falModel = null
        Prefs.putStatus(this, "model_ok", false)
        Prefs.setFalFailure(this, stage, detail)
        Prefs.latchFalOff(this, appVersionCode())
        DebugLog.log(this, "FAL latched OFF ($stage: $detail) — no scene grading; incognito/secure blocking still active")
        Prefs.appendLog(this, ts() + " FAL latched OFF ($stage: $detail) — no scene grading; incognito/secure blocking still active")
    }

    /**
     * Every FAL inference goes through here. The first call of a
     * process brackets the stage with the persisted attempt flag: it is
     * committed (sync) BEFORE inference, so a native death leaves it
     * set for the next service start to find; the first success marks
     * FAL proven. A Java-level failure latches FAL off and returns
     * null.
     */
    private fun falScore(model: FalconModel, bmp: Bitmap): Float? {
        analysisFalPasses++ // v0.14.0: per-analysis pass-set accounting (governor latency arm)
        if (!falFirstRunDone) Prefs.setFalAttempt(this, true)
        return try {
            val s = model.score(bmp)
            if (!falFirstRunDone) {
                falFirstRunDone = true
                Prefs.setFalProven(this)
                DebugLog.log(this, "FAL first inference ok — model proven")
            }
            s
        } catch (t: Throwable) {
            failLog("model", "fal run ${t.javaClass.simpleName}: ${t.message} — latching FAL off")
            latchFalOffNow("run", "${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * v0.11.6: every classifier inference goes through here, the
     * tripwire's failure-isolation boundary. Deliberately NOT the
     * FAL latch pattern: the classifier is an accessory — if it
     * fails, it is simply gone for this process (clsModel nulled,
     * reason kept in clsFailReason and the CLS state line), and
     * the guard continues Falconsai-only, exactly v0.11.5. No
     * persisted latch, no retry: unlike Falconsai this model +
     * loading route ran reliably for weeks (v0.4.0–v0.10.10), and
     * nothing protective depends on it.
     */
    private fun clsScore(model: SceneClassifier, bmp: Bitmap): Float? {
        analysisClsPasses++ // v0.14.0: per-analysis pass-set accounting (governor latency arm)
        return try {
            val s = model.classify(bmp)
            if (!clsFirstRunDone) {
                clsFirstRunDone = true
                DebugLog.log(this, "CLS first inference ok — tripwire live")
            }
            s
        } catch (t: Throwable) {
            val reason = "run: ${t.javaClass.simpleName}: ${t.message}"
            clsModel = null
            clsFailReason = reason
            Prefs.putStatus(this, "cls_state", "UNAVAILABLE ($reason)")
            DebugLog.log(this, "CLS state: UNAVAILABLE ($reason)")
            failLog("model", "cls run ${t.javaClass.simpleName}: ${t.message} — tripwire off, Falconsai-only continues")
            Prefs.appendLog(this, ts() + " CLS state: UNAVAILABLE ($reason) — Falconsai-only")
            null
        }
    }


    /** UI-armed deterministic test of the block path (overlay + Home). */
    private fun testBlock(pkg: String) {
        val enforce = Prefs.enforceMode(this)
        cooldownUntilMs = System.currentTimeMillis() + BLOCK_BLIND_MS
        showOverlayMessage(
            "TEST block",
            "Fired from the ScreenGuard UI — no content was detected.\nThis tests overlay + Home only.",
            enforce
        )
        if (enforce) performGlobalAction(GLOBAL_ACTION_HOME)
        Prefs.appendLog(this, ts() + " TEST BLOCK fired (pkg=$pkg, enforce=$enforce)")
        DebugLog.log(this, "TEST BLOCK fired pkg=$pkg enforce=$enforce")
    }

    private var overlayView: TextView? = null
    private fun showOverlayMessage(title: String, detail: String, enforce: Boolean) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        hideOverlay(wm)
        val tv = TextView(this).apply {
            // UI-polish Run 5 (presentation only): this block message
            // was the last GuardService surface still on the pre-polish
            // recipe (emoji header, pure black). It now matches the
            // Run 2 covers — ink-navy, palette text, 1.3 line spacing,
            // header + title bold via spans. The title / detail /
            // enforce lines are byte-identical; only the decorative
            // emoji header is gone, as on the covers. Window params,
            // flags and the 3.5 s dismiss below are unchanged.
            val msgText = "ScreenGuard\n\n$title\n$detail\n\n" +
                    if (enforce) "The app was closed." else "Warning only (test mode)."
            val spanned = android.text.SpannableString(msgText)
            spanned.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, "ScreenGuard".length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val titleStart = msgText.indexOf(title)
            if (titleStart >= 0) {
                spanned.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    titleStart, titleStart + title.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = spanned
            textSize = 20f
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.argb(242, 11, 18, 32))
            setTextColor(android.graphics.Color.rgb(230, 237, 247))
            setLineSpacing(0f, 1.3f)
            setPadding(60, 60, 60, 60)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        try {
            wm.addView(tv, params)
            overlayView = tv
            mainHandler.postDelayed({ hideOverlay(wm) }, 3500)
        } catch (e: Exception) {
            // Overlay is best-effort; the Home action still applies.
            // v0.11.14: the failure itself is marked now.
            failLog("other", "overlay block-message addView ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun hideOverlay(wm: WindowManager) {
        overlayView?.let {
            try { wm.removeView(it) } catch (e: Exception) {
                failLog("other", "window removeView (block message) ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        overlayView = null
    }

    private fun ts(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
