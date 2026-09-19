package com.humanrewrite.keyboard.core

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

sealed class ModelState {
    object NotDownloaded : ModelState()
    data class Downloading(val percent: Int) : ModelState()
    object Ready : ModelState()
}

/** Downloads rewrite models with Android's DownloadManager into app storage (deleted on uninstall). */
class ModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("humanrewrite_models", Context.MODE_PRIVATE)
    private val downloads = appContext.getSystemService(DownloadManager::class.java)
    private val dir = File(appContext.getExternalFilesDir(null), MODELS_DIR)

    fun file(option: LocalModelOption) = File(dir, option.fileName)

    // Downloads land under a .part name, so a half-finished file is never loaded as a model.
    private fun partFile(option: LocalModelOption) = File(dir, option.fileName + ".part")

    fun state(option: LocalModelOption): ModelState {
        if (file(option).exists()) return ModelState.Ready
        val id = prefs.getLong(option.name, -1L)
        if (id < 0) return ModelState.NotDownloaded

        downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        // The file goes straight into a native parser, so it is only marked Ready
                        // after its SHA-256 matches. Hashing ~800 MB is slow: do it off the main thread
                        // and keep reporting 100% until it finishes.
                        if (verifying.add(option.name)) {
                            Thread {
                                verifyAndPromote(option, total)
                                verifying.remove(option.name)
                            }.start()
                        }
                        return ModelState.Downloading(100)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        DiagnosticLog.log("Download failed: ${option.name} (DownloadManager reason=$reason)")
                    }
                    else -> return ModelState.Downloading(if (total > 0) (done * 100 / total).toInt() else 0)
                }
            }
        }
        prefs.edit().remove(option.name).apply()
        partFile(option).delete()
        return ModelState.NotDownloaded
    }

    fun download(option: LocalModelOption) {
        dir.mkdirs()
        partFile(option).delete()
        val request = DownloadManager.Request(Uri.parse(option.url))
            .setTitle("Quietype ${option.title} model")
            .setDestinationInExternalFilesDir(appContext, null, "$MODELS_DIR/${option.fileName}.part")
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        prefs.edit().putLong(option.name, downloads.enqueue(request)).apply()
    }

    private fun verifyAndPromote(option: LocalModelOption, expectedBytes: Long) {
        val part = partFile(option)
        val ok = part.exists() && (expectedBytes <= 0 || part.length() == expectedBytes) && sha256(part) == option.sha256
        if (ok && part.renameTo(file(option))) {
            prefs.edit().remove(option.name).apply()
        } else {
            DiagnosticLog.log("Download rejected: ${option.name} failed integrity check")
            part.delete()
            prefs.edit().remove(option.name).apply()
        }
    }

    private fun sha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private companion object {
        // Models whose finished download is being hashed right now (shared across ModelStore instances).
        val verifying: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        const val MODELS_DIR = "models"
    }
}
