// Shared by background script and popup.
const DEFAULTS = { ip: "", port: 8765, token: "changeme" };

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

function buildDropUrl(cfg, videoUrl) {
  const q = new URLSearchParams({ url: videoUrl || "https://youtu.be/VIDEO_ID", t: cfg.token });
  return `http://${cfg.ip || "<tv-ip>"}:${cfg.port}/?${q}`;
}

// Single GET with a timeout.
async function fetchOnce(url) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 6000);
  try { return await fetch(url, { signal: ctl.signal }); }
  finally { clearTimeout(timer); }
}

// kind: ok | config | auth | unreachable | other
async function sendDrop(cfg, videoUrl) {
  if (!validHost(cfg.ip)) return { ok: false, kind: "config", msg: "Enter the TV's IP address to get started." };
  if (!validPort(cfg.port)) return { ok: false, kind: "config", msg: "The port is invalid (1–65535)." };
  try {
    const r = await fetchOnce(buildDropUrl(cfg, toHttps(videoUrl)));
    if (r.ok) return { ok: true, kind: "ok", msg: "Sent to TV ▶" };
    if (r.status === 403 && (await r.text()) === "bad url") return { ok: false, kind: "other", msg: "The TV did not accept this link as a YouTube video link." };
    if (r.status === 403) return { ok: false, kind: "auth", msg: "The TV rejected the password. Make sure it matches the one in the S-Tube Drop app. Repeated failures get this computer blocked for 4 minutes." };
    return { ok: false, kind: "other", msg: `The TV answered with HTTP ${r.status}.` };
  } catch (e) {
    return { ok: false, kind: "unreachable", msg: `TV not reachable at ${cfg.ip}:${cfg.port}. Is it on, and is the S-Tube Drop app running (latest version)?` };
  }
}

// Harmless check used by the Preferences "Test connection" button: sends a valid
// password with an invalid link, so the TV never starts anything.
async function testConnection(cfg) {
  if (!validHost(cfg.ip)) return { ok: false, msg: "Enter the TV's IP address first." };
  if (!validPort(cfg.port)) return { ok: false, msg: "The port is invalid (1–65535)." };
  try {
    const r = await fetchOnce(`http://${cfg.ip}:${cfg.port}/?` + new URLSearchParams({ url: "x", t: cfg.token }));
    const body = await r.text();
    if (r.status === 403 && body === "bad url") return { ok: true, msg: "✓ Connected – the TV is reachable and accepted the password." };
    if (r.status === 403) return { ok: false, msg: "✗ The TV rejected the password." };
    return { ok: false, msg: `The TV answered HTTP ${r.status}: ${body}` };
  } catch (e) {
    return { ok: false, msg: `✗ No answer from ${cfg.ip}:${cfg.port} (${e.name}: ${e.message}).` };
  }
}
