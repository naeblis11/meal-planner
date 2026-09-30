// Where the Meal Planner is. Stored in chrome.storage.sync so it follows the
// Chrome profile; defaults to the app running on this PC. Shared by the
// popup (reads) and the options page (writes).
const DEFAULT_ORIGIN = "http://127.0.0.1:5000";

function normalizeOrigin(text) {
  const trimmed = (text || "").trim();
  if (!trimmed) return DEFAULT_ORIGIN;
  const url = new URL(trimmed.includes("://") ? trimmed : "http://" + trimmed);
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error("The address must start with http:// or https://");
  }
  return url.origin;
}

async function getOrigin() {
  const stored = await chrome.storage.sync.get({ origin: DEFAULT_ORIGIN });
  return stored.origin || DEFAULT_ORIGIN;
}

async function hasPermissionFor(origin) {
  return chrome.permissions.contains({ origins: [origin + "/*"] });
}
