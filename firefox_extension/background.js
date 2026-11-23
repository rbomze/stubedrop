// Toolbar button: enabled only on pages that have a video (watch, shorts, live,
// playlist, youtu.be/<id>); greyed out on the YouTube home page and elsewhere.
// If a tab's URL is unknown, stay enabled (fail open).
function updateIcon(tabId, url) {
  if (!url || isVideoUrl(url)) browser.action.enable(tabId);
  else browser.action.disable(tabId);
}

browser.tabs.onUpdated.addListener((id, change, tab) => updateIcon(id, tab.url));
browser.tabs.onActivated.addListener(async ({ tabId }) => updateIcon(tabId, (await browser.tabs.get(tabId)).url));
browser.tabs.query({}).then((tabs) => tabs.forEach((t) => updateIcon(t.id, t.url)));

browser.runtime.onInstalled.addListener(() => {
  browser.contextMenus.create({
    id: "drop-link",
    title: "Send to TV (S-Tube Drop)",
    contexts: ["link"],
    targetUrlPatterns: ["*://*.youtube.com/*", "*://youtu.be/*"],
    documentUrlPatterns: ["*://*.youtube.com/*"],
  });
});

function notify(msg) {
  browser.notifications.create({ type: "basic", title: "S-Tube Drop", message: msg, iconUrl: "icons/icon.svg" });
}

async function showPreferences(warning) {
  await browser.storage.local.set({ warning });
  browser.runtime.openOptionsPage();
}

async function handleSend(url) {
  if (!isVideoUrl(url)) return notify("Open a video, short or playlist on YouTube first.");
  const r = await sendDrop(await loadCfg(), url);
  switch (r.kind) {
    case "ok":
      await browser.storage.local.remove("warning");
      return notify(r.msg);
    case "config": // not configured yet
    case "auth":   // wrong password
      return showPreferences(r.msg);
    default:       // TV unreachable etc.
      return notify("⚠ " + r.msg);
  }
}

browser.action.onClicked.addListener((tab) => handleSend(tab.url));
browser.contextMenus.onClicked.addListener((info) => {
  if (info.menuItemId === "drop-link") handleSend(info.linkUrl);
});

browser.runtime.onMessage.addListener((msg) => {
  if (msg && msg.type === "test") return loadCfg().then(testConnection);
});
