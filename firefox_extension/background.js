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

// Feedback for the right-click menu, which has no popup: a short badge on the
// toolbar button, with the message as its tooltip.
const DEFAULT_TITLE = "Send this video to the TV (S-Tube Drop)";
let badgeTimer;
function flash(text, color, msg) {
  browser.action.setBadgeText({ text });
  browser.action.setBadgeBackgroundColor({ color });
  browser.action.setTitle({ title: msg });
  clearTimeout(badgeTimer);
  badgeTimer = setTimeout(() => {
    browser.action.setBadgeText({ text: "" });
    browser.action.setTitle({ title: DEFAULT_TITLE });
  }, 6000);
}

async function showPreferences(warning) {
  await browser.storage.local.set({ warning });
  browser.runtime.openOptionsPage();
}

async function handleSend(url) {
  if (!isVideoUrl(url)) return flash("!", "#d33", "That link isn't a video, short or playlist.");
  const r = await sendDrop(await loadCfg(), url);
  switch (r.kind) {
    case "ok":
      await browser.storage.local.remove("warning");
      return flash("✓", "#2a9d4b", r.msg);
    case "config": // not configured yet
    case "auth":   // wrong password
      return showPreferences(r.msg);
    default:       // TV unreachable etc.
      return flash("!", "#d33", r.msg);
  }
}

browser.contextMenus.onClicked.addListener((info) => {
  if (info.menuItemId === "drop-link") handleSend(info.linkUrl);
});

browser.runtime.onMessage.addListener((msg) => {
  if (msg && msg.type === "test") return loadCfg().then(testConnection);
});
