// Service worker: keeps the toolbar button in step with the current page. It can be stopped by
// the browser whenever it is idle, so the listeners are registered at the top level.
importScripts("shim.js", "common.js");

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
