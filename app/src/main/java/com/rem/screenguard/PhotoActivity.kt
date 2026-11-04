package com.rem.screenguard

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView

/**
 * v0.1.6 capture-bench screen: shows the bundled reference photo fullscreen so
 * the guard service can screenshot a KNOWN image through the exact production
 * path (takeScreenshot -> HardwareBuffer -> copy) and score it. If the logged
 * CAPBENCH scores match the detector bench (~FEET 0.59), the screenshot path
 * is faithful and earlier zeros were genuine model weakness on small
 * thumbnails; if they come back ~0.00, the conversion corrupts the pixels.
 * Tap anywhere to close.
 */
class PhotoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            assets.open("bench.jpg").use { setImageBitmap(BitmapFactory.decodeStream(it)) }
            setOnClickListener { finish() }
        }
        setContentView(iv)
    }
}
