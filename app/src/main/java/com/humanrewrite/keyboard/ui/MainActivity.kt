package com.humanrewrite.keyboard.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.humanrewrite.keyboard.BuildConfig
import com.humanrewrite.keyboard.R
import com.humanrewrite.keyboard.core.AppMode
import com.humanrewrite.keyboard.core.DiagnosticLog
import com.humanrewrite.keyboard.core.EntitlementRepository
import com.humanrewrite.keyboard.core.EntitlementStore
import com.humanrewrite.keyboard.core.LocalModelOption
import com.humanrewrite.keyboard.core.ModelState
import com.humanrewrite.keyboard.core.ModelStore
import com.humanrewrite.keyboard.core.Palette
import com.humanrewrite.keyboard.core.PlayBillingGateway
import com.humanrewrite.keyboard.core.SettingsStore
import com.humanrewrite.keyboard.core.ThemeMode
import java.util.Date

class MainActivity : Activity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var entitlementStore: EntitlementStore
    private lateinit var modelStore: ModelStore
    private lateinit var modeSelector: LinearLayout
    private lateinit var modeHint: TextView
    private lateinit var tryItHint: TextView
    private lateinit var setupSection: LinearLayout
    private lateinit var setupCard: LinearLayout
    private lateinit var typingSection: LinearLayout
    private lateinit var modelCard: LinearLayout
    private lateinit var bubbleSection: LinearLayout
    private lateinit var bubbleCard: LinearLayout
    private lateinit var planCard: LinearLayout
    private lateinit var planView: TextView
    private lateinit var billingGateway: PlayBillingGateway
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTick = Runnable { refresh() }
    private var onboarding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        Palette.apply(this, settingsStore.themeMode)
        Palette.styleWindow(window)
        entitlementStore = EntitlementStore(this)
        modelStore = ModelStore(this)
        billingGateway = PlayBillingGateway(this, EntitlementRepository(settingsStore, entitlementStore))
        if (settingsStore.hasAcceptedTerms) {
            setContentView(buildContent())
        } else {
            onboarding = true
            setContentView(buildOnboarding())
        }
        if (savedInstanceState == null) showSplash()
    }

    override fun onResume() {
        super.onResume()
        if (onboarding) {
            // Rebuilds so the "✓ Read" checkmarks pick up what LegalActivity just wrote to
            // SettingsStore (returning here always re-triggers onResume).
            setContentView(buildOnboarding())
            return
        }
        refresh()
        // Re-checks with Play (and, through it, our backend) every time the app is opened, so a
        // cancelled or refunded subscription locks paid features on the next open rather than
        // waiting out the full 24-hour offline grace period in EntitlementStore.
        billingGateway.restorePurchases { if (!isDestroyed) refresh() }
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTick)
        super.onPause()
    }

    override fun onDestroy() {
        billingGateway.close()
        super.onDestroy()
    }

    // The keyboard picker is a dialog, so picking a keyboard shows up as a focus change, not onResume.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !onboarding) refresh()
    }

    // Shown once, before the app is usable at all: the user has to open both legal pages (they
    // stay readable in-app, no network needed) and tap "Mark as read" in each before Continue
    // unlocks — read from SettingsStore, set by LegalActivity itself, so it survives leaving and
    // returning to this screen and doesn't rely on Android's back button to "count."
    private fun buildOnboarding(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        column.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(brandMark(36))
            addView(label("Quietype", 28f, INK, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        })
        column.addView(label(
            "Privacy-first grammar rewrite. Your text never leaves your phone.",
            15f, MUTED
        ).apply { setPadding(0, dp(6), 0, dp(28)) })

        column.addView(sectionTitle("Before you start"))
        column.addView(card().apply {
            addView(label("Please read these, then tap \"Mark as read\" at the bottom of each.", 14f, MUTED).apply {
                setPadding(0, dp(2), 0, dp(10))
            })
            addView(onboardingLinkRow("Privacy policy", settingsStore.readPrivacyPolicy) {
                openLegalPage("privacy-policy.html")
            })
            addView(divider())
            addView(onboardingLinkRow("Terms of service", settingsStore.readTermsOfService) {
                openLegalPage("terms-of-service.html")
            })
        })

        val bothViewed = settingsStore.readPrivacyPolicy && settingsStore.readTermsOfService
        column.addView(primaryButton(if (bothViewed) "Agree & continue" else "Mark both as read to continue") {
            if (!bothViewed) return@primaryButton
            settingsStore.hasAcceptedTerms = true
            onboarding = false
            setContentView(buildContent())
            refresh()
        }.apply {
            isEnabled = bothViewed
            alpha = if (bothViewed) 1f else 0.4f
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(20) }
        })

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(column)
            setOnApplyWindowInsetsListener { _, insets ->
                val (top, bottom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                }
                column.setPadding(dp(20), top + dp(20), dp(20), bottom + dp(24))
                insets
            }
        }
    }

    private fun onboardingLinkRow(title: String, viewed: Boolean, onClick: () -> Unit): LinearLayout =
        row(title, null, label(if (viewed) "✓ Read" else "Read →", 14f, if (viewed) ACCENT else MUTED, bold = viewed))
            .apply { setOnClickListener { onClick() } }

    private fun buildContent(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }

        column.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            // The stamp's rotated corners extend past its own unrotated layout box — without
            // disabling clipChildren here (and on `column` above), the parent(s) crop them off.
            clipChildren = false
            addView(brandMark(36))
            addView(label("Quietype", 28f, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            addView(privacyStamp(), LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(10); topMargin = dp(6); bottomMargin = dp(6)
            })
        })
        column.addView(label("Privacy-first grammar rewrite. Your text never leaves your phone.", 15f, MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })

        column.addView(sectionTitle("How you rewrite"))
        modeSelector = LinearLayout(this).apply {
            background = rounded(BG, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        column.addView(modeSelector)
        column.addView(label("", 12f, MUTED).also { modeHint = it }.apply {
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        setupSection = section("Get started") { setupCard = card(); addView(setupCard) }
        column.addView(setupSection)

        column.addView(sectionTitle("Rewrite quality"))
        modelCard = card()
        column.addView(modelCard)

        column.addView(sectionTitle("Try it"))
        column.addView(card().apply {
            addView(label("", 14f, MUTED).also { tryItHint = it }.apply {
                setPadding(0, dp(4), 0, dp(10))
            })
            addView(EditText(this@MainActivity).apply {
                hint = "Tap here and type something"
                minLines = 2
                textSize = 16f
                setTextColor(INK)
                setHintTextColor(MUTED)
                background = rounded(BG, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            })
        })

        column.addView(sectionTitle("Plan"))
        planView = label("", 15f, INK)
        planCard = card().apply { addView(planView) }
        column.addView(planCard)

        typingSection = section("Typing") {
            addView(card().apply {
                addView(switchRow("Number row", "Show 1–0 above the letters", settingsStore.numberRowEnabled) {
                    settingsStore.numberRowEnabled = it
                })
                addView(divider())
                addView(switchRow("Vibrate on keypress", null, settingsStore.hapticsEnabled) {
                    settingsStore.hapticsEnabled = it
                })
                addView(divider())
                addView(switchRow("Sound on keypress", null, settingsStore.keySoundEnabled) {
                    settingsStore.keySoundEnabled = it
                })
            })
        }
        column.addView(typingSection)

        column.addView(sectionTitle("Appearance"))
        column.addView(card().apply {
            addView(row(
                "Theme",
                "System follows your phone's light/dark (or day/night) setting",
                themeSelector()
            ))
        })

        column.addView(sectionTitle("Clipboard"))
        column.addView(card().apply {
            addView(row(
                "Clip retention",
                "How long unpinned clips are kept before they're auto-removed",
                daySelector(settingsStore.clipRetentionDays, SettingsStore.CLIP_RETENTION_DAY_OPTIONS) { days ->
                    settingsStore.clipRetentionDays = days
                    refresh()
                }
            ))
        })

        column.addView(sectionTitle("Privacy"))
        column.addView(card().apply {
            addView(label("🔒  Rewriting happens entirely on your phone.", 15f, INK, bold = true))
            addView(label(
                "Your text is never uploaded, logged, or sent to a server. The only thing that goes online is your Google Play purchase token, to confirm your subscription. " +
                    "A cloud option may return in a future update, off by default and only with your explicit consent.",
                13f, MUTED
            ).apply { setPadding(0, dp(6), 0, 0) })
        })

        bubbleSection = section("Floating bubble") { bubbleCard = card(); addView(bubbleCard) }
        column.addView(bubbleSection)

        column.addView(sectionTitle("Legal & support"))
        column.addView(legalGrid(listOf(
            "Privacy policy" to { openLegalPage("privacy-policy.html") },
            "Terms of service" to { openLegalPage("terms-of-service.html") },
            "Open source licenses" to { openLegalPage("open-source.html") },
            "Contact support" to { openSupportEmail() },
            "Send diagnostic report" to { sendDiagnosticReport() }
        )))

        column.addView(label("English only · Basic typing is always free", 13f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })
        // Required by the CC-BY 4.0 license on the sticker artwork — full attribution list is the
        // Open source licenses link above (see StickerCatalog.kt).
        column.addView(label("Stickers by Twemoji, licensed CC-BY 4.0", 11f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        // "Built with Llama" attribution (required by the Llama 3.2 Community License on the
        // High-quality rewrite model) lives on the Open source licenses page instead of here, per
        // user request not to show it on the main screen — see that page's Llama entry.

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(column)
            // targetSdk 35 draws edge-to-edge, so pad for the status and navigation bars ourselves.
            setOnApplyWindowInsetsListener { _, insets ->
                val (top, bottom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                }
                column.setPadding(dp(20), top + dp(20), dp(20), bottom + dp(24))
                insets
            }
        }
    }

    private fun refresh() {
        handler.removeCallbacks(refreshTick)
        refreshModeSelector()
        refreshModels()
        refreshPlan()
    }

    private fun refreshModeSelector() {
        val mode = settingsStore.appMode

        modeSelector.removeAllViews()
        AppMode.values().forEach { option ->
            val selected = option == mode
            modeSelector.addView(TextView(this).apply {
                text = option.title
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (selected) Color.WHITE else INK)
                background = if (selected) rounded(ACCENT, 10) else null
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { selectMode(option) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }

        modeHint.text = when (mode) {
            AppMode.KEYBOARD_ONLY -> "Rewrite from the Quietype keyboard's toolbar."
            AppMode.SHORTCUT_ONLY -> "Rewrite from the floating bubble in any app — keep using your usual keyboard."
            AppMode.BOTH -> "Rewrite from the keyboard toolbar, or from the floating bubble in any app."
        }
        tryItHint.text = if (mode.needsKeyboard) {
            "Type something with mistakes, then tap Rewrite on the keyboard."
        } else {
            "Type something with mistakes, then use the floating bubble to rewrite it."
        }

        // Only the sections relevant to the chosen mode stay on screen.
        setupSection.visibility = if (mode.needsKeyboard) View.VISIBLE else View.GONE
        typingSection.visibility = if (mode.needsKeyboard) View.VISIBLE else View.GONE
        bubbleSection.visibility = if (mode.needsShortcut) View.VISIBLE else View.GONE
        if (mode.needsKeyboard) refreshSetup()
        if (mode.needsShortcut) refreshBubble()
    }

    private fun selectMode(mode: AppMode) {
        val wasKeyboardEnabled = (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.any { it.packageName == packageName }
        val wasShortcutEnabled = FloatingAssistantService.isEnabled(this)
        settingsStore.appMode = mode

        // Sliding to a mode sends the user straight to grant whatever permission it newly needs,
        // instead of leaving them to find the right button in the sections below.
        when {
            mode.needsKeyboard && !wasKeyboardEnabled -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            mode.needsShortcut && !wasShortcutEnabled -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Choose \"Quietype bubble\" and turn it on")
            }
        }
        refresh()
    }

    private fun refreshPlan() {
        val entitlement = entitlementStore.current()
        val nowMillis = entitlementStore.monotonicNow(System.currentTimeMillis())
        val betaActive = entitlementStore.isBetaActive(nowMillis)
        val betaUsed = entitlementStore.hasUsedBetaThisGeneration()
        val active = entitlement.isPaidFeatureActive(nowMillis)
        val betaDaysLeft = if (betaActive) {
            val remainingMillis = settingsStore.betaOptInAtMillis + EntitlementStore.BETA_DURATION_MILLIS - nowMillis
            // Ceiling so the last partial day still reads as "1 day left," not "0."
            ((remainingMillis + 86_399_999L) / 86_400_000L).coerceAtLeast(1L)
        } else 0L
        planView.text = when {
            BuildConfig.DEBUG -> "Developer build · everything unlocked, no subscription needed"
            betaActive -> "Beta · everything unlocked · $betaDaysLeft day${if (betaDaysLeft == 1L) "" else "s"} left"
            active -> "Premium · active until ${DateFormat.getMediumDateFormat(this).format(Date(entitlement.validUntilMillis))}"
            betaUsed -> "Free · rewrite, clipboard and templates need a subscription"
            else -> "Free · rewrite, clipboard and templates need a subscription, or try a free 20-day beta"
        }
        // No subscribe button in debug builds or during the beta window (everything is already
        // unlocked either way), or once a real subscription is already active. Offer "Try Beta"
        // only once per generation (see EntitlementStore.CURRENT_BETA_GENERATION) — a user who let
        // their beta expire, or is on a later generation than they used, doesn't see it again.
        while (planCard.childCount > 1) planCard.removeViewAt(1)
        if (!BuildConfig.DEBUG && !betaActive && !active) {
            if (!betaUsed) {
                planCard.addView(primaryButton("Try free for 20 days") {
                    entitlementStore.optInToBeta()
                    refreshPlan()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) }
                })
            }
            planCard.addView(secondaryButton("Subscribe") {
                billingGateway.startSubscriptionPurchase(this) { message -> runOnUiThread { toast(message) } }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) }
            })
        }
    }

    private fun refreshSetup() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val selected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            imm.currentInputMethodInfo?.packageName == packageName
        } else {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true
        }
        // Hidden setting; if Android refuses the read, assume the on-screen keyboard is hidden and show the hint.
        val showImeWithHardKeyboard = runCatching {
            Settings.Secure.getInt(contentResolver, "show_ime_with_hard_keyboard", 0) == 1
        }.getOrDefault(false)
        val hardwareKeyboard = resources.configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO &&
            !showImeWithHardKeyboard

        setupCard.removeAllViews()
        setupCard.addView(step(1, "Turn on Quietype", "In Android keyboard settings", enabled, "Open") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        setupCard.addView(divider())
        setupCard.addView(step(2, "Switch to Quietype", "Make it your active keyboard", selected, "Choose", actionEnabled = enabled) {
            imm.showInputMethodPicker()
        })
        if (hardwareKeyboard) {
            setupCard.addView(divider())
            setupCard.addView(step(3, "Show on-screen keyboard", "A physical keyboard is connected (common on emulators), so Android hides on-screen keyboards", false, "Fix") {
                startActivity(Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS))
            })
        }
    }

    private fun refreshBubble() {
        bubbleCard.removeAllViews()
        val permissionOn = FloatingAssistantService.isEnabled(this)
        val shown = permissionOn && settingsStore.appMode.needsShortcut
        bubbleCard.addView(label(
            "Rewrite in any app without opening the keyboard: tap the QT bubble, then Rewrite.",
            14f, MUTED
        ))
        // Google Play requires this disclosure before sending the user to enable an accessibility service.
        bubbleCard.addView(label(
            "Uses Android's accessibility service so it can read and replace the text box you tap Rewrite in. " +
                "It reads nothing else, nothing until you tap, and stores or sends nothing.",
            13f, MUTED
        ).apply { setPadding(0, dp(8), 0, 0) })
        val trailing = when {
            shown -> secondaryButton("Turn off") {
                // The bubble itself checks appMode live and hides immediately — no need to touch
                // Android's Accessibility permission at all for a user who just wants it gone.
                settingsStore.appMode = AppMode.KEYBOARD_ONLY
                refresh()
            }
            permissionOn -> label("Off (Keyboard-only mode)", 14f, MUTED)
            else -> primaryButton("Turn on") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Choose \"Quietype bubble\" and turn it on")
            }
        }
        val subtitle = when {
            shown -> "On — tap Turn off, or manage the permission in Android Accessibility settings"
            permissionOn -> "Permission granted, but hidden — switch \"How you rewrite\" above to Shortcut or Both to show it"
            else -> "Off"
        }
        bubbleCard.addView(row("Floating bubble", subtitle, trailing))
    }

    private fun refreshModels() {
        modelCard.removeAllViews()
        var downloading = false
        LocalModelOption.values().forEachIndexed { index, option ->
            if (index > 0) modelCard.addView(divider())
            val state = modelStore.state(option)
            if (state is ModelState.Downloading) downloading = true
            modelCard.addView(modelRow(option, state))
        }
        // DownloadManager has no progress callback; poll once a second while a download runs.
        if (downloading) handler.postDelayed(refreshTick, 1000)
    }

    private fun modelRow(option: LocalModelOption, state: ModelState): LinearLayout {
        val selected = settingsStore.localModelOption == option
        val trailing: View = when (state) {
            ModelState.Ready -> label(if (selected) "✓ In use" else "Ready", 14f, ACCENT, bold = selected)
            is ModelState.Downloading -> label("Downloading ${state.percent}%", 14f, MUTED)
            ModelState.NotDownloaded -> primaryButton("Download") {
                modelStore.download(option)
                settingsStore.localModelOption = option
                refresh()
            }.apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
        }
        val title = (if (selected) "● " else "○ ") + option.title
        // Once it's on the phone, the download size is no longer useful information.
        val subtitle = if (state == ModelState.Ready) option.detail.substringBefore(" · ") else option.detail
        return row(title, subtitle, trailing).apply {
            setOnClickListener {
                settingsStore.localModelOption = option
                refresh()
            }
        }
    }

    private fun step(
        number: Int,
        title: String,
        subtitle: String,
        done: Boolean,
        action: String,
        actionEnabled: Boolean = true,
        onAction: () -> Unit
    ): LinearLayout {
        val trailing = if (done) {
            label("✓ Done", 14f, ACCENT, bold = true)
        } else {
            primaryButton(action, onAction).apply {
                isEnabled = actionEnabled
                alpha = if (actionEnabled) 1f else 0.4f
            }
        }
        val badge = label(number.toString(), 14f, if (done) Color.WHITE else ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (done) ACCENT else ACCENT_SOFT, 14)
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(14) })
            addView(titleBlock(title, subtitle), LinearLayout.LayoutParams(0, -2, 1f))
            addView(trailing, LinearLayout.LayoutParams(-2, if (done) -2 else dp(40)).apply { marginStart = dp(12) })
        }
    }

    private fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val toggle = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        return row(title, subtitle, toggle).apply { setOnClickListener { toggle.toggle() } }
    }

    // Compact pill group for a small fixed set of numeric choices (e.g. clip retention days) —
    // a trailing-view-sized version of the segmented "How you rewrite" control.
    private fun daySelector(selected: Int, options: List<Int>, onSelect: (Int) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            background = rounded(BG, 10)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            options.forEach { days ->
                addView(TextView(this@MainActivity).apply {
                    text = "$days"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (days == selected) Color.WHITE else INK)
                    background = if (days == selected) rounded(ACCENT, 8) else null
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener { onSelect(days) }
                })
            }
        }

    // The launcher keys on a fixed light tile, so the dark-navy keys stay visible in dark theme too.
    private fun brandMark(sizeDp: Int): View = android.widget.ImageView(this).apply {
        setImageResource(R.drawable.ic_brand)
        setPadding(dp(sizeDp / 8), dp(sizeDp / 8), dp(sizeDp / 8), dp(sizeDp / 8))
        background = rounded(Color.rgb(247, 245, 252), sizeDp / 4)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    // Brief branded overlay on a cold start (not when the theme switch recreates the screen).
    private fun showSplash() {
        val decor = window.decorView as android.view.ViewGroup
        val splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            isClickable = true
            addView(brandMark(96))
            addView(label("Quietype", 32f, INK, bold = true).apply { setPadding(0, dp(18), 0, 0) })
            addView(label("Rewrite privately, on your phone", 14f, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        }
        decor.addView(splash, android.view.ViewGroup.LayoutParams(-1, -1))
        splash.animate().alpha(0f).setStartDelay(900).setDuration(350).withEndAction { decor.removeView(splash) }.start()
    }

    private fun themeSelector(): LinearLayout = LinearLayout(this).apply {
        background = rounded(BG, 10)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        ThemeMode.values().forEach { option ->
            val selected = option == settingsStore.themeMode
            addView(TextView(this@MainActivity).apply {
                text = option.title
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (selected) Palette.onAccent else INK)
                background = if (selected) rounded(ACCENT, 8) else null
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    settingsStore.themeMode = option
                    recreate()
                }
            })
        }
    }

    private fun linkRow(title: String, onClick: () -> Unit): LinearLayout =
        row(title, null, label("↗", 16f, ACCENT)).apply { setOnClickListener { onClick() } }

    // Responsive grid of tappable cards: 1 column on very narrow/legacy screens, 2 on a typical
    // phone, 3 on a tablet (sw600dp, Android's standard 7"+ breakpoint) — recomputed on every call
    // so rotating the device or resuming on a different window size picks up the right column count.
    private fun legalGrid(items: List<Pair<String, () -> Unit>>): LinearLayout {
        val columns = when {
            resources.configuration.screenWidthDp >= 600 -> 3
            resources.configuration.screenWidthDp >= 320 -> 2
            else -> 1
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            items.chunked(columns).forEach { rowItems ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowItems.forEach { (title, onClick) ->
                        addView(legalTile(title, onClick), LinearLayout.LayoutParams(0, -2, 1f).apply {
                            marginStart = dp(4); marginEnd = dp(4)
                        })
                    }
                    // Empty spacers keep a short last row aligned to the same column width as the rest.
                    repeat(columns - rowItems.size) {
                        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
    }

    private fun legalTile(title: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = dp(72)
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Palette.surface, 14)
        foreground = RippleDrawable(ColorStateList.valueOf(ACCENT_SOFT), null, null)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(label(title, 14f, INK, bold = true))
        addView(label("↗", 14f, ACCENT).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun openLegalPage(fileName: String) {
        startActivity(Intent(this, LegalActivity::class.java).putExtra(LegalActivity.EXTRA_PAGE, fileName))
    }

    private fun openSupportEmail() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
            .putExtra(Intent.EXTRA_SUBJECT, "Quietype support")
        runCatching { startActivity(intent) }.onFailure { toast("No email app found — contact $SUPPORT_EMAIL") }
    }

    // Available to every user, beta or not — this is device/app diagnostics only, never the user's
    // typed or rewritten text (see DiagnosticLog's own doc comment for why that boundary matters here).
    private fun sendDiagnosticReport() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
            .putExtra(Intent.EXTRA_SUBJECT, "Quietype diagnostic report")
            .putExtra(Intent.EXTRA_TEXT, DiagnosticLog.report(this))
        runCatching { startActivity(intent) }.onFailure { toast("No email app found — contact $SUPPORT_EMAIL") }
    }

    private fun row(title: String, subtitle: String?, trailing: View): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(0, dp(8), 0, dp(8))
        background = RippleDrawable(ColorStateList.valueOf(ACCENT_SOFT), null, null)
        addView(titleBlock(title, subtitle), LinearLayout.LayoutParams(0, -2, 1f))
        addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
    }

    private fun titleBlock(title: String, subtitle: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 16f, INK))
        if (subtitle != null) addView(label(subtitle, 13f, MUTED).apply { setPadding(0, dp(2), 0, 0) })
    }

    private fun sectionTitle(text: String): TextView = label(text.uppercase(), 12f, MUTED, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4), dp(28), 0, dp(8))
    }

    // A rotated, stroked badge next to the app name — reads as a stamp, no image asset needed.
    private fun privacyStamp(): TextView = TextView(this).apply {
        text = "🔒 PRIVACY\nFIRST"
        textSize = 10f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.04f
        setTextColor(ACCENT)
        setLineSpacing(0f, 0.95f)
        rotation = -8f
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), ACCENT)
            cornerRadius = dp(10).toFloat()
        }
    }

    // A section title plus its own body, grouped so both can be shown/hidden together
    // depending on the chosen mode (Keyboard / Shortcut / Both) — see refreshModeSelector().
    private fun section(title: String, body: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle(title))
            body()
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.surface, 18)
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = button(text, ACCENT, Color.WHITE, onClick)

    private fun secondaryButton(text: String, onClick: () -> Unit) = button(text, ACCENT_SOFT, ACCENT, onClick)

    private fun button(text: String, fill: Int, textColor: Int, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(18), 0, dp(18), 0)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 0, 0, 0)), rounded(fill, 12), null)
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        // TODO before Play Store submission: point these at URLs you control (see docs/legal/ and
        // docs/play-launch-checklist.md) and replace the support address with your real one.
        const val SUPPORT_EMAIL = "vijaydmb@gmail.com"

        val BG get() = Palette.bg
        val INK get() = Palette.ink
        val MUTED get() = Palette.muted
        val LINE get() = Palette.line
        val ACCENT get() = Palette.accent
        val ACCENT_SOFT get() = Palette.accentSoft
    }
}
