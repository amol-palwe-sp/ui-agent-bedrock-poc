'use strict';

const express = require('express');
const fs = require('fs');
const path = require('path');
const { ACCOUNTS } = require('./lib/data');
const { applyVariant } = require('./lib/mutate');

const app = express();
const PORT = process.env.PORT || 4599;
// Second origin (same machine, different port) used only to make the
// cross-origin-iframe scenario genuinely cross-origin while staying offline.
const ALT_PORT = process.env.ALT_PORT || 4600;
const PUBLIC = path.join(__dirname, 'public');

// Fixed fake credentials (documented in the catalog). Safe, non-secret demo values.
const FIXTURE_EMAIL = 'test.user@demo.local';
const FIXTURE_PASSWORD = 'Demo!Pass123';
const SESSION_COOKIE = 'th_session';
const SESSION_VALUE = 'demo-authenticated';

app.use(express.urlencoded({ extended: false }));
app.use(express.json());

// --- tiny cookie parser (avoids extra dependency) ---------------------------
app.use((req, _res, next) => {
  req.cookies = {};
  const raw = req.headers.cookie;
  if (raw) {
    raw.split(';').forEach((pair) => {
      const idx = pair.indexOf('=');
      if (idx > -1) req.cookies[pair.slice(0, idx).trim()] = decodeURIComponent(pair.slice(idx + 1).trim());
    });
  }
  next();
});

function isAuthed(req) {
  return req.cookies[SESSION_COOKIE] === SESSION_VALUE;
}

// --- fake auth ---------------------------------------------------------------
app.post('/login', (req, res) => {
  const { email, password, redirect } = req.body || {};
  if (email === FIXTURE_EMAIL && password === FIXTURE_PASSWORD) {
    res.setHeader('Set-Cookie', `${SESSION_COOKIE}=${SESSION_VALUE}; Path=/; HttpOnly; SameSite=Lax`);
    return res.redirect(302, redirect && redirect.startsWith('/') ? redirect : '/secure/dashboard.html');
  }
  return res.redirect(302, `/login.html?error=1${redirect ? `&redirect=${encodeURIComponent(redirect)}` : ''}`);
});

app.post('/logout', (req, res) => {
  res.setHeader('Set-Cookie', `${SESSION_COOKIE}=; Path=/; Max-Age=0`);
  res.redirect(302, '/');
});

// Guard the /secure area — bounce unauthenticated visitors to the login page.
app.use('/secure', (req, res, next) => {
  if (isAuthed(req)) return next();
  const redirect = req.originalUrl;
  return res.redirect(302, `/login.html?redirect=${encodeURIComponent(redirect)}`);
});

// --- Tier C: variant mutation ------------------------------------------------
// Any *.html under /c-replay can be requested with ?variant=1..7 to receive a
// structurally mutated form of the same logical page.
app.get(/^\/c-replay\/.*\.html$/, (req, res, next) => {
  const variant = parseInt(req.query.variant, 10);
  const filePath = path.join(PUBLIC, req.path);
  if (!filePath.startsWith(PUBLIC) || !fs.existsSync(filePath)) return next();
  let html = fs.readFileSync(filePath, 'utf8');
  if (variant >= 1 && variant <= 7) html = applyVariant(html, variant);
  res.type('html').send(html);
});

// --- Tier D: accounts API (multiple pagination flavours) ---------------------
function paginate(list, page, size) {
  const start = (page - 1) * size;
  return list.slice(start, start + size);
}

// page_numbers / next_button style: ?page=N&size=M
app.get('/api/accounts', (req, res) => {
  const size = Math.min(parseInt(req.query.size, 10) || 25, 200);
  const page = Math.max(parseInt(req.query.page, 10) || 1, 1);
  const totalPages = Math.ceil(ACCOUNTS.length / size);
  res.json({
    page,
    size,
    total: ACCOUNTS.length,
    totalPages,
    rows: paginate(ACCOUNTS, page, size),
  });
});

// cursor style: ?after=<id>&limit=M  -> returns nextCursor
app.get('/api/accounts/cursor', (req, res) => {
  const limit = Math.min(parseInt(req.query.limit, 10) || 25, 200);
  const after = parseInt(req.query.after, 10) || 0;
  const rows = ACCOUNTS.filter((a) => a.id > after).slice(0, limit);
  const nextCursor = rows.length === limit ? rows[rows.length - 1].id : null;
  res.json({ rows, nextCursor, total: ACCOUNTS.length });
});

// --- Tier G: failure & recovery endpoints ------------------------------------
// These exist so the agent meets genuine dead ends and must TERMINATE rather
// than loop — looping is what burns tokens in production.

app.get('/api/error/500', (_req, res) => {
  res.status(500).send('<h1>500 — Internal Server Error</h1><p>The service is temporarily unavailable.</p>');
});

app.get('/api/error/429', (_req, res) => {
  res.status(429).set('Retry-After', '120')
     .send('<h1>429 — Too Many Requests</h1><p>Rate limit exceeded. Retry after 120 seconds.</p>');
});

// Short-lived session used only by the session-timeout scenario. The cookie is
// issued with a few seconds of life so a multi-step flow expires mid-way.
const TIMEOUT_COOKIE = 'th_shortsession';
const TIMEOUT_TTL_SECONDS = 12;

app.post('/timeout-login', (_req, res) => {
  res.setHeader('Set-Cookie',
    `${TIMEOUT_COOKIE}=active; Path=/; Max-Age=${TIMEOUT_TTL_SECONDS}; SameSite=Lax`);
  res.redirect(302, '/g-failure/session-timeout-step2.html');
});

// Steps 2+ of the timeout flow are gated; once the short cookie lapses the
// agent is bounced back to a re-authentication page mid-flow.
app.get('/g-failure/session-timeout-step:step.html', (req, res, next) => {
  if (req.params.step === '2' && req.cookies[TIMEOUT_COOKIE] !== 'active') {
    return res.redirect(302, '/g-failure/session-expired.html');
  }
  return next();
});

// --- static ------------------------------------------------------------------
app.use(express.static(PUBLIC, { extensions: ['html'] }));

app.listen(PORT, () => {
  console.log(`\nUI-Agent Test Harness running:  http://localhost:${PORT}\n`);
  console.log(`  Catalog        : http://localhost:${PORT}/`);
  console.log(`  Login fixtures : ${FIXTURE_EMAIL} / ${FIXTURE_PASSWORD}\n`);
});

// Alternate origin — serves the same static files so the cross-origin-iframe
// scenario embeds a genuinely different origin (localhost:4600) offline.
const alt = express();
alt.use(express.static(PUBLIC, { extensions: ['html'] }));
alt.listen(ALT_PORT, () => {
  console.log(`  Alt origin     : http://localhost:${ALT_PORT} (cross-origin iframe source)\n`);
});
