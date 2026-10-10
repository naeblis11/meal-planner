const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

// popup.js runs only in the extension (it touches chrome.* at load), so its text is checked.
const popup = fs.readFileSync(path.join(__dirname, "popup.js"), "utf8");

test("the send has no sign-in leftovers from the retired Python server", () => {
  // The desktop server has no /login route and never answers 401 to the extension (review, 2026-10-10).
  assert.doesNotMatch(popup, /\/login/);
  assert.doesNotMatch(popup, /401/);
  assert.doesNotMatch(popup, /credentials/);
});

test("the send posts to the import route and shows the app's own error for any refusal", () => {
  assert.match(popup, /fetch\(`\$\{APP_ORIGIN\}\/recipes\/import\/extension`/);
  assert.match(popup, /if \(!response\.ok \|\| !data\.ok\) \{\s*statusEl\.textContent = data\.error \|\|/);
  assert.match(popup, /chrome\.tabs\.create\(\{ url: `\$\{APP_ORIGIN\}\/recipes\/import\/review` \}\)/);
});
