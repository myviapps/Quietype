package com.humanrewrite.keyboard.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ClipboardStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("humanrewrite_clipboard", Context.MODE_PRIVATE)
    private val secureTextStore = SecureTextStore()
    private val settingsStore = SettingsStore(appContext)

    init { seedDefaultTemplatesOnce() }

    // Runs once per install (guarded by KEY_SEEDED_DEFAULTS), not on every launch — a user who
    // deletes all the starter templates shouldn't see them reappear. They're stored as ordinary
    // templates afterward, so they're editable/deletable exactly like anything the user creates.
    private fun seedDefaultTemplatesOnce() {
        if (prefs.getBoolean(KEY_SEEDED_DEFAULTS, false)) return
        prefs.edit().putBoolean(KEY_SEEDED_DEFAULTS, true).apply()
        if (templates().isNotEmpty()) return
        val now = System.currentTimeMillis()
        val defaults = DEFAULT_TEMPLATES.mapIndexed { index, (title, body) ->
            Template(id = UUID.randomUUID().toString(), title = title, body = body, createdAtMillis = now + index, updatedAtMillis = now + index)
        }
        writeArray(KEY_TEMPLATES, defaults.map { it.toJson() })
    }

    // Filtered by retain() on every read, not just on write — so lowering the retention setting
    // hides newly-stale clips right away instead of waiting for the next save to prune them.
    fun clips(): List<Clip> = retain(readArray(KEY_CLIPS).mapNotNull { it.toClip() })

    fun templates(): List<Template> = readArray(KEY_TEMPLATES).mapNotNull { it.toTemplate() }

    fun saveClip(text: String, pinned: Boolean = false): Clip? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val now = System.currentTimeMillis()
        val clip = Clip(
            id = UUID.randomUUID().toString(),
            text = clean,
            createdAtMillis = now,
            updatedAtMillis = now,
            pinned = pinned,
            type = "manual"
        )
        val next = retain(clips().filterNot { it.text == clean } + clip)
        writeArray(KEY_CLIPS, next.map { it.toJson() })
        return clip
    }

    fun saveTemplate(title: String, body: String): Template? {
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        if (cleanTitle.isEmpty() || cleanBody.isEmpty()) return null
        val now = System.currentTimeMillis()
        val template = Template(
            id = UUID.randomUUID().toString(),
            title = cleanTitle,
            body = cleanBody,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        writeArray(KEY_TEMPLATES, templates().filterNot { it.title == cleanTitle }.map { it.toJson() } + template.toJson())
        return template
    }

    fun updateTemplate(id: String, title: String, body: String): Template? {
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        if (cleanTitle.isEmpty() || cleanBody.isEmpty()) return null
        var updated: Template? = null
        val now = System.currentTimeMillis()
        val next = templates().map {
            if (it.id == id) {
                it.copy(title = cleanTitle, body = cleanBody, updatedAtMillis = now).also { template -> updated = template }
            } else {
                it
            }
        }
        writeArray(KEY_TEMPLATES, next.map { it.toJson() })
        return updated
    }

    fun deleteClip(id: String) {
        writeArray(KEY_CLIPS, clips().filterNot { it.id == id }.map { it.toJson() })
    }

    fun updateClip(id: String, text: String): Clip? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        var updated: Clip? = null
        val now = System.currentTimeMillis()
        val next = clips().map {
            if (it.id == id) {
                it.copy(text = clean, updatedAtMillis = now).also { clip -> updated = clip }
            } else {
                it
            }
        }
        writeArray(KEY_CLIPS, retain(next).map { it.toJson() })
        return updated
    }

    fun togglePinned(id: String) {
        val now = System.currentTimeMillis()
        writeArray(
            KEY_CLIPS,
            clips().map {
                if (it.id == id) it.copy(pinned = !it.pinned, updatedAtMillis = now) else it
            }.map { it.toJson() }
        )
    }

    fun deleteTemplate(id: String) {
        writeArray(KEY_TEMPLATES, templates().filterNot { it.id == id }.map { it.toJson() })
    }

    fun searchClips(query: String): List<Clip> {
        val clean = query.trim()
        if (clean.isEmpty()) return clips()
        return clips().filter { it.text.contains(clean, ignoreCase = true) }
    }

    private fun retain(input: List<Clip>): List<Clip> {
        val retentionMillis = settingsStore.clipRetentionDays.toLong() * 24L * 60L * 60L * 1000L
        val cutoff = System.currentTimeMillis() - retentionMillis
        val pinned = input.filter { it.pinned }
        val unpinned = input
            .filter { !it.pinned && it.createdAtMillis >= cutoff }
            .sortedByDescending { it.createdAtMillis }
            .take(MAX_UNPINNED)
        return (pinned + unpinned).sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.updatedAtMillis })
    }

    private fun readArray(key: String): List<JSONObject> {
        val encrypted = prefs.getString(key, null) ?: return emptyList()
        val raw = runCatching { secureTextStore.decrypt(encrypted) }
            .recoverCatching { secureTextStore.decrypt(encrypted) } // one retry: Keystore can fail transiently
            .getOrElse {
                // Still undecryptable: park the blob under a backup key instead of letting the next
                // write silently destroy it, then start from empty.
                prefs.edit().putString(key + BACKUP_SUFFIX, encrypted).apply()
                return emptyList()
            }
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun writeArray(key: String, objects: List<JSONObject>) {
        val array = JSONArray()
        objects.forEach { array.put(it) }
        prefs.edit().putString(key, secureTextStore.encrypt(array.toString())).apply()
    }

    private fun Clip.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("createdAtMillis", createdAtMillis)
        .put("updatedAtMillis", updatedAtMillis)
        .put("pinned", pinned)
        .put("type", type)

    private fun Template.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("body", body)
        .put("createdAtMillis", createdAtMillis)
        .put("updatedAtMillis", updatedAtMillis)

    private fun JSONObject.toClip(): Clip? = runCatching {
        Clip(
            id = getString("id"),
            text = getString("text"),
            createdAtMillis = getLong("createdAtMillis"),
            updatedAtMillis = getLong("updatedAtMillis"),
            pinned = optBoolean("pinned", false),
            type = optString("type", "manual")
        )
    }.getOrNull()

    private fun JSONObject.toTemplate(): Template? = runCatching {
        Template(
            id = getString("id"),
            title = getString("title"),
            body = getString("body"),
            createdAtMillis = getLong("createdAtMillis"),
            updatedAtMillis = getLong("updatedAtMillis")
        )
    }.getOrNull()

    companion object {
        private const val KEY_CLIPS = "clips"
        private const val KEY_TEMPLATES = "templates"
        private const val KEY_SEEDED_DEFAULTS = "seeded_default_templates"
        private const val MAX_UNPINNED = 200
        private const val BACKUP_SUFFIX = "_undecryptable_backup"

        private val DEFAULT_TEMPLATES = listOf(
            "Thank you" to "Thank you so much for your time — I really appreciate it and look forward to staying in touch.",
            "Follow-up" to "Hi, just following up on my previous message — let me know if you've had a chance to look at it.",
            "Out of office" to "Thanks for your message. I'm currently out of office and will get back to you as soon as I can.",
            "Meeting request" to "Would you be available for a quick call this week to discuss this further? Let me know a time that works for you.",
            "Running late" to "Sorry, I'm running a few minutes late — I'll be there shortly. Thanks for your patience."
        )
    }
}
