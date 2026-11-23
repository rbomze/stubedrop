// Opens when the toolbar button is clicked: sends the current tab's video and
// shows the result right here (no OS notification needed).
const statusEl = document.getElementById("status");
const show = (msg, cls = "") => { statusEl.textContent = msg; statusEl.className = cls; };

async function run(url) {
  if (!isVideoUrl(url)) return show("Open a video, short or playlist first.", "err");
  show("Sending…");
  const r = await sendDrop(await loadCfg(), url);
  switch (r.kind) {
    case "ok":
      await browser.storage.local.remove("warning");
      show("✓ Sent to TV", "ok");
      setTimeout(() => window.close(), 1500);
      break;
    case "config": // not configured yet
    case "trust":  // certificate not accepted yet
    case "auth":   // wrong password
      await browser.storage.local.set({ warning: r.msg });
      browser.runtime.openOptionsPage();
      window.close();
      break;
    default:       // TV unreachable etc.
      show("⚠ " + r.msg, "err");
  }
}

browser.tabs.query({ active: true, currentWindow: true }).then(([tab]) => run(tab && tab.url));
