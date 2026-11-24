// Shared by background script and popup.
// https: use TLS (self-signed certificate on the TV). certTrusted: the "ip:port" for which a
// request over https has succeeded, i.e. the user has accepted the certificate in the browser.
const DEFAULTS = { ip: "", port: 8765, token: "changeme", https: true, certTrusted: "", method: "POST" };

const loadCfg = () => browser.storage.local.get(DEFAULTS);

const validHost = (h) => /^[A-Za-z0-9.-]+$/.test(h || "");
const validPort = (p) => Number.isInteger(+p) && +p >= 1 && +p <= 65535;

function isYouTubeHost(h) {
  return h === "youtu.be" || h === "youtube.com" || h.endsWith(".youtube.com");
}

// True for pages/links that point at something playable.
function isVideoUrl(str) {
  let u;
  try { u = new URL(str); } catch { return false; }
  if (!/^https?:$/.test(u.protocol) || !isYouTubeHost(u.hostname)) return false;
  if (u.hostname === "youtu.be") return u.pathname.length > 1;
  const p = u.pathname;
  return (p === "/watch" && u.searchParams.has("v")) ||
         (p === "/playlist" && u.searchParams.has("list")) ||
         /^\/(shorts|live|embed)\/[^/]+/.test(p);
}

// The receiver only accepts https links.
const toHttps = (str) => str.replace(/^http:/, "https:");

const scheme = (cfg) => (cfg.https ? "https" : "http");
const trustKey = (cfg) => `${cfg.ip}:${cfg.port}`;
// HTTPS is on but the certificate of this ip:port hasn't been verified yet.
const needsTrust = (cfg) => cfg.https && cfg.certTrusted !== trustKey(cfg);

const TRUST_MSG = "Trust the TV's certificate first: click “Trust certificate”, on the browser's warning page click “Advanced” and then “Proceed to … (unsafe)”, then come back and press “Test connection”.";

// POST sends the link as the request body (no URL-escaping needed); GET puts it in the query.
const isPost = (cfg) => cfg.method !== "GET";

// The URL that is requested; for POST neither the link (body) nor the password (X-Token header) is in it.
function buildDropUrl(cfg, videoUrl) {
  const base = `${scheme(cfg)}://${cfg.ip || "<tv-ip>"}:${cfg.port}/`;
  if (isPost(cfg)) return base;
  return base + "?" + new URLSearchParams({ url: videoUrl || "https://youtu.be/VIDEO_ID", t: cfg.token });
}

// The whole request as text, for display.
function describeRequest(cfg, videoUrl) {
  const link = videoUrl || "https://youtu.be/VIDEO_ID";
  return isPost(cfg) ? `POST ${buildDropUrl(cfg, link)}\nX-Token: ${cfg.token}\n\nbody: ${link}` : `GET ${buildDropUrl(cfg, link)}`;
}

// Sends the link (or TEST_URL) to the receiver using the configured method.
function dropRequest(cfg, link) {
  const url = buildDropUrl(cfg, link);
  return isPost(cfg)
    ? fetchOnce(url, { method: "POST", headers: { "Content-Type": "text/plain; charset=utf-8", "X-Token": cfg.token }, body: link })
    : fetchOnce(url);
}

// "testurl" is a placeholder the receiver answers with a "it works" page (when the password
// is right) instead of starting a video. Used for the trust page and the connection test.
const TEST_URL = "testurl";
const trustUrl = (cfg) => `${scheme(cfg)}://${cfg.ip}:${cfg.port}/?` + new URLSearchParams({ url: TEST_URL, t: cfg.token });

// Single request with a timeout.
async function fetchOnce(url, opts = {}) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 6000);
  try { return await fetch(url, { ...opts, signal: ctl.signal }); }
  finally { clearTimeout(timer); }
}

// kind: ok | config | trust | auth | unreachable | other
async function sendDrop(cfg, videoUrl) {
  if (!validHost(cfg.ip)) return { ok: false, kind: "config", msg: "Enter the TV's IP address to get started." };
  if (!validPort(cfg.port)) return { ok: false, kind: "config", msg: "The port is invalid (1–65535)." };
  if (needsTrust(cfg)) return { ok: false, kind: "trust", msg: TRUST_MSG };
  try {
    const r = await dropRequest(cfg, toHttps(videoUrl));
    if (r.ok) return { ok: true, kind: "ok", msg: "Sent to TV ▶" };
    if (r.status === 403 && (await r.text()) === "bad url") return { ok: false, kind: "other", msg: "The TV did not accept this link as a YouTube video link." };
    if (r.status === 403) return { ok: false, kind: "auth", msg: "The TV rejected the password. Make sure it matches the one in the S-Tube Drop app. Repeated failures get this computer blocked for 4 minutes." };
    return { ok: false, kind: "other", msg: `The TV answered with HTTP ${r.status}.` };
  } catch (e) {
    return { ok: false, kind: "unreachable", msg: `TV not reachable at ${cfg.ip}:${cfg.port}. Is it on, and is the S-Tube Drop app running (latest version)?` + (cfg.https ? " If you reinstalled the receiver app, its certificate changed: trust it again in Preferences." : "") };
  }
}

// Check used by the Preferences "Test connection" button: asks the receiver to confirm the
// password with the placeholder link "testurl", so the TV never starts anything. A wrong
// password is refused. Over https a success also proves the certificate has been accepted.
async function testConnection(cfg) {
  if (!validHost(cfg.ip)) return { ok: false, msg: "Enter the TV's IP address first." };
  if (!validPort(cfg.port)) return { ok: false, msg: "The port is invalid (1–65535)." };
  try {
    const r = await dropRequest(cfg, TEST_URL);
    const body = await r.text();
    if (cfg.https) await browser.storage.local.set({ certTrusted: trustKey(cfg) }); // TLS worked
    if (r.ok) return { ok: true, msg: "✓ Connected – the TV is reachable and accepted the password." + (cfg.https ? " Certificate trusted." : "") };
    if (r.status === 403 && /^bad password/.test(body)) return { ok: false, msg: "✗ The TV rejected the password." };
    if (r.status === 403) return { ok: false, msg: "✗ The TV does not know the test link: update the S-Tube Drop receiver app." };
    return { ok: false, msg: `The TV answered HTTP ${r.status}: ${body}` };
  } catch (e) {
    if (cfg.https) return { ok: false, msg: `✗ No secure connection to ${cfg.ip}:${cfg.port}. If you haven't yet, click “Trust certificate” and proceed past the warning page (Advanced → Proceed); otherwise check that the TV is on and the receiver app is running.` };
    return { ok: false, msg: `✗ No answer from ${cfg.ip}:${cfg.port} (${e.name}: ${e.message}).` };
  }
}
