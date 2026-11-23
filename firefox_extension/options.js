const $ = (id) => document.getElementById(id);
let cfg;

function render() {
  $("ip").classList.toggle("bad", !!cfg.ip && !validHost(cfg.ip));
  $("port").classList.toggle("bad", !validPort(cfg.port));
  $("url").textContent = buildDropUrl(cfg, "");
}

function showWarning(msg) {
  $("warn").hidden = !msg;
  $("warn").textContent = msg ? "⚠ " + msg : "";
  if (msg) {
    const f = /password/i.test(msg) ? $("token") : $("ip");
    f.focus(); f.select();
  }
}

async function init() {
  cfg = await loadCfg();
  $("ip").value = cfg.ip; $("port").value = cfg.port; $("token").value = cfg.token;
  render();
  showWarning((await browser.storage.local.get({ warning: "" })).warning);

  for (const key of ["ip", "port", "token"]) {
    $(key).addEventListener("input", () => {
      cfg[key] = key === "port" ? +$(key).value : $(key).value.trim();
      browser.storage.local.set({ [key]: cfg[key] });
      browser.storage.local.remove("warning"); // user is fixing it
      showWarning("");
      render();
    });
  }
  $("ver").textContent = "S-Tube Drop v" + browser.runtime.getManifest().version;
  $("test").addEventListener("click", async () => {
    $("testResult").textContent = "Testing…";
    const r = await browser.runtime.sendMessage({ type: "test" });
    $("testResult").textContent = r.msg;
    $("testResult").className = r.ok ? "ok" : "err";
  });
  // page already open when a new warning arrives
  browser.storage.onChanged.addListener((ch) => { if (ch.warning) showWarning(ch.warning.newValue || ""); });
}
init();
