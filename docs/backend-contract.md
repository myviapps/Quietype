# Quietype Backend Contract

The Android app already contains client-side adapters for these endpoints. The backend must avoid logging request text and should redact request bodies from all infrastructure logs.

## POST /entitlements/verify

Purpose: verify a Google Play subscription purchase token and return the entitlement cache the app can enforce offline.

Request:

```json
{
  "purchaseToken": "google-play-token"
}
```

Response:

```json
{
  "status": "ACTIVE",
  "validUntilMillis": 1760000000000,
  "lastVerifiedAtMillis": 1759900000000,
  "offlineExpiresAtMillis": 1759986400000,
  "sessionToken": "server-issued-token"
}
```

Rules:

- `offlineExpiresAtMillis` must be no later than 24 hours after verification.
- `offlineExpiresAtMillis` must be no later than `validUntilMillis`.
- Return `EXPIRED` when Google Play reports cancellation, expiry, revocation, or invalid token.
- Include `sessionToken` only for active subscriptions. The Android client sends it as `Authorization: Bearer <token>` for cloud rewrite.

## GET /entitlements/current

Purpose: refresh the current user's entitlement. The production API should authenticate this request with the app account or a server-issued session token.

Response uses the same shape as `/entitlements/verify`.

## POST /rewrite

Purpose: cloud fallback rewrite only when the user has an active entitlement and has granted cloud fallback consent in the app.

Request:

```json
{
  "text": "i want this to be human written",
  "mode": "GRAMMAR_ONLY",
  "allowCloudFallback": true
}
```

Response:

```json
{
  "text": "I want this to be human-written."
}
```

Rules:

- Do not add facts.
- Do not change user intent.
- Keep grammar-only rewrites close to the original words.
- Avoid generic AI phrasing.
- Do not store input or output text after the response is sent.
- Require an active backend session token. The app's local entitlement check is not enough for backend access.
