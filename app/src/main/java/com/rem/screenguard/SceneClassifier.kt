package com.rem.screenguard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.nio.FloatBuffer

/**
 * v0.4.0 second signal: NudeNet's original (v0) scene classifier —
 * retired as a judge in v0.11.0 and RESTORED in v0.11.6 as a SUS
 * TRIPWIRE ONLY (Alejandro's design). Falconsai is polarised — it
 * reads ≈0 or ≈1 with almost nothing in between — so SUS MODE's
 * Falconsai-only trip (≥0.30) almost never fires on borderline
 * content. This model is the opposite: a mediocre judge but a fine
 * thermometer, handing out graded mid-range numbers (BENCH-ALL:
 * explicit stills median 0.90, explicit crops 0.89; innocent full
 * frames mostly 0.01–0.03, the fully-clothed comedy reel's upper
 * tail excepted). GuardService calls it once per analysed frame
 * (full frame, plus the motion-region crop when a region is
 * active) and its ONLY consumer is the suspicion trigger: a
 * reading at/above the package's effective trip bar (v0.11.8:
 * 0.30 in risky apps, 0.50 elsewhere — GuardService.clsTripBar)
 * (re)trips SUS MODE so Falconsai samples densely for 5 s. It has NO path to a block, a pending park, or the crop
 * accumulator — its outputs never enter handleSignal at all (see
 * the v0.11.6 block in GuardService.processFrame).
 *
 * History, for honesty: as a judge (v0.4.0–v0.10.10) it read whole
 * frames and returned P(unsafe); offline A/B on Alejandro's field
 * misses (2026-10-26) is why it existed — on 15 real frames the
 * detector managed 0.12-0.79 while this classifier scored the
 * explicit ones 0.76-1.00. Its known weakness is skin-fraction
 * false positives (a tight bikini close-up scored 0.88; the comedy
 * reel's content crops reach 0.98) — disqualifying for a judge,
 * cheap for a tripwire: a false trip buys 5 s of dense Falconsai
 * sampling and nothing else, and the v0.11.6 refractory mutes
 * repeat classifier-only trips for 60 s after a window in which
 * Falconsai never read ≥0.30.
 *
 * Model: classifier_model.onnx (NudeNet v0 release; asset SHA-256
 * e0746121167fd94c9b5327831472221dca88fb3746c2a5ffd6b6f301fc1ff04a,
 * byte-identical to the v0.9.0-era asset), input NHWC 256x256
 * float RGB /255 (plain stretch resize — matches the offline rig
 * exactly), output softmax [unsafe, safe]; index 0 is unsafe.
 * Loading is its original route — bytes via assets.open +
 * createSession with ALL_OPT — which ran reliably for weeks.
 */
class SceneClassifier(ctx: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    @Volatile var loadError: String? = null
        private set

    /** Wall-clock cost of the most recent [classify] call, inference
     *  only (v0.11.6: mirrors FalconModel.lastMs for the CLS lines). */
    @Volatile var lastMs: Long = 0L
        private set

    init {
        val modelBytes = ctx.assets.open("classifier.onnx").readBytes()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelBytes, opts)
        inputName = session.inputNames.iterator().next()
    }

    /** P(unsafe) for [bitmap], 0..1. Throws on inference failure. */
    fun classify(bitmap: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT, INPUT, true)
        try {
            val px = IntArray(INPUT * INPUT)
            scaled.getPixels(px, 0, INPUT, 0, 0, INPUT, INPUT)
            val buf = FloatBuffer.allocate(INPUT * INPUT * 3)
            for (p in px) {
                buf.put(Color.red(p) / 255f)
                buf.put(Color.green(p) / 255f)
                buf.put(Color.blue(p) / 255f)
            }
            buf.rewind()
            val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, INPUT.toLong(), INPUT.toLong(), 3))
            val t0 = System.currentTimeMillis()
            val out = session.run(mapOf(inputName to tensor))
            try {
                @Suppress("UNCHECKED_CAST")
                val arr = (out[0].value as Array<FloatArray>)[0]
                lastMs = System.currentTimeMillis() - t0
                return arr[0]
            } finally {
                out.close()
                tensor.close()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    companion object {
        const val INPUT = 256
    }
}
