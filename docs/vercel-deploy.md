# Deploying the backend on Vercel

1. Vercel → Add New Project → import `myviapps/Quietype`.
2. **Root Directory: `backend`**. Framework preset: Other. No build command.
3. Environment variables (Production):
   - `NODE_ENV=production`
   - `SESSION_SECRET` — a long random string (`node -e "console.log(require('crypto').randomBytes(48).toString('hex'))"`). Required: sessions are signed with it, so rotating it logs everyone out.
   - `GOOGLE_SERVICE_ACCOUNT_JSON` — the full service-account JSON (Play Developer API + Play Integrity).
   - `REQUIRE_INTEGRITY=1` — only after the app ships with its Play Integrity project number.
   - `ANTHROPIC_API_KEY` — only if you re-enable cloud rewrite (off in the app today).
4. Deploy. Your URL `https://<project>.vercel.app` goes in `gradle.properties` as `backendUrl=...`.

Notes
- Sessions are stateless signed tokens, so nothing needs a database.
- The per-IP rate limit is in-memory per function instance, so it is best-effort. Add a Vercel Firewall rate-limit rule on `/entitlements/verify` and `/rewrite` for a real limit.
- Legal pages are served from `backend/public/legal/` (copy of `docs/legal/`). After editing `docs/legal`, run `npm run sync-legal` in `backend/`.
- Vercel's free Hobby plan is non-commercial; a paid app needs Pro.
