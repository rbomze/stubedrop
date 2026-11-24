// The shared code is written against the promise-based `browser.*` API (Firefox).
// Chrome exposes the same promise-based API as `chrome.*` (Manifest V3).
if (typeof globalThis.browser === "undefined") globalThis.browser = globalThis.chrome;
