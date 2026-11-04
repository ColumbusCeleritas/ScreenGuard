package com.rem.screenguard

import android.content.Context
import android.content.SharedPreferences

/** Simple settings + status store shared between the service and the UI. */
object Prefs {
    private const val FILE = "screenguard"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Play prominent disclosure (priority 5, 2026-10-31) ----
    // Versioned affirmative consent for AccessibilityService
    // screenshot access (see DisclosureActivity). Accepted only when
    // the stored version is >= the current text's version, so a
    // material change to the disclosure asks again.
    const val DISCLOSURE_VERSION = 1
    fun disclosureAccepted(ctx: Context): Boolean =
        get(ctx).getInt("disclosure_accepted_version", 0) >= DISCLOSURE_VERSION
    fun disclosureAcceptedTs(ctx: Context): Long =
        get(ctx).getLong("disclosure_accepted_ts", 0L)
    fun setDisclosureAccepted(ctx: Context) {
        get(ctx).edit()
            .putInt("disclosure_accepted_version", DISCLOSURE_VERSION)
            .putLong("disclosure_accepted_ts", System.currentTimeMillis())
            .apply()
    }

    // --- Settings (written by MainActivity / CustomActivity) ---
    // v0.11.0: the per-model bars are gone with their models — the
    // Falconsai bars are named constants in GuardService (one model,
    // nothing to tune per-model). What remains a setting: the
    // suspicion warmth bar, the incognito frame count and the
    // cadences. (The cooldown ladder left the settings in v0.15.1 —
    // it is code-only behaviour now; see the QUAR_* constants below.)
    // v0.11.5: the whole-frame corroboration
    // counts/windows (strong_hits, pending_window_s, persist_frames,
    // persist_window_s) are gone too — the reformed whole-frame
    // pending (fixed 2 hits, 10 s warmth expiry) replaced the
    // machinery they tuned, so their keys are simply never read again.
    fun suspFal(ctx: Context): Float = get(ctx).getFloat("susp_fal", 0.40f) // suspicion warmth: either Falconsai pass
    fun secureFrames(ctx: Context): Int = get(ctx).getInt("secure_frames_needed", 2)
    fun attentionMs(ctx: Context): Long = get(ctx).getInt("attention_s", 3) * 1000L
    // v0.15.1 (Alejandro's correction, 2026-10-31): quarantine duration
    // is app BEHAVIOUR, not a user setting. The small ladder values of
    // the settings era (20 s base and friends) were test conveniences
    // so blocks could be iterated quickly; the production ladder below
    // is ground truth in code and an update overrides stored leftovers.
    // Enforcement, all shipped in this release: the getters read ONLY
    // these constants; the presets no longer write the keys; the
    // Custom screen shows the ladder read-only; and GuardService
    // deletes any stored quar_base_s / quar_max_s / quar_reset_min at
    // every service start, logging the QUAR ladder line as proof.
    // Ladder: 10 s first block, doubling per repeat (20, 40, 80,
    // 160, 320), 600 s cap; the escalation level resets after 30
    // clean minutes. (v0.18.0, Alejandro's order 2026-11-03: base
    // shortened 120 s -> 10 s; the 120/240/480/600 ladder of
    // v0.15.0–v0.17.0 read too paranoid in the field.)
    const val QUAR_BASE_SEC = 10
    const val QUAR_MAX_SEC = 600
    const val QUAR_RESET_MS = 30 * 60_000L
    fun quarBaseSec(ctx: Context): Int = QUAR_BASE_SEC
    fun quarMaxSec(ctx: Context): Int = QUAR_MAX_SEC
    fun quarResetMs(ctx: Context): Long = QUAR_RESET_MS
    fun consecutiveNeeded(ctx: Context): Int = get(ctx).getInt("consecutive", 2)
    fun enforceMode(ctx: Context): Boolean = get(ctx).getBoolean("enforce", true) // true=close app, false=warn only
    fun riskyIntervalMs(ctx: Context): Long = get(ctx).getLong("risky_ms", 5000L) // v0.7.1: battery pass (Alejandro) — 5 s risky / 15 s other; suspicion bursts override to max cadence
    fun normalIntervalMs(ctx: Context): Long = get(ctx).getLong("normal_ms", 15000L)
    // v0.13.0: SYSTEM tier sanity cadence (app tiers design) — dialer /
    // clock / calculator / contacts foregrounds are only re-checked
    // this often while idle; any escalation restores full cadence.
    fun systemIntervalMs(ctx: Context): Long = get(ctx).getLong("system_ms", 60000L)
    fun verbose(ctx: Context): Boolean = get(ctx).getBoolean("verbose", true) // PoC default: ON
    fun blockSecure(ctx: Context): Boolean = get(ctx).getBoolean("block_secure", true) // incognito/FLAG_SECURE windows
    fun fadeFastStrong(ctx: Context): Boolean = get(ctx).getBoolean("fade_fast_strong", true) // v0.2.0: strong content fades 2x faster
    fun flickerCaptures(ctx: Context): Boolean = get(ctx).getBoolean("flicker_captures", true) // v0.2.4: hide the fade overlay ~0.1 s around each capture so the model grades the true screen
    fun instantBlock(ctx: Context): Boolean = get(ctx).getBoolean("instant_block", true) // v0.3.0: block at first sight (weak hits need a 2nd frame); off = v0.2.x fade/grace
    fun debugOverlay(ctx: Context): Boolean = get(ctx).getBoolean("debug_overlay", false) // v0.5.2: small top-left live FAL readout on screen
    // v0.16.0: the on-screen score dots (red strikes / yellow crop
    // points). A DISPLAY preference only — it changes nothing about
    // scoring or blocking, so unlike behaviour values it is
    // legitimately a pref. The overlay's hide chip and the Settings
    // switch both write this key.
    fun scoreDots(ctx: Context): Boolean = get(ctx).getBoolean("score_dots", true)
    fun setScoreDots(ctx: Context, v: Boolean) { get(ctx).edit().putBoolean("score_dots", v).apply() }

    // ---- v0.9.0: protection levels (Alejandro) ----
    // Gentle / Balanced / Strict are deliberately CLOSE bundles — small
    // tweaks around the tuned Balanced config, not different products.
    // Hand-editing anything on the Custom screen marks the level "custom".
    // v0.11.0: with one model and its bars fixed as named constants, the
    // levels differ ONLY by cadence (how often screens are checked) —
    // the corroboration counts and windows stay at their shared
    // defaults for every level, and the quarantine ladder is code-only
    // since v0.15.1 (identical at every level).
    fun protectionLevel(ctx: Context): String =
        get(ctx).getString("protection_level", "balanced") ?: "balanced"

    fun markCustom(ctx: Context) {
        if (protectionLevel(ctx) != "custom") {
            get(ctx).edit().putString("protection_level", "custom").apply()
        }
    }

    private data class Preset(
        val riskyMs: Long, val normalMs: Long, val systemMs: Long
    )

    fun applyPreset(ctx: Context, level: String) {
        // Cadences v0.11.1 (Alejandro's order: dangerous apps — Chrome,
        // X — checked every 3 s at Balanced; everything else every
        // 10–20 s across the levels, Instagram included). Nothing else
        // differs between levels. v0.15.1: presets no longer write any
        // quarantine key — the ladder is code-only (QUAR_* above).
        // v0.13.0 (app tiers + Alejandro's amendment, Oct 29): presets
        // now write THREE cadences — SUSPICIOUS (risky_ms) 4/3/2 s,
        // NORMAL (normal_ms) 20/15/10 s (the amendment: today's
        // other-apps values, NOT the design draft's 30/20/15), and
        // SYSTEM sanity (system_ms) 120/60/30 s.
        // v0.14.5 (Alejandro's universal-ceiling ruling, Oct 30):
        // SYSTEM sanity is 60/60/30 s — Gentle's 120 s is abolished;
        // nothing idles unscanned past 60 s (see SANITY_CEILING_MS).
        val b = when (level) {
            "gentle" -> Preset(4000L, 20000L, 60000L)
            "strict" -> Preset(2000L, 10000L, 30000L)
            else -> Preset(3000L, 15000L, 60000L)
        }
        get(ctx).edit()
            .putLong("risky_ms", b.riskyMs)
            .putLong("normal_ms", b.normalMs)
            .putLong("system_ms", b.systemMs)
            .putString("protection_level", level)
            .apply()
    }

    // ---- v0.13.0: device capability — governor rung + benchmark cap ----
    // (device-capability design, Oct 29). Rungs: 3 strict, 2 balanced,
    // 1 gentle, 0 FLOOR. The effective rung is the MINIMUM of the
    // user's chosen rung, the benchmark cap and the governor rung;
    // user settings are never rewritten by either mechanism — the
    // effective rung only clamps cadences at scheduling time
    // (GuardService.tierIntervalMs via rungFloorMs below).
    // -1 from capRung/govRung means "not in force".

    /** The rung the user's current selection asks for. Custom derives
     *  from its suspicious cadence (the ladder's fastest tier decides). */
    fun chosenRung(ctx: Context): Int = when (protectionLevel(ctx)) {
        "gentle" -> 1
        "strict" -> 3
        "custom" -> when {
            riskyIntervalMs(ctx) <= 2500L -> 3
            riskyIntervalMs(ctx) <= 3500L -> 2
            else -> 1
        }
        else -> 2
    }

    fun capRung(ctx: Context): Int = get(ctx).getInt("cap_level", -1)
    fun capEpisodeOk(ctx: Context): Boolean = get(ctx).getBoolean("cap_episode_ok", true)
    fun capTs(ctx: Context): Long = get(ctx).getLong("cap_ts", 0L)
    fun capFalMs(ctx: Context): Long = get(ctx).getLong("cap_fal_ms", -1L)
    fun capClsMs(ctx: Context): Long = get(ctx).getLong("cap_cls_ms", -1L)
    fun capFailDetail(ctx: Context): String = get(ctx).getString("cap_fail_detail", "") ?: ""
    fun capDevice(ctx: Context): String = get(ctx).getString("cap_device", "") ?: ""

    /** A completed benchmark writes the whole cap record at once. */
    fun setCap(ctx: Context, rung: Int, episodeOk: Boolean, falMs: Long, clsMs: Long, failDetail: String, device: String) {
        get(ctx).edit()
            .putInt("cap_level", rung)
            .putBoolean("cap_episode_ok", episodeOk)
            .putLong("cap_ts", System.currentTimeMillis())
            .putLong("cap_fal_ms", falMs)
            .putLong("cap_cls_ms", clsMs)
            .putString("cap_fail_detail", failDetail)
            .putString("cap_device", device)
            .apply()
    }

    fun govRung(ctx: Context): Int = get(ctx).getInt("gov_rung", -1)
    fun govReason(ctx: Context): String = get(ctx).getString("gov_reason", "") ?: ""
    fun govTs(ctx: Context): Long = get(ctx).getLong("gov_ts", 0L)

    fun setGov(ctx: Context, rung: Int, reason: String) {
        get(ctx).edit()
            .putInt("gov_rung", rung)
            .putString("gov_reason", reason)
            .putLong("gov_ts", System.currentTimeMillis())
            .apply()
    }

    fun clearGov(ctx: Context) {
        get(ctx).edit().remove("gov_rung").remove("gov_reason").remove("gov_ts").apply()
    }

    /** Effective rung: the minimum of chosen / cap / governor. */
    fun effRung(ctx: Context): Int {
        var r = chosenRung(ctx)
        val cap = capRung(ctx)
        if (cap >= 0 && cap < r) r = cap
        val gov = govRung(ctx)
        if (gov >= 0 && gov < r) r = gov
        return r
    }

    fun rungName(rung: Int): String = when (rung) {
        3 -> "strict"
        2 -> "balanced"
        1 -> "gentle"
        else -> "floor"
    }

    // Tier ids shared with GuardService.tierOf.
    const val TIER_SUSPICIOUS = 0
    const val TIER_NORMAL = 1
    const val TIER_SYSTEM = 2

    // v0.14.5: the universal idle ceiling (Alejandro's ruling,
    // 2026-10-30): no idle interval anywhere may exceed 60 s — a
    // phone too weak for 1–4 s cadences is still never too weak
    // for one look a minute. GuardService clamps the SYSTEM /
    // sanity price to this at pricing time; the preset rows and
    // the rung floors below are written to respect it as well.
    const val SANITY_CEILING_MS = 60_000L

    /**
     * The cadence floor (ms) a rung imposes on a tier's idle interval:
     * a rung can only ever SLOW a tier, never speed it past the user's
     * own setting (the caller takes max(pref, floor)). FLOOR holds
     * suspicious at Gentle's 4 s (never slower), doubles normal to
     * 40 s and — v0.14.5, superseding the capability design's 120 s —
     * holds system sanity at the 60 s universal ceiling.
     */
    fun rungFloorMs(rung: Int, tier: Int): Long = when (tier) {
        TIER_SUSPICIOUS -> when (rung) { 3 -> 2000L; 2 -> 3000L; else -> 4000L }
        TIER_SYSTEM -> when (rung) { 3 -> 30000L; else -> 60000L }
        else -> when (rung) { 3 -> 10000L; 2 -> 15000L; 1 -> 20000L; else -> 40000L }
    }

    // ---- v0.11.0: Falconsai crash-loop latch ----
    // The v0.10.1 Freepik latch pattern, carried to the only model. A
    // death inside the FAL stage has to be detected from OUTSIDE the
    // process: an attempt flag is committed (sync) just before the
    // first FAL inference of a process and cleared by the first
    // success, which also marks FAL proven for this install. If a
    // service start finds the attempt flag set with FAL never proven,
    // the previous process died inside FAL — latch FAL off for this
    // versionCode (a newer build clears the latch and tries again).
    fun falAttemptInProgress(ctx: Context): Boolean = get(ctx).getBoolean("fal_attempt_in_progress", false)
    fun falProven(ctx: Context): Boolean = get(ctx).getBoolean("fal_proven", false)
    fun falLatchedVersion(ctx: Context): Int = get(ctx).getInt("fal_latched_version", -1)

    fun setFalAttempt(ctx: Context, on: Boolean) {
        get(ctx).edit().putBoolean("fal_attempt_in_progress", on).commit()
    }

    /** First FAL inference of this install succeeded: FAL is proven; clear the attempt flag and any recorded failure. */
    fun setFalProven(ctx: Context) {
        get(ctx).edit().putBoolean("fal_proven", true)
            .putBoolean("fal_attempt_in_progress", false)
            // FAL works now — any recorded failure is history.
            .remove("fal_fail_stage").remove("fal_fail_detail").remove("fal_fail_ts").commit()
    }

    fun latchFalOff(ctx: Context, versionCode: Int) {
        get(ctx).edit().putInt("fal_latched_version", versionCode).commit()
    }

    fun clearFalLatch(ctx: Context) {
        get(ctx).edit().remove("fal_latched_version").commit()
    }

    // ---- v0.11.0: last FAL failure, persisted ----
    // The v0.10.3 lesson: a latch reason that lives only in the debug
    // log is lost when the log is cleared before export. The failure
    // (stage + exception + time) is persisted here, written by every
    // latch path, shown on the Home screen, and cleared when FAL first
    // succeeds.
    fun falFailStage(ctx: Context): String = get(ctx).getString("fal_fail_stage", "") ?: ""
    fun falFailDetail(ctx: Context): String = get(ctx).getString("fal_fail_detail", "") ?: ""
    fun falFailTs(ctx: Context): Long = get(ctx).getLong("fal_fail_ts", 0L)

    fun setFalFailure(ctx: Context, stage: String, detail: String) {
        get(ctx).edit().putString("fal_fail_stage", stage)
            .putString("fal_fail_detail", detail)
            .putLong("fal_fail_ts", System.currentTimeMillis()).commit()
    }

    fun clearFalFailure(ctx: Context) {
        get(ctx).edit().remove("fal_fail_stage").remove("fal_fail_detail").remove("fal_fail_ts").commit()
    }

    // ---- v0.6.0: per-app quarantine state (dynamic keys; survives restarts) ----
    fun quarUntil(ctx: Context, pkg: String): Long = get(ctx).getLong("quar_until_$pkg", 0L)
    fun quarLevel(ctx: Context, pkg: String): Int = get(ctx).getInt("quar_level_$pkg", 0)
    fun quarLastBlock(ctx: Context, pkg: String): Long = get(ctx).getLong("quar_last_$pkg", 0L)
    fun quarAttr(ctx: Context, pkg: String): String = get(ctx).getString("quar_attr_$pkg", "") ?: ""
    fun setQuarantine(ctx: Context, pkg: String, until: Long, level: Int, lastBlock: Long, attr: String) {
        get(ctx).edit().putLong("quar_until_$pkg", until).putInt("quar_level_$pkg", level)
            .putLong("quar_last_$pkg", lastBlock).putString("quar_attr_$pkg", attr).apply()
    }
    fun clearQuarUntil(ctx: Context, pkg: String) {
        get(ctx).edit().putLong("quar_until_$pkg", 0L).apply()
    }

    // ---- v0.5.0: settings lock (anti-tamper ritual, Alejandro's design) ----
    // Once locked, every protection-weakening setting is frozen. Unlocking
    // takes: a prayer screen (UnlockActivity), then a timer, then an open
    // window before it re-locks on its own. A block during the timer
    // cancels the request (GuardService calls in).
    // PRODUCTION (v0.15.0, Alejandro's order 2026-10-31): the ritual
    // runs its real durations — prayer 60 s (UnlockActivity.PRAYER_MS),
    // wait timer 10 min, open window 10 min. (They were 10 s debug
    // values from 2026-10-26 until this release; the OPEN WINDOW has
    // been 10 minutes since his order of 2026-10-26 19:44.)
    const val LOCK_UNLOCKED = 0
    const val LOCK_LOCKED = 1
    const val LOCK_TIMER = 2
    const val LOCK_OPEN = 3
    const val LOCK_TIMER_MS = 10 * 60_000L // production wait: 10 minutes
    const val LOCK_WINDOW_MS = 10 * 60_000L // Alejandro: settings stay unlocked for 10 minutes

    fun settingsLocked(ctx: Context): Boolean = get(ctx).getBoolean("settings_locked", false)

    /** Resolved lock state; lazily advances/expires stages as time passes. */
    fun lockState(ctx: Context): Int {
        val p = get(ctx)
        if (!p.getBoolean("settings_locked", false)) return LOCK_UNLOCKED
        val stage = p.getInt("unlock_stage", 0)
        val deadline = p.getLong("unlock_deadline", 0L)
        val now = System.currentTimeMillis()
        return when {
            stage == 1 && now < deadline -> LOCK_TIMER
            stage == 1 -> { // timer elapsed: open the window (from timer end)
                val winEnd = deadline + LOCK_WINDOW_MS
                if (now < winEnd) {
                    p.edit().putInt("unlock_stage", 2).putLong("unlock_deadline", winEnd).apply()
                    LOCK_OPEN
                } else {
                    p.edit().putInt("unlock_stage", 0).apply()
                    LOCK_LOCKED
                }
            }
            stage == 2 && now < deadline -> LOCK_OPEN
            stage == 2 -> {
                p.edit().putInt("unlock_stage", 0).apply()
                LOCK_LOCKED
            }
            else -> LOCK_LOCKED
        }
    }

    fun lockDeadline(ctx: Context): Long = get(ctx).getLong("unlock_deadline", 0L)

    fun lockNow(ctx: Context) {
        get(ctx).edit().putBoolean("settings_locked", true).putInt("unlock_stage", 0).apply()
    }

    /** The legitimate exit: only reachable through the ritual's open window. */
    fun unlockFully(ctx: Context) {
        get(ctx).edit().putBoolean("settings_locked", false).putInt("unlock_stage", 0).apply()
    }

    fun beginUnlockTimer(ctx: Context) {
        get(ctx).edit().putInt("unlock_stage", 1)
            .putLong("unlock_deadline", System.currentTimeMillis() + LOCK_TIMER_MS).apply()
    }

    fun cancelUnlock(ctx: Context) {
        get(ctx).edit().putInt("unlock_stage", 0).apply()
    }

    /** Increment an Int status counter (ticks, cap_ok, cap_fail, diff_skip, …). */
    fun bump(ctx: Context, key: String) {
        putStatus(ctx, key, get(ctx).getInt(key, 0) + 1)
    }

    // --- Status / log (written by GuardService) ---
    fun putStatus(ctx: Context, key: String, value: Any) {
        val e = get(ctx).edit()
        when (value) {
            is String -> e.putString(key, value)
            is Long -> e.putLong(key, value)
            is Int -> e.putInt(key, value)
            is Float -> e.putFloat(key, value)
            is Boolean -> e.putBoolean(key, value)
        }
        e.apply()
    }

    fun appendLog(ctx: Context, line: String) {
        val p = get(ctx)
        val old = p.getString("log", "") ?: ""
        val lines = (old.split("\n").filter { it.isNotBlank() } + line).takeLast(50)
        p.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun log(ctx: Context): String = get(ctx).getString("log", "") ?: ""
}
