package com.humanrewrite.keyboard.ime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Copies bundled sticker PNGs to a shareable content:// URI and sends them into the active app. */
class StickerStore(private val context: Context) {
    private val dir = File(context.cacheDir, "stickers").apply { mkdirs() }

    private fun fileFor(sticker: StickerCatalog.Sticker): File {
        val file = File(dir, "${sticker.codePoint}.png")
        if (!file.exists()) {
            context.assets.open(StickerCatalog.assetPath(sticker)).use { input ->
                FileOutputStream(file).use { input.copyTo(it) }
            }
        }
        return file
    }

    private fun uriFor(sticker: StickerCatalog.Sticker): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.stickers", fileFor(sticker))

    /**
     * Sends a sticker into the field the keyboard is attached to.
     * - `commitContent` (API 25+) is the InputMethodService mechanism Gboard/WhatsApp use, but the app on the
     *   other end must opt in via EditorInfo.contentMimeTypes; most plain text fields do not.
     * - When that's not supported, falls back to Android's share sheet so the user can still send it as a photo.
     * Returns true if the sticker was handed off (either way); false only when the app enabled but rejected it.
     */
    fun send(sticker: StickerCatalog.Sticker, ic: InputConnection, editorInfo: EditorInfo): SendResult {
        val supportsCommitContent = editorInfo.contentMimeTypes?.any { it.equals("image/png", true) || it == "image/*" } == true
        if (supportsCommitContent) {
            val uri = uriFor(sticker)
            context.grantUriPermission(editorInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            } else 0
            val info = InputContentInfo(uri, android.content.ClipDescription("sticker", arrayOf("image/png")))
            val sent = ic.commitContent(info, flags, null)
            if (sent) return SendResult.Sent
        }
        return SendResult.NeedsShareSheet(shareIntent(sticker))
    }

    private fun shareIntent(sticker: StickerCatalog.Sticker): Intent {
        val uri = uriFor(sticker)
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    sealed class SendResult {
        object Sent : SendResult()
        data class NeedsShareSheet(val intent: Intent) : SendResult()
    }
}
