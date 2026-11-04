package com.rem.screenguard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * v0.11.0: the ONLY model. Falconsai/nsfw_image_detection (ViT, 224x224) in
 * the onnx-community export — file SHA-256
 * a4316a4fb750169ac4fcabaabee1fcbd982b0ee8c0cc63fe3e944954bb9a7d9c,
 * 343,401,688 bytes, raw-logit outputs, labels [normal, nsfw] (nsfw =
 * index 1). It grades a WHOLE picture (the full screen, or the motion
 * region as its own whole picture) and returns P(nsfw) in 0..1.
 *
 * Why it stands alone (offline bench over every preserved field sample,
 * model/BENCH-ALL-2026-10-27.md): the cleanest full-frame separator of the
 * twelve artifacts benched — explicit stills/crops median 1.00, and all
 * three benign specimens that defeated the v0.10.x stack (the fully-clothed
 * comedy reel, the political meme video, smooth CGI mannequins with no
 * anatomy) read ≈0.00 (benign worst 0.011 across ~150 benign whole
 * pictures), where the NudeNet detector and the old scene classifier both
 * fired. The detector, the old classifier, their tiles and every
 * corroboration rule built around them were removed this version; git
 * history preserves them.
 *
 * Preprocessing mirrors the bench rig (bench_extra.py run_falconsai)
 * EXACTLY — an offline parity run of this exact pipeline reproduced the
 * bench's signature numbers before this build was made: stretch-resize
 * 224x224 bilinear, RGB, (px/255 − 0.5)/0.5, NCHW float; the model emits
 * raw logits and the softmax is taken here (double precision, nsfw =
 * index 1). Android's Bitmap scaler with filter=true is bilinear,
 * matching the rig's INTER_LINEAR.
 *
 * Loading follows the v0.10.3 Freepik pattern, learned the hard way: the
 * asset is stream-copied to internal storage once and the session is
 * created from the file PATH (ORT mmaps the weights) with BASIC_OPT —
 * never a 343 MB byte[] in the Java heap, never ALL_OPT's heavy passes
 * over a huge graph. GuardService brackets load and first run with stage
 * markers plus a persisted crash-loop latch (see Prefs), so a native
 * death inside this stage latches FAL off instead of crash-looping, and
 * the state is stated at every service start.
 */
class FalconModel(ctx: Context, private val log: (String) -> Unit = {}) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    /** Wall-clock cost of the most recent [score] call, inference only. */
    @Volatile var lastMs: Long = 0L
        private set

    init {
        val modelFile = ensureModelFile(ctx)
        val opts = OrtSession.SessionOptions().apply {
            // BASIC_OPT (v0.10.3 lesson): ALL_OPT's optimisation passes
            // over a 343 MB graph are a prime load-failure suspect.
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        // File-path session: ORT memory-maps the weights from disk
        // instead of the app holding the whole model in the Java heap.
        session = env.createSession(modelFile.absolutePath, opts)
        inputName = session.inputNames.iterator().next()
    }

    /**
     * The model file in internal storage, stream-copied from the APK
     * asset on first use. Skips the copy when a file of exactly
     * [MODEL_BYTES] is already there; a copy interrupted by process death
     * leaves only the .tmp behind (or a short final file, which the
     * length check rejects next start) and is redone. Throws IOException
     * on any copy failure — GuardService's latch handles it.
     */
    private fun ensureModelFile(ctx: Context): File {
        val dest = File(ctx.filesDir, ASSET_NAME)
        if (dest.isFile && dest.length() == MODEL_BYTES) return dest
        log("FAL copy start ($ASSET_NAME, $MODEL_BYTES B)")
        val tmp = File(ctx.filesDir, "$ASSET_NAME.tmp")
        ctx.assets.open(ASSET_NAME).use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        }
        val copied = tmp.length()
        if (copied != MODEL_BYTES) {
            tmp.delete()
            throw IOException("Falconsai asset copy incomplete: $copied of $MODEL_BYTES bytes")
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IOException("Falconsai asset copy: rename to ${dest.name} failed")
        }
        log("FAL copy done")
        return dest
    }

    /**
     * P(nsfw) for [bmp], which may be the full frame or the motion-region
     * crop — either way it is judged as a whole picture, exactly as the
     * bench rig judged whole pictures.
     */
    fun score(bmp: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(bmp, INPUT, INPUT, true)
        val pixels = IntArray(INPUT * INPUT)
        scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        if (scaled !== bmp) scaled.recycle()
        val plane = INPUT * INPUT
        val input = FloatBuffer.allocate(3 * plane)
        // Bench rig: BGR→RGB (the rig reads BGR via OpenCV, so its channel
        // 0 write is the R plane), (px/255 − 0.5)/0.5, NCHW.
        for (i in pixels.indices) {
            val p = pixels[i]
            input.put(i, norm((p shr 16) and 0xFF))            // R
            input.put(plane + i, norm((p shr 8) and 0xFF))     // G
            input.put(2 * plane + i, norm(p and 0xFF))         // B
        }
        input.rewind()
        val t0 = System.currentTimeMillis()
        val tensor = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong()))
        val pNsfw = tensor.use {
            session.run(mapOf(inputName to it)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val logits = (out[0].value as Array<FloatArray>)[0]
                require(logits.size == 2) { "Falconsai output size ${logits.size}, expected 2 [normal, nsfw]" }
                val m = maxOf(logits[0], logits[1]).toDouble()
                val e0 = exp(logits[0].toDouble() - m)
                val e1 = exp(logits[1].toDouble() - m)
                (e1 / (e0 + e1)).toFloat()
            }
        }
        lastMs = System.currentTimeMillis() - t0
        return pNsfw
    }

    private fun norm(px: Int): Float = ((px / 255.0) - 0.5).toFloat() / 0.5f

    companion object {
        const val ASSET_NAME = "falconsai.onnx"
        const val MODEL_BYTES = 343_401_688L
        private const val INPUT = 224
    }
}
