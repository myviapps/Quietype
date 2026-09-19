package com.humanrewrite.keyboard.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.humanrewrite.keyboard.core.Palette
import com.humanrewrite.keyboard.core.SettingsStore

/**
 * Shows one of the bundled legal pages (app/src/main/assets/legal/) inside the app, so Privacy
 * Policy / Terms / Open Source licenses are readable with no network and no external hosting —
 * useful since the app has no domain of its own yet. Play Console's Privacy Policy field still
 * needs a real external URL (docs/play-launch-checklist.md has the no-cost way to get one); this
 * is the in-app copy for users, not a replacement for that listing requirement.
 *
 * The bottom bar's "Mark as read" is the explicit action that satisfies onboarding's read-gate
 * (MainActivity.buildOnboarding) — reading it there rather than relying on Android's back button,
 * which is easy to trigger by accident and wouldn't clearly register as "I read this."
 */
class LegalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE) ?: "privacy-policy.html"
        val settingsStore = SettingsStore(this)
        Palette.apply(this, settingsStore.themeMode)
        Palette.styleWindow(window)

        val webView = WebView(this).apply {
            // Without a WebViewClient, WebView hands EVERY tapped link — even the relative ones
            // between our own bundled pages, like Terms' link to Privacy — to the system's
            // default handler, which opens Chrome instead of navigating in place. Cross-links
            // between our own file:// pages stay in the WebView; genuine https:// links (the
            // Open Source page's GitHub/Hugging Face credits) still open in the browser, correctly.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme == "file") return false
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    return true
                }

                // The pages' own CSS has light + dark palettes; this pins the one matching the app's theme.
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(
                        "document.documentElement.setAttribute('data-theme','${if (Palette.isDark) "dark" else "light"}')", null
                    )
                }
            }
            setBackgroundColor(Palette.bg)
            loadUrl("file:///android_asset/legal/$page")
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Palette.surface)
            addView(TextView(this@LegalActivity).apply {
                text = titleFor(page)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Palette.ink)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@LegalActivity).apply {
                text = "✓ Mark as read & go back"
                isAllCaps = false
                setTextColor(Palette.onAccent)
                setBackgroundColor(Palette.accent)
                setOnClickListener {
                    when (page) {
                        "privacy-policy.html" -> settingsStore.readPrivacyPolicy = true
                        "terms-of-service.html" -> settingsStore.readTermsOfService = true
                    }
                    finish()
                }
            })
        }

        setContentView(FrameLayout(this).apply {
            addView(LinearLayout(this@LegalActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(bar)
                addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
            })
            // The bundled pages already pad for a status bar via CSS env(safe-area-inset-*) only
            // on platforms that set it; WebView doesn't, so pad the container instead.
            setOnApplyWindowInsetsListener { view, insets ->
                val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.systemBars()).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                view.setPadding(0, top, 0, 0)
                insets
            }
        })
    }

    private fun titleFor(page: String) = when (page) {
        "privacy-policy.html" -> "Privacy Policy"
        "terms-of-service.html" -> "Terms of Service"
        "open-source.html" -> "Open Source Licenses"
        else -> "Legal"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PAGE = "page"
    }
}
