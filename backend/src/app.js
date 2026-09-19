import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { currentEntitlement, requireActiveSession, verifyPurchaseToken } from './entitlements.js';
import { isCloudRewriteConfigured, rewriteText } from './rewrite.js';

const MAX_BODY_BYTES = 16 * 1024;
const RATE_LIMIT = 20;
const RATE_WINDOW_MS = 60_000;
const hits = new Map();

// Fixed-window per-IP limit for the endpoints that cost money or mint sessions. In-memory, so it
// resets on restart and is per-instance; behind a proxy set the client IP header at the proxy.
function rateLimited(req) {
  const now = Date.now();
  const forwarded = process.env.VERCEL ? String(req.headers['x-forwarded-for'] || '').split(',')[0].trim() : '';
  const key = forwarded || req.socket.remoteAddress || 'unknown';
  const entry = hits.get(key);
  if (!entry || now - entry.start > RATE_WINDOW_MS) {
    if (hits.size > 10_000) hits.clear();
    hits.set(key, { start: now, count: 1 });
    return false;
  }
  entry.count += 1;
  return entry.count > RATE_LIMIT;
}

// Real, plain, publicly-reachable pages once this backend is deployed anywhere — no separate
// hosting needed for the Privacy Policy / Terms / Open Source links Play Store requires.
const LEGAL_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'docs', 'legal');

export async function handle(req, res) {
  try {
    await route(req, res);
  } catch (error) {
    safeLog(`request_failed path=${req.url} message=${error.message}`);
    if (error.message === 'request_too_large') return sendJson(res, 413, { error: 'request_too_large' });
    if (error.message === 'invalid_json') return sendJson(res, 400, { error: 'invalid_json' });
    if (error.message === 'play_developer_api_not_configured' || error.message === 'session_secret_not_configured') {
      return sendJson(res, 503, { error: 'verification_unavailable' });
    }
    sendJson(res, 500, { error: 'internal_error' });
  }
}

async function route(req, res) {
  if (req.method === 'GET' && req.url === '/health') {
    return sendJson(res, 200, { ok: true });
  }

  if (req.method === 'GET' && req.url.startsWith('/legal/')) {
    return serveLegalPage(req.url.slice('/legal/'.length), res);
  }

  if (req.method === 'POST' && (req.url === '/entitlements/verify' || req.url === '/rewrite') && rateLimited(req)) {
    return sendJson(res, 429, { error: 'rate_limited' });
  }

  if (req.method === 'POST' && req.url === '/entitlements/verify') {
    const body = await readJson(req);
    // Play Integrity: verifyPurchaseToken decodes body.integrityToken when present (required if REQUIRE_INTEGRITY=1).
    const entitlement = await verifyPurchaseToken(body.purchaseToken, body.integrityToken);
    return sendJson(res, 200, entitlement);
  }

  if (req.method === 'GET' && req.url === '/entitlements/current') {
    const token = bearerToken(req.headers.authorization || '');
    return sendJson(res, 200, currentEntitlement(token));
  }

  if (req.method === 'POST' && req.url === '/rewrite') {
    if (!isCloudRewriteConfigured()) return sendJson(res, 503, { error: 'cloud_rewrite_not_configured' });

    const entitlement = requireActiveSession(req.headers.authorization || '');
    if (!entitlement) return sendJson(res, 401, { error: 'subscription_required' });

    const body = await readJson(req);
    if (body.allowCloudFallback !== true) return sendJson(res, 400, { error: 'cloud_not_allowed' });

    const rewritten = await rewriteText(body.text, body.mode);
    return sendJson(res, 200, {
      text: rewritten,
      engine: 'cloud-fallback',
      requestedLocalModel: body.localModel || 'NONE'
    });
  }

  sendJson(res, 404, { error: 'not_found' });
}

async function serveLegalPage(requested, res) {
  // path.normalize collapses "../" segments before the startsWith check, so a request can't
  // escape LEGAL_DIR to read arbitrary files off the server.
  const name = requested || 'privacy-policy.html';
  const filePath = path.normalize(path.join(LEGAL_DIR, name));
  if (!filePath.startsWith(LEGAL_DIR + path.sep) || !filePath.endsWith('.html')) {
    return sendJson(res, 404, { error: 'not_found' });
  }
  try {
    const html = await readFile(filePath, 'utf8');
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(html);
  } catch {
    sendJson(res, 404, { error: 'not_found' });
  }
}

function bearerToken(authHeader) {
  const match = /^Bearer\s+(.+)$/i.exec(authHeader);
  return match?.[1] || '';
}

function readJson(req) {
  // Vercel's Node runtime has already consumed the stream and exposes the parsed body.
  if (req.body !== undefined) {
    const body = typeof req.body === 'string' ? safeParse(req.body) : req.body;
    if (body === null || typeof body !== 'object' || Array.isArray(body)) return Promise.reject(new Error('invalid_json'));
    return Promise.resolve(body);
  }
  return new Promise((resolve, reject) => {
    let size = 0;
    let raw = '';
    req.setEncoding('utf8');
    req.on('data', (chunk) => {
      size += Buffer.byteLength(chunk);
      if (size > MAX_BODY_BYTES) {
        reject(new Error('request_too_large'));
        req.destroy();
        return;
      }
      raw += chunk;
    });
    req.on('end', () => {
      try {
        const parsed = raw ? JSON.parse(raw) : {};
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('shape');
        resolve(parsed);
      } catch {
        reject(new Error('invalid_json'));
      }
    });
    req.on('error', reject);
  });
}

function safeParse(text) {
  try { return JSON.parse(text || '{}'); } catch { return null; }
}

function sendJson(res, statusCode, body) {
  res.writeHead(statusCode, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store'
  });
  res.end(JSON.stringify(body));
}

function safeLog(message) {
  // Never log request bodies. User text may be sensitive.
  console.log(`[quietype] ${message}`);
}
