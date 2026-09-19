# Play Billing Setup

The Android app already has entitlement storage, backend verification calls, and paid-feature guards. Production billing needs Play Console setup before code can be fully wired.

## Play Console Tasks

1. Create an app in Google Play Console.
2. Create a monthly subscription product:
   - Product id: `humanrewrite_monthly`
   - Base plan id: `monthly`
   - Offer: optional free trial only after abuse controls exist.
3. Link a Google Cloud project to Play Console.
4. Enable Google Play Developer API.
5. Create a backend service account with subscription read permissions.
6. Configure Real-time Developer Notifications to a backend Pub/Sub topic or HTTPS endpoint.

## Backend Production Replacement

Replace the local `test_active*` behavior in `backend/src/entitlements.js` with:

- Validate purchase token with Google Play Developer API.
- Confirm package name is `com.humanrewrite.keyboard`.
- Confirm product id is `humanrewrite_monthly`.
- Confirm subscription state is active, grace, or expired.
- Return `offlineExpiresAtMillis = min(validUntilMillis, now + 24h)`.
- Revoke session tokens when RTDN reports expiry, cancellation, refund, or revocation.

## Android Production Replacement

Replace `BillingNotConfiguredGateway` with a Billing Library implementation:

- Query `humanrewrite_monthly`.
- Launch purchase flow from settings/onboarding.
- On purchase success, send purchase token to `/entitlements/verify`.
- On restore, query existing purchases and verify the newest active token.
- Never unlock paid features from client-only purchase state.

