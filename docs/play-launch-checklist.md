# Play Store launch checklist

Everything on this list needs your Google Play Console / Google Cloud account — none of it
can be done from inside this codebase. The app-side code (Billing Library, entitlement store,
in-app disclosures) is already built and waiting on these.

## 0. Done — one hosting step + two text edits from you before submitting
- **Privacy Policy**, **Terms of Service**, and an **Open Source licenses** page are written as
  plain static HTML in `docs/legal/` (`privacy-policy.html`, `terms-of-service.html`,
  `open-source.html`). These are normal web pages — no account of any kind needed to view them.
- **They're also bundled inside the app itself** (`app/src/main/assets/legal/`, opened by
  `LegalActivity` via Settings → Legal & support) — readable with no internet connection and no
  external hosting at all, which matters since there's no domain for this app yet.
- **Play Console still requires a real external URL** for its Privacy Policy field — an in-app
  screen doesn't satisfy that by itself. **Hosted on GitHub Pages: done.** Site home:
  `https://myviapps.github.io/Quietype/` (`docs/legal/index.html`, links to all three pages).
  - **Paste into Play Console → Privacy Policy:** `https://myviapps.github.io/Quietype/privacy-policy.html`
    (use the page URL, not the home page — Play wants the policy itself at that address).
  - After any edit to `docs/legal/`, re-upload the changed files to that repo so the live pages
    match the app's bundled copies.
  - Alternative: once you deploy the backend (you'll need to anyway, for real subscription
    verification — section 2), it already serves the same three files at `/legal/<file>` (see
    the route in `backend/src/server.js`) — no separate hosting needed at that point.
- **No placeholders left** inside the HTML files (both copies — `docs/legal/` and
  `app/src/main/assets/legal/` are kept in sync manually; a future edit should update both). The
  support email is filled in (`vijaydmb@gmail.com`, also `SUPPORT_EMAIL` in `MainActivity.kt`),
  and the Terms' governing-law line now names India (the app is developed there) — courts of
  India have exclusive jurisdiction, Indian law governs. Revisit if the developer's actual home
  jurisdiction ever changes.
- Cloud AI fallback is disabled in the current build (on-device only) — `CloudRewriteEngine` and
  the backend `/rewrite` endpoint are kept but disconnected, for a future release.

## 1. Play Console app + subscription product
- Create the app listing in Play Console ($25 one-time developer fee if you don't already have
  an account).
- Create a subscription product with ID `humanrewrite_monthly` (matches
  `SUBSCRIPTION_PRODUCT_ID` in `BillingGateway.kt` — change both together if you want a
  different ID) and at least one base plan/offer.
- Until this product exists, the app's "Subscribe" button connects to Play Billing but has
  nothing to sell — `PlayBillingGateway.startSubscriptionPurchase` logs a warning and does
  nothing, which is expected.

## 2. Server-side purchase verification (replaces the dev stub)

### 2a. Purchase token verification — required
`backend/src/entitlements.js` now calls the Play Developer API (`purchases.subscriptionsv2.get`).
Test tokens (`test_active*`) are ignored unless `ALLOW_DEV_TOKENS=1` and `NODE_ENV` is not
production. To go live:
- Create a Google Cloud service account with the **Android Publisher API** enabled, invite it in
  Play Console (Users and permissions, "View financial data"), and export the JSON key.
- Set `GOOGLE_SERVICE_ACCOUNT_JSON` (the key's contents) and `NODE_ENV=production` on the backend.
- Set `backendUrl=https://your-backend` in gradle.properties so release builds know where to verify.
- Optional but recommended: a Pub/Sub topic + Real-time Developer Notifications (RTDN) so the
  backend learns about renewals/cancellations immediately instead of only when the app checks.

### 2b. Play Integrity API — anti-tampering, wired client-side, needs your project number
This is the actual answer to "can someone patch the APK to skip the paywall": a purchase token
alone only proves *a* purchase happened somewhere — it can't prove *this specific app binary,
unmodified* is the one asking. Play Integrity closes that gap.
- `IntegrityGateway.kt` requests a token from Google bound to a hash of the purchase token being
  verified (Play's current "Standard" API, not the older nonce-based "Classic" one). It's wired
  into `PlayBillingGateway` already — every purchase-verify call sends `integrityToken` alongside
  `purchaseToken` to the backend.
- **What's still needed from you**: your Play Console app's linked Google Cloud project number
  (Play Console → App integrity → Play Integrity API), set in `IntegrityGateway.CLOUD_PROJECT_NUMBER`
  (it's `0` right now, which disables the check — the app still works, just without this extra
  layer, falling back to purchase-token-only verification).
- **What's still needed server-side**: decode the token with Google's Play Integrity API
  (`playintegrity.googleapis.com`, `decodeIntegrityToken`) using a service account for that same
  Cloud project, and reject requests where the verdict shows a tampered app, an unlicensed
  install, or a suspicious device — see the `TODO` left in `server.js`'s `/entitlements/verify`
  handler.

### On sideloading, specifically
Sharing the `.apk`/`.aab` outside Play Store doesn't, by itself, let someone skip payment —
Play Billing still requires the device to have Google Play Store installed and a real Google
account to complete a purchase, regardless of where the app itself came from, and the backend is
the one deciding whether paid features unlock, not anything baked into the APK. What sideloading
*does* enable is someone **patching** a copy of the APK to remove the entitlement check entirely
(a different attack from bypassing payment) — that's what section 2b defends against. No
client-side check is unbeatable on a rooted or fully compromised device; Play Integrity plus
server-side verification is the standard practical ceiling, not an absolute guarantee.

## 3. Cloud rewrite (optional — only needed if you want the cloud fallback to work)
`backend/src/rewrite.js` calls the Anthropic API and requires `ANTHROPIC_API_KEY` to be set in
the backend's environment. Without it, `/rewrite` returns `503 cloud_rewrite_not_configured` —
the app falls back to on-device rewrite when this happens, which is the intended behavior. This
step is skippable if you're comfortable with on-device-only rewriting.

## 4. Play Console policy forms
These are filled out when you create the store listing, using the same facts already reflected
in the app's own UI:
- **Data Safety form**: text you select and tap Rewrite on is processed on-device, always —
  there is currently no way to send it anywhere else (see Settings → Privacy in the app, and
  section 0 above on the disabled cloud fallback). No text is stored on the backend (see
  `safeLog` in `server.js`, which never logs bodies).
- **Accessibility API declaration**: required because of the floating bubble
  (`FloatingAssistantService`). Play's questionnaire asks why the app uses
  `BIND_ACCESSIBILITY_SERVICE` — the answer is "reads/replaces the text field the user is
  actively editing, only when the user taps Rewrite, to provide the app's core rewriting
  feature without requiring the user to switch keyboards." The in-app disclosure text is in
  `strings.xml` (`bubble_description`) and `accessibility_service.xml`.
- **Generated/AI content disclosure**: the app rewrites user-authored text (grammar/tone), it
  does not generate new content from a prompt — check Play's current policy page for how this
  distinction is classified at the time you submit, since policy wording changes.

## 5. Real-device beta testing
The emulator used during development has no Play Store, no SIM/MMS, and can't install WhatsApp,
so testing here stopped at Google Messages, Chrome, and the app's own fields. Before a public
release, test Rewrite (keyboard and bubble) and stickers in: WhatsApp, Gmail, LinkedIn, Chrome,
Instagram/Threads — on a real device signed into a real Google account, per the original plan's
beta phase.

## 6. Release build — code side is done, signing is the one thing left
- `app/build.gradle.kts` now has a `release` build type: R8 minify + resource shrinking on, and
  ProGuard rules (`app/proguard-rules.pro`) that specifically protect the llama.cpp JNI native
  methods — without that rule a shrunk build would crash on the very first rewrite
  (`UnsatisfiedLinkError`), since R8 would rename `LlamaEngine`'s methods out from under the
  compiled `.so`'s symbol names. Verified: `./gradlew bundleRelease` and `assembleRelease` both
  build clean with this on.
- `android:debuggable` is already `false` in release builds — that's AGP's default for the
  `release` build type, nothing extra to configure.
- **Signing** is the one manual step: copy `keystore.properties.example` → `keystore.properties`
  (already git-ignored) and fill in a real keystore — instructions are in that file. Without it,
  `bundleRelease`/`assembleRelease` produce a valid but *unsigned* build; Play Console needs a
  signed one, or you can let **Play App Signing** (offered on your first upload) manage the
  final signing key from an upload key you provide.
- **Before each release**, bump both `versionCode` (must strictly increase — currently `1`) and
  `versionName` (user-facing string — currently `"0.1.0"`) in `app/build.gradle.kts`.
- Play Store requires an **`.aab`** (Android App Bundle), not a bare `.apk` — that's what
  `bundleRelease` produces (`app/build/outputs/bundle/release/app-release.aab`); the `.apk` from
  `assembleRelease` is for your own sideloaded testing only.

## 7. Store listing assets to prepare
Not code — these are made outside this repo (a design tool, or ask for help generating them):

| Asset | Spec |
|---|---|
| App title | ≤ 30 characters |
| Short description | ≤ 80 characters |
| Full description | ≤ 4,000 characters |
| App icon | 512×512 px, PNG or JPEG |
| Feature graphic | 1024×500 px, shown at the top of the listing |
| Screenshots | 2–8 per device type, 16:9 or 9:16 |

The in-app "🔒 PRIVACY FIRST" stamp (`MainActivity.privacyStamp()`) and the teal accent color
(`#18615B`) are the closest thing to an existing visual identity — worth carrying into the icon
and feature graphic for consistency, if you want a starting point rather than a blank page.

## 8. The 14-day closed testing rule
If this is registered as a *personal* Google Play developer account created after
**November 13, 2023**, Play requires a closed test with **12+ testers opted in continuously for
14 days** before production access is granted — factor this into your timeline; it can't be
skipped or sped up. Since April 2026 Google also checks that testers actually *used* the app
during that window, not just opted in, so recruit testers who'll genuinely open it a few times.
(Verify this still applies at the time you register — Play's policies do change.)

**Free access during the beta, no accounts needed**: `EntitlementStore.BETA_ENTITLEMENT_UNTIL_MILLIS`
(`app/src/main/java/com/humanrewrite/keyboard/core/EntitlementStore.kt`) is a single cutoff
timestamp — while `now < that date`, every paid feature (Rewrite, Clipboard, Templates) is
unlocked for anyone running that build, no purchase or sign-up, since the app has no accounts to
gate by anyway. **Currently set to a real window: 2026-09-17 → 2026-10-17** (30 days, double the
14-day Play Console minimum). Once the cutoff passes, the *same installed app* (no update needed)
automatically falls back to the real Play Billing check — beta testers who never subscribed see
the normal "Free · subscribe" prompt from then on, exactly like a first-time user. **Set it back
to `0L` before building the eventual production release** — a real launch must not ship with the
bypass still active. Who gets the beta build at all is controlled entirely by Play Console's
Closed Testing tester list (email allowlist or opt-in link) — that's Play Console configuration,
not app code.

## 9. Launch sequence, in order
1. **Create the app** in Play Console (default language, app vs. game, free vs. paid).
2. **App content declarations** (all mandatory): Content Rating questionnaire (→ IARC age
   rating), Data Safety form (section 4 above has the answers), Target Audience (this app isn't
   directed at children — keep COPPA/Families requirements out of scope by answering
   accordingly).
3. **Main store listing**: title, descriptions, icon, feature graphic, screenshots, support
   email (section 0's `SUPPORT_EMAIL`), Privacy Policy URL (section 0).
4. **Upload the release**: pick a track (Internal → Closed → Open → Production is the safe
   order), opt into Play App Signing, upload the signed `.aab` from section 6, write release
   notes.
5. **Review release → Start rollout.** Google's review is typically **3–7 days** for a new app —
   plan the closed-testing 14 days (section 8) to run before or alongside this, not after.
