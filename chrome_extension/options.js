const $ = (id) => document.getElementById(id);
let cfg;

const TRUST_WARNING = "The TV's certificate isn't trusted yet. Click “Trust certificate…”, on the warning page click “Advanced” and then “Proceed to … (unsafe)”, then come back and press “Test connection”.";

// A finished address: IPv4 with four parts, or a name with a letter in it (not "1" or "192.1").
const looksComplete = (h) => /^\d{1,3}(\.\d{1,3}){3}$/.test(h || "") || (validHost(h) && /[A-Za-z]/.test(h));

function render() {
  $("ip").classList.toggle("bad", !!cfg.ip && !validHost(cfg.ip));
  $("port").classList.toggle("bad", !validPort(cfg.port));
  $("url").textContent = describeRequest(cfg, "");
  $("trustBox").hidden = !cfg.https;
  const st = $("certStatus");
  if (!cfg.https) { st.textContent = ""; return; }
  if (!looksComplete(cfg.ip)) { st.textContent = "Enter the TV's IP address first."; st.className = ""; }
  else if (needsTrust(cfg)) { st.textContent = `✗ Certificate of ${trustKey(cfg)} not trusted yet`; st.className = "err"; }
  else { st.textContent = `✓ Certificate of ${trustKey(cfg)} trusted`; st.className = "ok"; }
  $("trust").disabled = !looksComplete(cfg.ip) || !validPort(cfg.port);
}

// Focus moves to the relevant field only when the page itself raises the warning
// (e.g. opened because of a wrong password), never while the user is typing.
function showWarning(msg, focus = false) {
  $("warn").hidden = !msg;
  $("warn").textContent = msg ? "⚠ " + msg : "";
  if (msg && focus) {
    const f = /certificate/i.test(msg) ? $("trust") : /password/i.test(msg) ? $("token") : $("ip");
    f.focus();
    if (f.select) f.select();
  }
}

// A saved setting changed: if it makes the certificate untrusted again, say so.
function afterEdit() {
  browser.storage.local.remove("warning");
  showWarning(looksComplete(cfg.ip) && needsTrust(cfg) ? TRUST_WARNING : "");
  render();
}

async function verify(showResult) {
  $("testResult").textContent = "Testing…";
  $("testResult").className = "";
  const r = await testConnection(await loadCfg());
  cfg = await loadCfg();
  $("testResult").textContent = r.msg;
  $("testResult").className = r.ok ? "ok" : "err";
  if (!needsTrust(cfg)) showWarning("");
  render();
}

async function init() {
  cfg = await loadCfg();
  $("ip").value = cfg.ip; $("port").value = cfg.port; $("token").value = cfg.token; $("https").checked = cfg.https; $("method").value = isPost(cfg) ? "POST" : "GET";
  render();
  const stored = (await browser.storage.local.get({ warning: "" })).warning;
  showWarning(stored || (looksComplete(cfg.ip) && needsTrust(cfg) ? TRUST_WARNING : ""), true);

  for (const key of ["ip", "port", "token"]) {
    $(key).addEventListener("input", () => {
      cfg[key] = key === "port" ? +$(key).value : $(key).value.trim();
      browser.storage.local.set({ [key]: cfg[key] });
      // no interruptions while typing: just refresh the display; the warning follows when the field is left
      browser.storage.local.remove("warning");
      showWarning("");
      render();
    });
    $(key).addEventListener("change", afterEdit);
  }
  $("https").addEventListener("change", () => {
    cfg.https = $("https").checked;
    browser.storage.local.set({ https: cfg.https });
    afterEdit();
  });
  $("method").addEventListener("change", () => {
    cfg.method = $("method").value;
    browser.storage.local.set({ method: cfg.method });
    afterEdit();
  });
  $("trust").addEventListener("click", () => browser.tabs.create({ url: trustUrl(cfg) }));
  $("test").addEventListener("click", () => verify(true));

  // Back from the certificate tab: check automatically.
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && looksComplete(cfg.ip) && needsTrust(cfg)) verify(false);
  });

  $("ver").textContent = "S-Tube Drop v" + browser.runtime.getManifest().version;
  // page already open when a new warning arrives
  browser.storage.onChanged.addListener(async (ch) => {
    if (ch.warning) showWarning(ch.warning.newValue || "", true);
    if (ch.certTrusted) { cfg = await loadCfg(); render(); }
  });
}
init();
