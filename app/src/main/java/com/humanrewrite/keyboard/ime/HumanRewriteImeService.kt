package com.humanrewrite.keyboard.ime

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.ToneGenerator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.text.TextUtils
import android.view.WindowInsets
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.View
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.inputmethodservice.InputMethodService
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.humanrewrite.keyboard.core.ClipboardStore
import com.humanrewrite.keyboard.core.EntitlementStore
import com.humanrewrite.keyboard.core.LlamaEngine
import com.humanrewrite.keyboard.core.LocalModelRewriteGateway
import com.humanrewrite.keyboard.core.ModelStore
import com.humanrewrite.keyboard.core.Palette
import com.humanrewrite.keyboard.core.RewriteCoordinator
import com.humanrewrite.keyboard.core.RewriteResult
import com.humanrewrite.keyboard.core.SettingsStore
import com.humanrewrite.keyboard.core.Template
import com.humanrewrite.keyboard.ui.MainActivity
import java.util.concurrent.Executors

class HumanRewriteImeService : InputMethodService() {
    private lateinit var root: LinearLayout
    private lateinit var settingsStore: SettingsStore
    private lateinit var entitlementStore: EntitlementStore
    private lateinit var clipboardStore: ClipboardStore
    private lateinit var rewriteCoordinator: RewriteCoordinator
    private val executor = Executors.newSingleThreadExecutor()
    // Separate thread so the hidden model warm-up never delays keystrokes or the Rewrite button.
    private val warmupExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "quietype-warmup") }
    private val warmupPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var keyboardMode = KeyboardMode.LETTERS
    private var shiftEnabled = false
    private var lastKeyWasSpace = false
    private val emojiMixParts = mutableListOf<String>()
    private var emojiCategory: EmojiCatalog.Category? = null
    private var stickersSelected = false
    private lateinit var stickerStore: StickerStore
    private val stickerBitmaps = mutableMapOf<String, Bitmap>()
    private var rewriteButton: Button? = null
    private var rewritePulse: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        Palette.apply(this, settingsStore.themeMode)
        themeListener = settingsStore.addThemeListener { refreshTheme() }
        entitlementStore = EntitlementStore(this)
        clipboardStore = ClipboardStore(this)
        stickerStore = StickerStore(this)
        rewriteCoordinator = RewriteCoordinator(
            entitlementStore,
            settingsStore,
            LocalModelRewriteGateway(settingsStore, ModelStore(this))
        )
        warmUpModel()
    }

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(10))
            setBackgroundColor(Palette.kbBg)
            // Android 15+ lays the keyboard behind the navigation bar, where the system draws its own
            // hide-keyboard and switch-keyboard buttons. Without this pad they cover ?123 and emoji.
            setOnApplyWindowInsetsListener { view, insets ->
                val navBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                view.setPadding(dp(6), dp(6), dp(6), dp(10) + navBottom)
                insets
            }
        }
        showKeyboard()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshTheme()
        mainHandler.removeCallbacks(idleUnload)
        warmUpModel()
    }

    // Runs when the app's Theme setting changes and when the phone flips light/dark (day/night) — the
    // open keyboard repaints right away instead of waiting for the next field.
    private var themeListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun refreshTheme() {
        if (Palette.apply(this, settingsStore.themeMode) && ::root.isInitialized) {
            root.setBackgroundColor(Palette.kbBg)
            showKeyboard()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshTheme()
    }

    // The model holds ~1 GB of RAM; free it after a quiet stretch (or when Android is short on
    // memory) and let the next field-open warm it back up in the background.
    private val idleUnload = Runnable { warmupExecutor.execute { LlamaEngine.release() } }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        mainHandler.removeCallbacks(idleUnload)
        mainHandler.postDelayed(idleUnload, IDLE_UNLOAD_MS)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) idleUnload.run()
    }

    private fun warmUpModel() {
        if (!warmupPending.compareAndSet(false, true)) return
        warmupExecutor.execute {
            try { rewriteCoordinator.warmUp() } finally { warmupPending.set(false) }
        }
    }

    override fun onDestroy() {
        // execute() after shutdown() would throw (shutdown() stops accepting new tasks) — release
        // the model first, then shut the executor down.
        executor.execute { LlamaEngine.release() }
        executor.shutdown()
        warmupExecutor.shutdown()
        themeListener?.let { settingsStore.removeListener(it) }
        toneGenerator?.release()
        super.onDestroy()
    }

    private fun showKeyboard() {
        root.removeAllViews()
        root.addView(toolbar())
        when (keyboardMode) {
            KeyboardMode.LETTERS -> showLetterRows()
            KeyboardMode.SYMBOLS -> showSymbolRows()
            KeyboardMode.EMOJI -> showEmojiRows()
            KeyboardMode.MIXER -> showEmojiMixer()
        }
    }

    private fun toolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            addView(toolButton("Rewrite", 1.1f) { rewriteActiveText() }.also { rewriteButton = it })
            addView(toolButton(settingsStore.rewriteMode.title, 1.5f) { cycleRewriteMode() })
            addView(toolButton("Clipboard", 1.2f) { showClipboardPanel() })
            addView(toolButton("Templates", 1.2f) { showTemplatesPanel() })
            addView(toolButton("⚙", 0.6f) { openSettings() })
            addView(toolButton("⌄", 0.6f) { requestHideSelf(0) })
        }
    }


    // Plain Buttons carry a 48dp min height and inset padding, which clips text in a short keyboard strip.
    private fun compactButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            setAllCaps(false)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Palette.ink)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(4), 0, dp(4), 0)
            stateListAnimator = null
            background = keyBackground("shift")
            setOnClickListener { action() }
        }
    }

    private fun showLetterRows() {
        if (settingsStore.numberRowEnabled) {
            root.addView(keyRow("1234567890".map { it.toString() }))
        }
        root.addView(keyRow("qwertyuiop".map { it.toString() }))
        root.addView(keyRow("asdfghjkl".map { it.toString() }, sidePaddingWeight = 0.5f))
        root.addView(keyRow(listOf("shift") + "zxcvbnm".map { it.toString() } + "back"))
        root.addView(keyRow(listOf("?123", "emoji", ",", "space", ".", "enter"), weightSpace = true))
    }

    private fun showSymbolRows() {
        root.addView(keyRow("1234567890".map { it.toString() }))
        root.addView(keyRow(listOf("@", "#", "$", "_", "&", "-", "+", "(", ")"), sidePaddingWeight = 0.5f))
        root.addView(keyRow(listOf("%", "/", "*", "'", "\"", ":", ";", "!", "?", "back")))
        root.addView(keyRow(listOf("ABC", "emoji", ",", "space", ".", "enter"), weightSpace = true))
    }

    private fun showEmojiRows() {
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val grid = GridView(this).apply {
            numColumns = 8
            verticalSpacing = dp(2)
            setPadding(dp(2), 0, dp(2), 0)
        }
        fun showEmoji(category: EmojiCatalog.Category?) {
            stickersSelected = false
            emojiCategory = category
            val list = category?.let(EmojiCatalog::emojiFor) ?: settingsStore.recentEmoji
            grid.adapter = emojiAdapter(if (list.isEmpty()) listOf("🕘") else list, enabled = list.isNotEmpty())
            val active = category?.let { EmojiCatalog.categories.indexOf(it) + 1 } ?: 0
            for (i in 0 until tabs.childCount) tabs.getChildAt(i).alpha = if (i == active) 1f else 0.45f
        }
        fun showStickers() {
            stickersSelected = true
            grid.adapter = stickerAdapter()
            for (i in 0 until tabs.childCount) tabs.getChildAt(i).alpha = if (i == tabs.childCount - 1) 1f else 0.45f
        }
        tabs.addView(toolButton("🕘", 1f) { showEmoji(null) })
        EmojiCatalog.categories.forEach { category -> tabs.addView(toolButton(category.icon, 1f) { showEmoji(category) }) }
        tabs.addView(toolButton("🖼", 1f) { showStickers() })
        root.addView(tabs)
        root.addView(grid, LinearLayout.LayoutParams(-1, dp(190)))
        if (stickersSelected) showStickers() else showEmoji(emojiCategory ?: EmojiCatalog.categories.first().takeIf { settingsStore.recentEmoji.isEmpty() })
        root.addView(keyRow(listOf("ABC", "mix", "space", "back", "enter"), weightSpace = true))
    }

    // Stickers: Twemoji PNGs bundled in assets/stickers (CC-BY 4.0, see StickerCatalog).
    private fun stickerAdapter() = object : BaseAdapter() {
        override fun getCount() = StickerCatalog.stickers.size
        override fun getItem(position: Int) = StickerCatalog.stickers[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val sticker = StickerCatalog.stickers[position]
            val view = (convertView as? ImageView) ?: ImageView(this@HumanRewriteImeService).apply {
                layoutParams = AbsListView.LayoutParams(-1, dp(44))
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            view.setImageBitmap(stickerBitmaps.getOrPut(sticker.codePoint) {
                assets.open(StickerCatalog.assetPath(sticker)).use { BitmapFactory.decodeStream(it) }
            })
            view.contentDescription = sticker.label
            view.setOnClickListener { sendSticker(sticker) }
            return view
        }
    }

    private fun sendSticker(sticker: StickerCatalog.Sticker) {
        val ic = currentInputConnection ?: return toast("No active text field")
        val editorInfo = currentInputEditorInfo ?: return toast("No active text field")
        feedback()
        when (val result = stickerStore.send(sticker, ic, editorInfo)) {
            StickerStore.SendResult.Sent -> toast("Sticker sent")
            is StickerStore.SendResult.NeedsShareSheet -> {
                // Most text fields (SMS, chat apps without image support) can't receive an inline sticker,
                // so hand it to Android's share sheet instead of silently failing.
                toast("This app can't take stickers inline; choose where to send it")
                startActivity(result.intent)
            }
        }
    }

    private fun emojiAdapter(list: List<String>, enabled: Boolean) = object : BaseAdapter() {
        override fun getCount() = list.size
        override fun getItem(position: Int) = list[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? TextView) ?: TextView(this@HumanRewriteImeService).apply {
                textSize = 26f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setBackgroundResource(android.R.drawable.list_selector_background)
                layoutParams = AbsListView.LayoutParams(-1, dp(44))
            }
            view.text = list[position]
            view.alpha = if (enabled) 1f else 0.35f
            view.setOnClickListener {
                if (!enabled) return@setOnClickListener
                feedback()
                val emoji = list[position]
                lastKeyWasSpace = false
                currentInputConnection?.commitText(emoji, 1)
                settingsStore.recentEmoji = listOf(emoji) + settingsStore.recentEmoji
            }
            return view
        }
    }

    private fun showEmojiMixer() {
        root.addView(mixerPreview())
        root.addView(keyRow(listOf("😀", "😂", "😍", "😎", "🤝", "🙏", "🔥", "✨")))
        root.addView(keyRow(listOf("❤️", "💔", "💯", "✅", "⭐", "🎯", "🚀", "🎉")))
        root.addView(keyRow(listOf("→", "+", "×", "/", "|", "•", "~", "_")))
        root.addView(keyRow(listOf("(ง'̀-'́)ง", "¯\\_(ツ)_/¯", "༼ つ ◕_◕ ༽つ")))
        root.addView(keyRow(listOf("ABC", "clearMix", "saveMix", "pasteMix", "back"), weightSpace = true))
    }

    private fun mixerPreview(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 0, 8, 6)
            addView(TextView(this@HumanRewriteImeService).apply {
                text = if (emojiMixParts.isEmpty()) "Emoji Mix: tap items to combine" else "Emoji Mix: ${emojiMixParts.joinToString("")}"
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
    }

    private fun keyRow(labels: List<String>, weightSpace: Boolean = false, sidePaddingWeight: Float = 0f): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            if (sidePaddingWeight > 0f) {
                addView(View(this@HumanRewriteImeService), LinearLayout.LayoutParams(0, dp(48), sidePaddingWeight))
            }
            labels.forEach { label ->
                val weight = when {
                    weightSpace && label == "space" -> 4.8f
                    label == "enter" -> 1.25f
                    label == "back" -> 1.22f
                    label == "shift" -> 1.22f
                    label == "emoji" || label == "mix" -> 1.05f
                    label == "clearMix" || label == "saveMix" || label == "pasteMix" -> 1.6f
                    label == "?123" || label == "ABC" -> 1.25f
                    label.length > 4 -> 2f
                    else -> 1f
                }
                val params = LinearLayout.LayoutParams(0, dp(48), weight).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                addView(keyButton(label), params)
            }
            if (sidePaddingWeight > 0f) {
                addView(View(this@HumanRewriteImeService), LinearLayout.LayoutParams(0, dp(48), sidePaddingWeight))
            }
        }
    }

    private fun keyButton(label: String): TextView {
        return TextView(this).apply {
            text = when (label) {
                "space" -> "English"
                "back" -> "⌫"
                "enter" -> "↵"
                "shift" -> if (shiftEnabled) "⇧" else "↑"
                "emoji" -> "☺"
                "mix" -> "Mix"
                "clearMix" -> "Clear"
                "saveMix" -> "Save"
                "pasteMix" -> "Paste"
                else -> label
            }
            gravity = Gravity.CENTER
            textSize = when {
                label == "space" -> 13f
                isFunctionKey(label) -> 15f
                label.length > 4 -> 12f
                else -> 18f
            }
            setTextColor(Palette.ink)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            includeFontPadding = false
            background = keyBackground(label)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                feedback()
                val ic = currentInputConnection ?: return@setOnClickListener
                when (label) {
                    "space" -> handleSpace(ic)
                    "back" -> {
                        lastKeyWasSpace = false
                        // A selection is deleted as a whole; otherwise remove one code point so an
                        // emoji (a surrogate pair) doesn't leave half a character behind.
                        if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingTextInCodePoints(1, 0)
                        else ic.commitText("", 1)
                    }
                    "enter" -> {
                        lastKeyWasSpace = false
                        val action = currentInputEditorInfo?.let {
                            if (it.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) EditorInfo.IME_ACTION_NONE
                            else it.imeOptions and EditorInfo.IME_MASK_ACTION
                        } ?: EditorInfo.IME_ACTION_NONE
                        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                            // Send / Go / Search / Done: let the app handle it like any other keyboard would.
                            ic.performEditorAction(action)
                        } else {
                            shiftEnabled = true
                            ic.commitText("\n", 1)
                            showKeyboard()
                        }
                    }
                    "shift" -> {
                        shiftEnabled = !shiftEnabled
                        showKeyboard()
                    }
                    "?123" -> {
                        keyboardMode = KeyboardMode.SYMBOLS
                        showKeyboard()
                    }
                    "ABC" -> {
                        keyboardMode = KeyboardMode.LETTERS
                        showKeyboard()
                    }
                    "emoji" -> {
                        keyboardMode = KeyboardMode.EMOJI
                        showKeyboard()
                    }
                    "mix" -> {
                        keyboardMode = KeyboardMode.MIXER
                        showKeyboard()
                    }
                    "clearMix" -> {
                        emojiMixParts.clear()
                        showKeyboard()
                    }
                    "saveMix" -> {
                        val mix = emojiMixParts.joinToString("")
                        if (mix.isBlank()) {
                            toast("Add emojis or symbols first")
                        } else if (!entitlementStore.isPaidFeatureActive()) {
                            lockedFeatureToast("emoji mix saving")
                        } else {
                            clipboardStore.saveClip(mix, pinned = true)
                            toast("Emoji mix saved")
                        }
                    }
                    "pasteMix" -> {
                        val mix = emojiMixParts.joinToString("")
                        lastKeyWasSpace = false
                        if (mix.isBlank()) toast("Add emojis or symbols first") else ic.commitText(mix, 1)
                    }
                    else -> {
                        lastKeyWasSpace = false
                        if (keyboardMode == KeyboardMode.MIXER) {
                            emojiMixParts.add(label)
                            showKeyboard()
                        } else {
                            val output = if (keyboardMode == KeyboardMode.LETTERS && shiftEnabled) label.uppercase() else label
                            ic.commitText(output, 1)
                            if (label in listOf(".", "!", "?")) {
                                shiftEnabled = true
                                showKeyboard()
                            } else if (shiftEnabled && keyboardMode == KeyboardMode.LETTERS) {
                                shiftEnabled = false
                                showKeyboard()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun keyBackground(label: String): GradientDrawable {
        val functionKey = isFunctionKey(label)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(if (functionKey) Palette.fnKey else Palette.key)
            setStroke(dp(1), if (functionKey) Palette.fnKeyStroke else Palette.keyStroke)
        }
    }

    private fun isFunctionKey(label: String): Boolean {
        return label in setOf(
            "shift",
            "back",
            "enter",
            "?123",
            "ABC",
            "emoji",
            "mix",
            "clearMix",
            "saveMix",
            "pasteMix"
        )
    }

    private fun handleSpace(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(3, 0)?.toString().orEmpty()
        if (lastKeyWasSpace && before.endsWith(" ")) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            shiftEnabled = true
            lastKeyWasSpace = false
            showKeyboard()
            return
        }
        ic.commitText(" ", 1)
        lastKeyWasSpace = true
    }

    // Lazily created and reused rather than one-per-keypress; released in onDestroy().
    private val toneGenerator by lazy { runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 45) }.getOrNull() }

    private fun feedback() {
        if (settingsStore.hapticsEnabled) {
            // FLAG_IGNORE_GLOBAL_SETTING makes our own toggle authoritative — without it, this is
            // silently gated by the phone's ambient "vibrate on touch" setting, which is off on
            // many devices, making the toggle look broken even though this line ran every time.
            root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
        if (settingsStore.keySoundEnabled) {
            // AudioManager.playSoundEffect() is silently gated by the phone's "Touch sounds"
            // system setting (off by default on many devices/emulators) — same problem as above.
            // ToneGenerator isn't gated by that setting, so our toggle stays authoritative here too.
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 20)
        }
    }

    private fun toolButton(label: String, weight: Float = 1f, action: () -> Unit): Button {
        return compactButton(label, action).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, dp(38), weight).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
        }
    }

    private fun cycleRewriteMode() {
        settingsStore.rewriteMode = settingsStore.rewriteMode.next()
        showKeyboard()
    }

    private fun isPasswordField(): Boolean {
        val type = currentInputEditorInfo?.inputType ?: return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    // Password fields and incognito fields (IME_FLAG_NO_PERSONALIZED_LEARNING) never feed rewrite or the clipboard.
    private fun isPrivateField(): Boolean =
        isPasswordField() ||
            ((currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    private fun rewriteActiveText() {
        if (isPrivateField()) return toast("Rewrite is off in password and private fields")
        val ic = currentInputConnection ?: return toast("No active text field")
        val target = captureRewriteTarget(ic) ?: return toast("Type or select text to rewrite")
        startRewritePulse()
        executor.execute {
            val result = rewriteCoordinator.rewrite(target.text)
            mainHandler.post {
                stopRewritePulse()
                // The user may have typed, switched fields or dismissed the keyboard while the model ran.
                val live = currentInputConnection
                val stillSame = live != null && live === ic && fieldStillMatches(ic, target)
                if (result is RewriteResult.Success && result.text != target.text && stillSame) {
                    replaceTarget(ic, target, result.text)
                    return@post
                }
                if (stillSame) restoreSelection(ic, target)
                when (result) {
                    is RewriteResult.Success ->
                        toast(if (result.text == target.text) "Already looks good" else "The text changed while rewriting. Tap Rewrite again.")
                    is RewriteResult.Locked -> toast(result.reason)
                    is RewriteResult.NeedsCloudConsent -> toast(result.reason)
                    is RewriteResult.Failed -> toast(result.reason)
                }
            }
        }
    }

    private fun fieldStillMatches(ic: InputConnection, target: RewriteTarget): Boolean {
        if (target.selection) return ic.getSelectedText(0)?.toString()?.trim() == target.text
        val before = ic.getTextBeforeCursor(MAX_REWRITE_CHARS, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_REWRITE_CHARS, 0)?.toString().orEmpty()
        return (before + after).trim() == target.text
    }

    // Put the caret/selection back where the user had it, so a failed or skipped rewrite doesn't
    // leave the whole field selected (the next keystroke would replace all of it).
    private fun restoreSelection(ic: InputConnection, target: RewriteTarget) {
        if (target.selectedAll && target.originalStart >= 0) ic.setSelection(target.originalStart, target.originalEnd)
    }

    // Read-only peek at the selection or the text around the caret; unlike captureRewriteTarget it
    // never changes the field's selection.
    private fun peekFieldText(ic: InputConnection): String {
        ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val before = ic.getTextBeforeCursor(MAX_REWRITE_CHARS, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_REWRITE_CHARS, 0)?.toString().orEmpty()
        return (before + after).trim()
    }

    // Replaces the old "Rewriting…" toast with a visible pulse on the button itself, since a toast
    // disappears before slower on-device generations finish and looks like nothing is happening.
    private fun startRewritePulse() {
        val button = rewriteButton ?: return
        button.isEnabled = false
        button.text = "Rewriting…"
        rewritePulse?.cancel()
        rewritePulse = ObjectAnimator.ofFloat(button, View.ALPHA, 1f, 0.35f).apply {
            duration = 450
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopRewritePulse() {
        rewritePulse?.cancel()
        rewritePulse = null
        rewriteButton?.apply {
            alpha = 1f
            isEnabled = true
            text = "Rewrite"
        }
    }

    private fun captureRewriteTarget(ic: InputConnection): RewriteTarget? {
        ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let {
            return RewriteTarget(it.trim(), selection = true)
        }

        // No selection: select the whole field the same way the user's own "Select all" would, so
        // replacing it is one commitText-over-selection call. Some apps (WhatsApp among them) cap or
        // silently ignore a large deleteSurroundingText, which left the old text sitting right next
        // to the rewritten text instead of being replaced by it.
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val originalStart = extracted?.let { it.startOffset + it.selectionStart } ?: -1
        val originalEnd = extracted?.let { it.startOffset + it.selectionEnd } ?: -1
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let {
            return RewriteTarget(it.trim(), selection = true, selectedAll = true, originalStart = originalStart, originalEnd = originalEnd)
        }
        // Select-all found nothing to rewrite: undo it before falling through.
        if (originalStart >= 0) ic.setSelection(originalStart, originalEnd)

        // Fallback only for InputConnections that don't implement select-all.
        val before = ic.getTextBeforeCursor(MAX_REWRITE_CHARS, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_REWRITE_CHARS, 0)?.toString().orEmpty()
        val message = before + after
        val text = message.trim()
        if (text.isBlank()) return null
        return RewriteTarget(
            text,
            selection = false,
            beforeLength = before.length,
            afterLength = after.length,
            leading = message.takeWhile { it.isWhitespace() },
            trailing = message.takeLastWhile { it.isWhitespace() }
        )
    }

    private fun replaceTarget(ic: InputConnection, target: RewriteTarget, rewritten: String) {
        if (target.selection) {
            ic.commitText(rewritten, 1)
        } else {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(target.beforeLength, target.afterLength)
            // The text sent for rewriting was trimmed; put the surrounding whitespace back.
            ic.commitText(target.leading + rewritten + target.trailing, 1)
            ic.endBatchEdit()
        }
        toast("Rewritten")
    }

    private fun showClipboardPanel() {
        if (!entitlementStore.isPaidFeatureActive()) {
            lockedFeatureToast("power clipboard")
            return
        }
        root.removeAllViews()
        root.addView(panelHeader("Clipboard"))

        root.addView(panelButton("Save current text") {
            val ic = currentInputConnection
            val saved = if (ic == null || isPrivateField()) null else clipboardStore.saveClip(peekFieldText(ic))
            toast(if (saved == null) "Nothing to save" else "Clip saved")
            showClipboardPanel()
        })

        val searchInput = panelInput("Search clips")
        root.addView(searchInput)
        root.addView(panelButton("Search") {
            showClipboardPanel(searchInput.text.toString())
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val clips = clipboardStore.clips().take(20)
        if (clips.isEmpty()) list.addView(panelEmptyState("No saved clips yet"))
        clips.forEach { clip ->
            list.addView(panelCardRow(
                if (clip.pinned) "📌 ${clip.text}" else clip.text,
                onInsert = {
                    currentInputConnection?.commitText(clip.text, 1)
                    showKeyboard()
                },
                icons = listOf(
                    (if (clip.pinned) "📍" else "📌") to {
                        clipboardStore.togglePinned(clip.id)
                        showClipboardPanel()
                    },
                    "↻" to {
                        val ic = currentInputConnection
                        if (ic != null) {
                            val replacement = if (isPrivateField()) "" else peekFieldText(ic)
                            val updated = clipboardStore.updateClip(clip.id, replacement)
                            toast(if (updated == null) "Nothing to update" else "Clip updated")
                            showClipboardPanel()
                        }
                    },
                    "✕" to {
                        showConfirmDelete(clip.text, onConfirm = {
                            clipboardStore.deleteClip(clip.id)
                            toast("Clip deleted")
                            showClipboardPanel()
                        }, onCancel = { showClipboardPanel() })
                    }
                )
            ))
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(200)))
    }

    private fun showClipboardPanel(query: String) {
        root.removeAllViews()
        root.addView(panelHeader("Clipboard"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val results = clipboardStore.searchClips(query).take(20)
        if (results.isEmpty()) list.addView(panelEmptyState("No matching clips"))
        results.forEach { clip ->
            list.addView(panelCardRow(
                if (clip.pinned) "📌 ${clip.text}" else clip.text,
                onInsert = {
                    currentInputConnection?.commitText(clip.text, 1)
                    showKeyboard()
                },
                icons = listOf(
                    (if (clip.pinned) "📍" else "📌") to {
                        clipboardStore.togglePinned(clip.id)
                        showClipboardPanel(query)
                    },
                    "✕" to {
                        showConfirmDelete(clip.text, onConfirm = {
                            clipboardStore.deleteClip(clip.id)
                            toast("Clip deleted")
                            showClipboardPanel(query)
                        }, onCancel = { showClipboardPanel(query) })
                    }
                )
            ))
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(240)))
    }

    private fun showTemplatesPanel() {
        if (!entitlementStore.isPaidFeatureActive()) {
            lockedFeatureToast("templates")
            return
        }
        root.removeAllViews()
        root.addView(panelHeader("Templates", onAdd = { showTemplateComposer() }))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val templates = clipboardStore.templates()
        if (templates.isEmpty()) list.addView(panelEmptyState("No templates yet"))
        templates.forEach { template ->
            list.addView(panelCardRow(
                template.title,
                onInsert = {
                    currentInputConnection?.commitText(template.body, 1)
                    showKeyboard()
                },
                icons = listOf(
                    "✎" to { showTemplateComposer(template) },
                    "✕" to {
                        showConfirmDelete(template.title, onConfirm = {
                            clipboardStore.deleteTemplate(template.id)
                            toast("Template deleted")
                            showTemplatesPanel()
                        }, onCancel = { showTemplatesPanel() })
                    }
                )
            ))
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(230)))
    }

    // Reached via the "+" in the Templates header for a new template, or "✎" on an existing one to
    // edit it in place — same screen either way, only the save action differs. Rewrite here runs on
    // this screen's own text (not the app field behind the keyboard, which is what the main
    // toolbar's Rewrite button targets) — this is where a user drafts a template, so it's the main
    // agenda's real home here too.
    private fun showTemplateComposer(existing: Template? = null) {
        root.removeAllViews()
        root.addView(panelHeader(if (existing == null) "New template" else "Edit template", onBack = { showTemplatesPanel() }))

        val titleInput = panelInput("Template title")
        val bodyInput = panelInput("Template text", minLinesCount = 3)
        if (existing != null) {
            titleInput.setText(existing.title)
            bodyInput.setText(existing.body)
        }
        root.addView(titleInput)
        root.addView(bodyInput)

        var pulse: ValueAnimator? = null
        lateinit var rewriteBtn: Button
        rewriteBtn = panelButton("Rewrite") {
            val draft = bodyInput.text.toString().trim()
            if (draft.isEmpty()) {
                toast("Type something to rewrite first")
                return@panelButton
            }
            rewriteBtn.isEnabled = false
            rewriteBtn.text = "Rewriting…"
            pulse?.cancel()
            pulse = ObjectAnimator.ofFloat(rewriteBtn, View.ALPHA, 1f, 0.35f).apply {
                duration = 450
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
            executor.execute {
                val result = rewriteCoordinator.rewrite(draft)
                mainHandler.post {
                    pulse?.cancel()
                    pulse = null
                    rewriteBtn.alpha = 1f
                    rewriteBtn.isEnabled = true
                    rewriteBtn.text = "Rewrite"
                    when (result) {
                        is RewriteResult.Success -> {
                            bodyInput.setText(result.text)
                            bodyInput.setSelection(result.text.length)
                        }
                        is RewriteResult.Locked -> toast(result.reason)
                        is RewriteResult.NeedsCloudConsent -> toast(result.reason)
                        is RewriteResult.Failed -> toast(result.reason)
                    }
                }
            }
        }
        root.addView(rewriteBtn)

        root.addView(panelButton("Save template") {
            val title = titleInput.text.toString()
            val body = bodyInput.text.toString()
            val saved = if (existing == null) {
                clipboardStore.saveTemplate(title, body)
            } else {
                clipboardStore.updateTemplate(existing.id, title, body)
            }
            if (saved == null) {
                toast("Title and text required")
            } else {
                toast("Template saved")
                showTemplatesPanel()
            }
        })
    }

    private fun panelEmptyState(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(PANEL_MUTED)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(20), dp(12), dp(20))
    }

    // onBack defaults to closing the panel entirely; the delete-confirm screen overrides it to
    // return to the list instead (see showConfirmDelete). onAdd, when given, shows a "+" icon
    // before Back — used by the Templates list to open the create-template screen.
    private fun panelHeader(title: String, onBack: () -> Unit = { showKeyboard() }, onAdd: (() -> Unit)? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(10))
            addView(TextView(this@HumanRewriteImeService).apply {
                text = title
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(PANEL_INK)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (onAdd != null) {
                addView(panelIconButton("+", onAdd).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(6) }
                })
            }
            // Same icon-button component as "+" (not the old mixed icon+text toolButton) so the two
            // line up pixel-for-pixel instead of two differently-shaped controls sitting side by side.
            addView(panelIconButton("←", onBack).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            })
        }
    }

    // One bordered card per clip/template: the text itself (tap = insert) plus small icon actions
    // (pin, replace, delete) on the right — replaces the old separate text-button-plus-pill-row
    // layout with a single clearly-outlined row per item.
    private fun panelCardRow(label: String, onInsert: () -> Unit, icons: List<Pair<String, () -> Unit>>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBordered(Palette.surface, PANEL_LINE, 12)
            setPadding(dp(12), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            addView(TextView(this@HumanRewriteImeService).apply {
                text = label.take(120)
                textSize = 14f
                setTextColor(PANEL_INK)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(10), dp(8), dp(10))
                isClickable = true
                isFocusable = true
                foreground = RippleDrawable(ColorStateList.valueOf(ACCENT_SOFT), null, null)
                setOnClickListener { onInsert() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            icons.forEach { (icon, action) -> addView(panelIconButton(icon, action)) }
        }
    }

    // Small square icon button (pin/replace/delete) sized for a card row's trailing edge. The "✕"
    // gets a muted-red treatment so delete reads as distinct from the neutral teal actions.
    private fun panelIconButton(icon: String, action: () -> Unit): TextView {
        val danger = icon == "✕"
        val size = dp(30)
        return TextView(this).apply {
            text = icon
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (danger) PANEL_DANGER else ACCENT)
            background = rounded(if (danger) PANEL_DANGER_SOFT else ACCENT_SOFT, 8)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(4) }
        }
    }

    // Secondary actions (Save, Search, Pin, Delete, Replace) — soft teal pill, one shared style
    // since none of these outrank each other enough in this compact panel to need a second tier.
    private fun panelButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 13f
            setTextColor(ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(ACCENT_SOFT, 10)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) }
        }
    }

    private fun panelInput(hintText: String, minLinesCount: Int = 1): EditText {
        return EditText(this).apply {
            hint = hintText
            textSize = 14f
            setHintTextColor(PANEL_MUTED)
            setTextColor(PANEL_INK)
            background = rounded(PANEL_INPUT_BG, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minLines = minLinesCount
            if (minLinesCount <= 1) setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        }
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun roundedBordered(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dangerButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setAllCaps(false)
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(PANEL_DANGER, 10)
            setOnClickListener { action() }
        }
    }

    // Full-panel "are you sure" step shown before any delete actually happens, in-keyboard (no
    // system Dialog — a Service window can't show one cleanly) — swaps root's content the same way
    // every other panel does, then either deletes-and-returns or just returns on Cancel.
    private fun showConfirmDelete(itemPreview: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
        root.removeAllViews()
        root.addView(panelHeader("Delete?", onBack = onCancel))
        root.addView(TextView(this).apply {
            text = itemPreview.take(140)
            textSize = 14f
            setTextColor(PANEL_MUTED)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            background = roundedBordered(Palette.surface, PANEL_LINE, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(panelButton("Cancel", onCancel).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) }
            })
            addView(dangerButton("Delete", onConfirm).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) }
            })
        })
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // Same "subscription required" fact, but a longer, more specific toast when the local
    // entitlement cache has never been verified — that's what a subscribed user sees right after
    // Android's "Clear data" wipes it, not just a genuinely never-subscribed user. See
    // EntitlementStore.looksNeverVerified() for why this can't just check silently and skip the message.
    private fun lockedFeatureToast(featureLabel: String) {
        if (entitlementStore.looksNeverVerified()) {
            Toast.makeText(this, "Subscription required for $featureLabel. Already subscribed? Open Quietype once to restore it.", Toast.LENGTH_LONG).show()
        } else {
            toast("Subscription required for $featureLabel")
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class RewriteTarget(
        val text: String,
        val selection: Boolean,
        val beforeLength: Int = 0,
        val afterLength: Int = 0,
        val leading: String = "",
        val trailing: String = "",
        val selectedAll: Boolean = false,
        val originalStart: Int = -1,
        val originalEnd: Int = -1
    )

    private companion object {
        const val IDLE_UNLOAD_MS = 5L * 60L * 1000L
        // Keeps prompt plus answer inside the model's 2048-token context.
        const val MAX_REWRITE_CHARS = 1000

        // Matches MainActivity's palette exactly, so the Clipboard/Templates panels (built in this
        // file, a separate rendering surface from the app's own Activity) read as the same product.
        val ACCENT get() = Palette.accent
        val ACCENT_SOFT get() = Palette.accentSoft
        val PANEL_INK get() = Palette.ink
        val PANEL_MUTED get() = Palette.muted
        val PANEL_INPUT_BG get() = Palette.bg
        val PANEL_LINE get() = Palette.panelLine
        val PANEL_DANGER get() = Palette.danger
        val PANEL_DANGER_SOFT get() = Palette.dangerSoft
    }

    private enum class KeyboardMode {
        LETTERS,
        SYMBOLS,
        EMOJI,
        MIXER
    }
}
