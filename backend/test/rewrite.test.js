import assert from 'node:assert/strict';
import { isCloudRewriteConfigured, rewriteText } from '../src/rewrite.js';
import { checkIntegrityPayload, requireActiveSession, verifyPurchaseToken } from '../src/entitlements.js';
import crypto from 'node:crypto';

// No ANTHROPIC_API_KEY in the test environment: cloud rewrite must refuse rather than fake a result.
delete process.env.ANTHROPIC_API_KEY;
assert.equal(isCloudRewriteConfigured(), false);
await assert.rejects(() => rewriteText('hello', 'GRAMMAR_ONLY'), /cloud_rewrite_not_configured/);

// Dev tokens are closed by default: without ALLOW_DEV_TOKENS=1 a test token is not a subscription,
// and with no service account configured verification fails closed.
delete process.env.GOOGLE_SERVICE_ACCOUNT_JSON;
await assert.rejects(() => verifyPurchaseToken('test_active_local'), /play_developer_api_not_configured/);
assert.equal(requireActiveSession('Bearer bad-token'), null);

import { execFileSync } from 'node:child_process';
const run = (env, code) => execFileSync(process.execPath, ['-e', code], {
  cwd: new URL('..', import.meta.url), env: { ...process.env, ...env }
}).toString().trim();
const probe = `
  import('./src/entitlements.js').then(async ({ verifyPurchaseToken, requireActiveSession }) => {
    const e = await verifyPurchaseToken('test_active_local').catch(() => ({ status: 'ERR' }));
    console.log(e.status === 'ACTIVE' && requireActiveSession('Bearer ' + e.sessionToken) ? 'ACTIVE' : e.status);
  });`;
assert.equal(run({ ALLOW_DEV_TOKENS: '1', NODE_ENV: 'development' }, probe), 'ACTIVE');
assert.equal(run({ ALLOW_DEV_TOKENS: '1', NODE_ENV: 'production' }, probe), 'ERR');
assert.equal(run({ ALLOW_DEV_TOKENS: '', NODE_ENV: 'development' }, probe), 'ERR');
const good = {
  requestDetails: {
    requestPackageName: 'com.humanrewrite.keyboard',
    requestHash: crypto.createHash('sha256').update('tok').digest('base64url'),
    timestampMillis: String(Date.now())
  },
  appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED' },
  deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY'] },
  accountDetails: { appLicensingVerdict: 'LICENSED' }
};
assert.equal(checkIntegrityPayload(good, 'tok'), true);
assert.equal(checkIntegrityPayload(good, 'other-token'), false, 'hash must bind to the purchase token');
assert.equal(checkIntegrityPayload({ ...good, appIntegrity: { appRecognitionVerdict: 'UNRECOGNIZED_VERSION' } }, 'tok'), false);
assert.equal(checkIntegrityPayload({ ...good, deviceIntegrity: { deviceRecognitionVerdict: [] } }, 'tok'), false);
assert.equal(checkIntegrityPayload(good, 'tok', Date.now() + 3_600_000), false, 'stale token');
console.log('backend tests passed');
