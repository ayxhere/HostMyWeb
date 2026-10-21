/*
 * pentagi-remote manager — tiny authenticated control plane for PentAGI.
 *
 * SECURITY MODEL (read before deploying):
 *  - Binds to MANAGER_BIND (default 127.0.0.1) ONLY. Reach it from the phone
 *    through an SSH tunnel, Tailscale, or a Cloudflare Tunnel — never expose
 *    this port publicly. If you must bind beyond localhost, put real auth/TLS
 *    in front of it as well; the Bearer token alone is not a public API.
 *  - Every /v1/* request needs `Authorization: Bearer <MANAGER_TOKEN>`
 *    (constant-time compared). There are no other credentials and no sessions.
 *  - Commands run ONLY via child_process.execFile('docker', [...]) with
 *    shell:false and compile-time-constant argv arrays. Request data is never
 *    interpolated into a command; the single variable argument (log tail) is
 *    validated as an integer in 1..500 before use. POST bodies are ignored.
 *  - Log output is redacted with the actual secret values from .env before
 *    it leaves this process, and capped at 200 KB.
 */
'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFile } = require('child_process');

const MANAGER_TOKEN = process.env.MANAGER_TOKEN || '';
const MANAGER_PORT = parseInt(process.env.MANAGER_PORT || '8787', 10);
const MANAGER_BIND = process.env.MANAGER_BIND || '127.0.0.1';
const PENTAGI_DIR = process.env.PENTAGI_DIR || path.resolve(__dirname, '..');
const PENTAGI_URL = process.env.PENTAGI_URL || 'https://127.0.0.1:8443';

if (MANAGER_TOKEN.length < 24) {
  console.error('FATAL: MANAGER_TOKEN must be set and at least 24 characters long.');
  process.exit(1);
}
if (!Number.isInteger(MANAGER_PORT) || MANAGER_PORT < 1 || MANAGER_PORT > 65535) {
  console.error('FATAL: MANAGER_PORT is invalid.');
  process.exit(1);
}

// Official PentAGI env key names (allowlist — config-status reports only these).
const KNOWN_ENV_KEYS = [
  'OPEN_AI_KEY', 'ANTHROPIC_API_KEY', 'GEMINI_API_KEY', 'DEEPSEEK_API_KEY',
  'BEDROCK_ACCESS_KEY_ID', 'BEDROCK_SECRET_ACCESS_KEY', 'BEDROCK_REGION',
  'BEDROCK_DEFAULT_MODEL', 'DEEPSEEK_SERVER_URL', 'DEEPSEEK_MODEL',
  'OLLAMA_SERVER_URL', 'OLLAMA_SERVER_MODEL',
  'LLM_SERVER_URL', 'LLM_SERVER_KEY', 'LLM_SERVER_MODEL',
  'DATABASE_URL', 'COOKIE_SIGNING_SALT', 'PUBLIC_URL', 'CORS_ORIGINS',
  'PENTAGI_POSTGRES_USER', 'PENTAGI_POSTGRES_PASSWORD', 'PENTAGI_POSTGRES_DB',
  'SERVER_PORT', 'SERVER_USE_SSL', 'PENTAGI_LISTEN_IP', 'PENTAGI_LISTEN_PORT',
  'INSTALL_ID', 'SCRAPER_PUBLIC_URL'
];

function readDotEnv() {
  const out = {};
  try {
    const text = fs.readFileSync(path.join(PENTAGI_DIR, '.env'), 'utf8');
    for (const line of text.split(/\r?\n/)) {
      const t = line.trim();
      if (!t || t.startsWith('#')) continue;
      const eq = t.indexOf('=');
      if (eq <= 0) continue;
      let v = t.slice(eq + 1).trim();
      if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
      out[t.slice(0, eq).trim()] = v;
    }
  } catch (_) { /* missing .env -> empty */ }
  return out;
}

function secretValues() {
  const env = readDotEnv();
  return Object.values(env).filter((v) => typeof v === 'string' && v.length >= 6);
}

function redact(text) {
  let out = String(text);
  for (const s of secretValues()) out = out.split(s).join('•••');
  // Generic backstop for key-like assignments in log lines.
  out = out.replace(/(api[_-]?key|token|secret|password|authorization)(["'\s:=]+)([^\s"',;]{8,})/gi, '$1$2•••');
  return out;
}

function runDocker(args, timeoutMs = 60000) {
  return new Promise((resolve) => {
    execFile('docker', args, { cwd: PENTAGI_DIR, shell: false, timeout: timeoutMs, maxBuffer: 2 * 1024 * 1024 },
      (err, stdout, stderr) => {
        resolve({ ok: !err, code: err && typeof err.code === 'number' ? err.code : 0, stdout: stdout || '', stderr: stderr || '', error: err ? String(err.message || err) : '' });
      });
  });
}

function tokenOk(req) {
  const h = req.headers.authorization || '';
  const given = h.startsWith('Bearer ') ? h.slice(7) : '';
  if (!given) return false;
  const a = Buffer.from(given);
  const b = Buffer.from(MANAGER_TOKEN);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json', 'X-Content-Type-Options': 'nosniff' });
  res.end(body);
}

function probePentagi() {
  return new Promise((resolve) => {
    let url;
    try { url = new URL(PENTAGI_URL); } catch (_) { resolve(false); return; }
    const lib = url.protocol === 'https:' ? https : http;
    // Server-side localhost probe only: PentAGI's default cert is self-signed,
    // so certificate verification is disabled HERE (manager -> localhost) and
    // nowhere else. The phone app verifies TLS normally.
    const req = lib.request(url, { method: 'GET', timeout: 4000, rejectUnauthorized: false }, (res) => {
      res.resume();
      resolve(true); // any HTTP response = reachable
    });
    req.on('timeout', () => { req.destroy(); resolve(false); });
    req.on('error', () => resolve(false));
    req.end();
  });
}

async function handle(req, res) {
  const url = new URL(req.url, 'http://manager.local');
  const p = url.pathname;

  if (!p.startsWith('/v1/')) { sendJson(res, 404, { error: 'not found' }); return; }
  if (!tokenOk(req)) { sendJson(res, 401, { error: 'unauthorized' }); return; }

  if (req.method === 'GET' && p === '/v1/health') {
    const docker = await runDocker(['version', '--format', '{{.Server.Version}}'], 8000);
    const composeFile = fs.existsSync(path.join(PENTAGI_DIR, 'docker-compose.yml'));
    sendJson(res, 200, { ok: true, docker: docker.ok, compose: composeFile });
    return;
  }

  if (req.method === 'GET' && p === '/v1/status') {
    const ps = await runDocker(['compose', 'ps', '--format', 'json'], 15000);
    const services = {};
    let composeState = 'unknown';
    if (ps.ok) {
      for (const line of ps.stdout.split(/\r?\n/)) {
        if (!line.trim()) continue;
        try {
          const o = JSON.parse(line);
          const name = o.Service || o.Name || 'unknown';
          services[name] = o.State || o.Status || 'unknown';
        } catch (_) { /* skip non-JSON line */ }
      }
      const states = Object.values(services);
      composeState = states.length === 0 ? 'stopped' : states.every((s) => s === 'running') ? 'running' : 'partial';
    } else {
      composeState = 'error';
    }
    const reachable = await probePentagi();
    sendJson(res, 200, { composeState, services, pentagiReachable: reachable });
    return;
  }

  if (req.method === 'POST' && p.startsWith('/v1/actions/')) {
    const action = p.slice('/v1/actions/'.length);
    // Fixed argv per action — constants, never derived from the request beyond
    // this exact-match allowlist. The request body is deliberately never read.
    const argvByAction = {
      start: ['compose', 'up', '-d'],
      stop: ['compose', 'stop'],
      restart: ['compose', 'restart']
    };
    const argv = argvByAction[action];
    if (!argv) { sendJson(res, 400, { error: 'unknown action' }); return; }
    const r = await runDocker(argv, 120000);
    if (!r.ok) { sendJson(res, 502, { error: 'docker compose failed', detail: redact(r.stderr).slice(0, 500) }); return; }
    sendJson(res, 200, { ok: true, action });
    return;
  }

  if (req.method === 'GET' && p === '/v1/logs') {
    const rawTail = url.searchParams.get('tail');
    const tail = rawTail === null ? 100 : Number(rawTail);
    if (!Number.isInteger(tail) || tail < 1 || tail > 500) {
      sendJson(res, 400, { error: 'tail must be an integer between 1 and 500' });
      return;
    }
    const r = await runDocker(['compose', 'logs', '--tail', String(tail), '--no-color', 'pentagi', 'pgvector'], 30000);
    const text = redact((r.stdout || '') + (r.stderr || '')).slice(0, 200 * 1024);
    sendJson(res, 200, { logs: text });
    return;
  }

  if (req.method === 'GET' && p === '/v1/config-status') {
    const env = readDotEnv();
    const envKeysSet = KNOWN_ENV_KEYS.filter((k) => typeof env[k] === 'string' && env[k].trim() !== '');
    sendJson(res, 200, { envKeysSet }); // names only — values never leave this process
    return;
  }

  sendJson(res, 404, { error: 'not found' });
}

const server = http.createServer((req, res) => {
  // Drain (and ignore) any request body so sockets stay clean; bodies carry no meaning.
  req.resume();
  handle(req, res).catch(() => {
    if (!res.headersSent) sendJson(res, 500, { error: 'internal error' });
  });
});

server.listen(MANAGER_PORT, MANAGER_BIND, () => {
  console.log(`pentagi-manager listening on ${MANAGER_BIND}:${MANAGER_PORT} (PENTAGI_DIR=${PENTAGI_DIR})`);
});
