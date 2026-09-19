import crypto from 'node:crypto';

const DAY_MS = 24 * 60 * 60 * 1000;
const PACKAGE_NAME = process.env.PLAY_PACKAGE_NAME || 'com.humanrewrite.keyboard';

// Sessions are stateless: the token is `payload.signature`, where the payload carries the subscription
// expiry and the signature is an HMAC with SESSION_SECRET. Nothing is stored server-side, so it works
// on serverless hosts (Vercel) where memory doesn't survive between requests.
const SESSION_SECRET = process.env.SESSION_SECRET || (process.env.NODE_ENV === 'production' ? '' : crypto.randomBytes(32).toString('hex'));

function sign(payload) {
  return crypto.createHmac('sha256', SESSION_SECRET).update(payload).digest('base64url');
}

// Test tokens ("test_active...") are only honoured when ALLOW_DEV_TOKENS=1 is set explicitly and
// NODE_ENV is not production. Default is closed, so a deploy that forgets an env var can't hand
// out free subscriptions.
const DEV_TOKENS_ALLOWED = process.env.ALLOW_DEV_TOKENS === '1' && process.env.NODE_ENV !== 'production';

// Set REQUIRE_INTEGRITY=1 once the app ships with a Play Integrity cloud project number; until then
// a request without an integrity token is still accepted (a token that IS sent must always be valid).
const REQUIRE_INTEGRITY = process.env.REQUIRE_INTEGRITY === '1';

export async function verifyPurchaseToken(purchaseToken, integrityToken) {
  if (!purchaseToken || typeof purchaseToken !== 'string' || purchaseToken.length > 2048) {
    return expiredEntitlement();
  }
  if (DEV_TOKENS_ALLOWED && purchaseToken.startsWith('test_active')) {
    return createSession(Date.now() + 30 * DAY_MS);
  }
  if (integrityToken || REQUIRE_INTEGRITY) {
    if (typeof integrityToken !== 'string' || !(await integrityPasses(integrityToken, purchaseToken))) {
      return expiredEntitlement();
    }
  }
  const validUntilMillis = await lookupPlaySubscription(purchaseToken);
  return validUntilMillis > Date.now() ? createSession(validUntilMillis) : expiredEntitlement();
}

// Asks Google Play whether this purchase token is a live subscription (subscriptionsv2.get).
// Needs GOOGLE_SERVICE_ACCOUNT_JSON: a Play Console-linked service account with "View financial
// data" permission. Returns the subscription's expiry in ms, or 0 if it isn't entitled.
async function lookupPlaySubscription(purchaseToken) {
  const accessToken = await getServiceAccountToken();
  const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${
    encodeURIComponent(PACKAGE_NAME)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
  const response = await fetch(url, { headers: { authorization: `Bearer ${accessToken}` } });
  if (response.status === 400 || response.status === 404 || response.status === 410) return 0;
  if (!response.ok) throw new Error(`play_api_error_${response.status}`);

  const body = await response.json();
  const entitledStates = ['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_CANCELED'];
  if (!entitledStates.includes(body.subscriptionState)) return 0;
  // CANCELED still has access until expiry; the client re-verifies on every app open.
  const expiry = Date.parse(body.lineItems?.[0]?.expiryTime || '');
  return Number.isFinite(expiry) ? expiry : 0;
}

// Decodes the app's Play Integrity token with Google and checks it was issued for THIS purchase
// token (request hash), by our package, from a Play-installed, unmodified app on a genuine device,
// for a licensed account, within the last 10 minutes.
export async function integrityPasses(integrityToken, purchaseToken) {
  const accessToken = await getServiceAccountToken('https://www.googleapis.com/auth/playintegrity');
  const response = await fetch(
    `https://playintegrity.googleapis.com/v1/${encodeURIComponent(PACKAGE_NAME)}:decodeIntegrityToken`,
    {
      method: 'POST',
      headers: { authorization: `Bearer ${accessToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ integrityToken })
    }
  );
  if (!response.ok) return false;
  return checkIntegrityPayload((await response.json()).tokenPayloadExternal, purchaseToken);
}

export function checkIntegrityPayload(payload, purchaseToken, now = Date.now()) {
  if (!payload) return false;
  const expectedHash = crypto.createHash('sha256').update(purchaseToken, 'utf8').digest('base64url');
  const request = payload.requestDetails || {};
  return request.requestPackageName === PACKAGE_NAME &&
    request.requestHash === expectedHash &&
    Math.abs(now - Number(request.timestampMillis)) < 10 * 60_000 &&
    payload.appIntegrity?.appRecognitionVerdict === 'PLAY_RECOGNIZED' &&
    (payload.deviceIntegrity?.deviceRecognitionVerdict || []).includes('MEETS_DEVICE_INTEGRITY') &&
    payload.accountDetails?.appLicensingVerdict === 'LICENSED';
}

const cachedTokens = new Map();

async function getServiceAccountToken(scope = 'https://www.googleapis.com/auth/androidpublisher') {
  const cached = cachedTokens.get(scope);
  if (cached && cached.expiresAt > Date.now() + 60_000) return cached.value;
  const raw = process.env.GOOGLE_SERVICE_ACCOUNT_JSON;
  if (!raw) throw new Error('play_developer_api_not_configured');
  const account = JSON.parse(raw);

  const now = Math.floor(Date.now() / 1000);
  const b64 = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const unsigned = `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64({
    iss: account.client_email,
    scope,
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600
  })}`;
  const signature = crypto.sign('RSA-SHA256', Buffer.from(unsigned), account.private_key).toString('base64url');

  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: `${unsigned}.${signature}`
    })
  });
  if (!response.ok) throw new Error(`google_oauth_error_${response.status}`);
  const token = await response.json();
  cachedTokens.set(scope, { value: token.access_token, expiresAt: Date.now() + token.expires_in * 1000 });
  return token.access_token;
}

function createSession(validUntilMillis) {
  if (!SESSION_SECRET) throw new Error('session_secret_not_configured');
  const now = Date.now();
  const payload = Buffer.from(JSON.stringify({ v: validUntilMillis, n: crypto.randomBytes(8).toString('hex') })).toString('base64url');
  return entitlementFor(`${payload}.${sign(payload)}`, validUntilMillis, now);
}

function entitlementFor(sessionToken, validUntilMillis, now) {
  return {
    status: 'ACTIVE',
    validUntilMillis,
    lastVerifiedAtMillis: now,
    offlineExpiresAtMillis: Math.min(validUntilMillis, now + DAY_MS),
    sessionToken
  };
}

export function currentEntitlement(sessionToken) {
  if (!SESSION_SECRET || typeof sessionToken !== 'string') return expiredEntitlement();
  const [payload, signature, ...extra] = sessionToken.split('.');
  if (!payload || !signature || extra.length) return expiredEntitlement();
  const expected = Buffer.from(sign(payload));
  const given = Buffer.from(signature);
  if (expected.length !== given.length || !crypto.timingSafeEqual(expected, given)) return expiredEntitlement();
  let validUntilMillis;
  try {
    validUntilMillis = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')).v;
  } catch {
    return expiredEntitlement();
  }
  const now = Date.now();
  if (!Number.isFinite(validUntilMillis) || now > validUntilMillis) return expiredEntitlement();
  return entitlementFor(sessionToken, validUntilMillis, now);
}

export function requireActiveSession(authHeader) {
  const token = extractBearer(authHeader);
  if (!token) return null;
  const entitlement = currentEntitlement(token);
  return entitlement.status === 'ACTIVE' ? entitlement : null;
}

function extractBearer(authHeader = '') {
  const match = /^Bearer\s+(.+)$/i.exec(authHeader);
  return match?.[1] || '';
}

function expiredEntitlement() {
  const now = Date.now();
  return {
    status: 'EXPIRED',
    validUntilMillis: 0,
    lastVerifiedAtMillis: now,
    offlineExpiresAtMillis: 0,
    sessionToken: ''
  };
}
