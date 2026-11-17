package com.rem.screenguard

/**
 * OBSERVABILITY ONLY: in-memory history of analysed-frame scores,
 * feeding the Home screen's score graph ([ScoreGraphActivity]) and
 * nothing else — no detection path ever reads this back, and
 * recording a sample cannot change any verdict, count, or block.
 *
 * v0.11.0: one sample per ANALYSED frame carries exactly the two
 * Falconsai scores — the whole-frame pass (falFull) and, when a
 * motion region was active, the crop pass (falCrop, null otherwise)
 * — so the graph is exactly two series and draws an honest gap where
 * no crop pass ran instead of a fake zero. (History: v0.10.6 created
 * this for the detector+classifier stack's 21 series; they left with
 * their models.)
 *
 * v0.11.7 (Alejandro's order — full debug visibility): the sample
 * also carries the tripwire classifier's two readings (clsFull /
 * clsCrop, same null-for-no-pass convention), so the graph gains
 * CLS FULL and CLS CROP series beside the Falconsai pair. Still
 * observability only: the classifier remains a SUS tripwire whose
 * scores no detection path reads; recording them changes nothing.
 *
 * v0.12.2: the sample also carries the ZoD burst's best zoom
 * verdict ([Sample.falZoom], null when no burst ran) so the graph
 * gains a FAL ZOOM series beside the existing four. Still
 * observability only.
 *
 * v0.11.14 (Alejandro's order — failure visibility): the store also
 * carries FAILURE events ([Failure]: timestamp + a short kind tag —
 * capture / watchdog / model / other), recorded by GuardService's
 * FAIL channel at the moment each `FAIL ` log line is emitted. The
 * graph draws each as a red vertical tick, so a failure storm is
 * visible as a red band instead of hiding between score lines —
 * the v0.11.12 capture drought (85 consecutive refusals, zero
 * analyses) would have painted the graph solid red. Same store,
 * same lifetime, same retention as the scores.
 *
 * In-memory only: process death clears it, which is fine — the graph
 * is a live instrument, not a record. Retention: at most
 * [MAX_SAMPLES] samples and at most [MAX_AGE_MS] of wall time,
 * whichever trims first.
 */
object ScoreHistory {

    data class Sample(
        val tsMs: Long,
        val pkg: String,
        /** Falconsai P(nsfw) on the whole frame. */
        val falFull: Float,
        /** Falconsai P(nsfw) on the motion-region crop, or null when no region was active. */
        val falCrop: Float?,
        /** Tripwire classifier P(unsafe) on the whole frame, or null when it did not run. */
        val clsFull: Float?,
        /** Tripwire classifier P(unsafe) on the motion-region crop, or null when no crop pass ran. */
        val clsCrop: Float?,
        /** v0.12.2: the ZoD burst's best Falconsai zoom verdict on this frame, or null when no burst ran. Observability only, like the rest of the sample. */
        val falZoom: Float? = null
    )

    /**
     * v0.11.14: one failure event — the moment a `FAIL ` line was
     * emitted, tagged by subsystem kind: "capture", "watchdog",
     * "model" or "other" (GuardService's FAIL channel assigns it).
     */
    data class Failure(
        val tsMs: Long,
        val kind: String
    )

    private const val MAX_SAMPLES = 2000
    private const val MAX_AGE_MS = 30 * 60_000L

    private val samples = java.util.ArrayDeque<Sample>()
    private val failures = java.util.ArrayDeque<Failure>()

    @Synchronized
    fun record(s: Sample) {
        samples.addLast(s)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        val cutoff = s.tsMs - MAX_AGE_MS
        while (!samples.isEmpty() && samples.first().tsMs < cutoff) samples.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Sample> = samples.toList()

    @Synchronized
    fun size(): Int = samples.size

    /** v0.11.14: record a failure event (same retention as scores). */
    @Synchronized
    fun recordFailure(tsMs: Long, kind: String) {
        failures.addLast(Failure(tsMs, kind))
        while (failures.size > MAX_SAMPLES) failures.removeFirst()
        val cutoff = tsMs - MAX_AGE_MS
        while (!failures.isEmpty() && failures.first().tsMs < cutoff) failures.removeFirst()
    }

    @Synchronized
    fun failureSnapshot(): List<Failure> = failures.toList()
}
