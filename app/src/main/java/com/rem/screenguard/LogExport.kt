package com.rem.screenguard

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.2.1: the debug log outgrew copy-paste and share-as-text (a single field
 * test produces thousands of lines). Export writes the full log to a real
 * file in the public Downloads folder via MediaStore (no storage permission
 * needed for our own file on Android 10+), where it can be attached to a
 * chat directly; the share intent then carries the file's content URI
 * instead of a giant text blob.
 */
object LogExport {

    const val REQ_SAVE_LOG = 4101

    /** Display name of the most recent successful Downloads export. */
    var lastFileName: String? = null
        private set

    fun suggestedName(): String = "screenguard-log-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

    /**
     * v0.2.2: system file-picker save (ACTION_CREATE_DOCUMENT). The v0.2.1
     * MediaStore one-tap export failed on Alejandro's phone and — worse —
     * failed SILENTLY into the old text share. The picker needs no storage
     * permission, works on every OEM build, and lets the user choose the
     * folder (Downloads) and name himself, so the file is really there.
     */
    fun saveIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "text/plain"
        putExtra(Intent.EXTRA_TITLE, suggestedName())
    }

    /** Writes the current debug log to a picker-chosen [uri]. */
    fun writeTo(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openOutputStream(uri, "w")?.use { out ->
            out.write(DebugLog.text(ctx).toByteArray(Charsets.UTF_8))
        } != null
    } catch (e: Exception) {
        false
    }

    /** Writes the current debug log to Downloads; returns its content Uri. */
    fun exportToDownloads(ctx: Context): Uri? {
        val name = suggestedName()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver
            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(DebugLog.text(ctx).toByteArray(Charsets.UTF_8))
        } ?: return null
        lastFileName = name
        return uri
    }

    /** Export to Downloads, Toast the outcome (never silently), then share the file. */
    fun shareWithFeedback(activity: Activity) {
        val uri = try {
            exportToDownloads(activity)
        } catch (e: Exception) {
            null
        }
        if (uri != null) {
            Toast.makeText(activity, "Saved to Downloads/$lastFileName", Toast.LENGTH_LONG).show()
            DebugLog.log(activity, "LOG EXPORT ok $lastFileName")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ScreenGuard debug log")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "Share debug log file"))
        } else {
            Toast.makeText(
                activity,
                "Downloads export FAILED — use 'Save log to file' instead",
                Toast.LENGTH_LONG
            ).show()
            DebugLog.log(activity, "LOG EXPORT FAILED (MediaStore) — picker save offered")
        }
    }

    /** Handle the ACTION_CREATE_DOCUMENT result: write the log, Toast the outcome. */
    fun handleSaveResult(activity: Activity, resultCode: Int, data: Intent?) {
        val uri = data?.data
        val ok = resultCode == Activity.RESULT_OK && uri != null && writeTo(activity, uri)
        Toast.makeText(
            activity,
            if (ok) "Log saved to file" else "Log save cancelled / failed",
            Toast.LENGTH_LONG
        ).show()
        DebugLog.log(activity, "LOG SAVE via picker ${if (ok) "ok" else "cancelled/failed"}")
    }
}
