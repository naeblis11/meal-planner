// The app address comes from the options page (settings.js); see getOrigin().

const statusEl = document.getElementById("status");
const previewEl = document.getElementById("preview");
const previewImageEl = document.getElementById("preview-image");
const previewTitleEl = document.getElementById("preview-title");
const previewMetaEl = document.getElementById("preview-meta");
const sendButton = document.getElementById("send-button");

let extractedRecipe = null;

async function loadExtraction() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab || !tab.id) {
    statusEl.textContent = "No active tab.";
    return;
  }

  try {
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["extract.js"] });
    const [{ result }] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => (window.__extractRecipeFromPage ? window.__extractRecipeFromPage() : null),
    });

    if (!result) {
      statusEl.textContent = "No recipe found on this page.";
      return;
    }

    extractedRecipe = result;
    statusEl.textContent = "";
    previewTitleEl.textContent = result.name;
    previewMetaEl.textContent = `${result.ingredients.length} ingredients, ${result.steps.length} steps`;
    if (result.image_url) {
      previewImageEl.src = result.image_url;
      previewImageEl.hidden = false;
    }
    previewEl.classList.add("visible");
  } catch (err) {
    statusEl.textContent = "Couldn't read this page (" + err.message + ").";
  }
}

sendButton.addEventListener("click", async () => {
  if (!extractedRecipe) return;
  sendButton.disabled = true;
  statusEl.textContent = "Sending…";
  statusEl.className = "message";

  const APP_ORIGIN = await getOrigin();
  if (!(await hasPermissionFor(APP_ORIGIN))) {
    statusEl.innerHTML = "";
    statusEl.append("This extension isn't allowed to talk to " + APP_ORIGIN + " yet. ");
    const link = document.createElement("a");
    link.href = "#";
    link.textContent = "Open settings to allow it.";
    link.addEventListener("click", (e) => { e.preventDefault(); chrome.runtime.openOptionsPage(); });
    statusEl.append(link);
    statusEl.className = "message error";
    sendButton.disabled = false;
    return;
  }

  try {
    const response = await fetch(`${APP_ORIGIN}/recipes/import/extension`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      // Send the app's session cookie so the household login gate lets us in.
      credentials: "include",
      body: JSON.stringify(extractedRecipe),
    });
    const data = await response.json();

    if (response.status === 401) {
      statusEl.textContent = data.error || "Sign in to the Meal Planning app first.";
      statusEl.className = "message error";
      sendButton.disabled = false;
      chrome.tabs.create({ url: `${APP_ORIGIN}/login` });
      return;
    }
    if (!response.ok || !data.ok) {
      statusEl.textContent = data.error || "The Meal Planning app rejected this recipe.";
      statusEl.className = "message error";
      sendButton.disabled = false;
      return;
    }

    chrome.tabs.create({ url: `${APP_ORIGIN}/recipes/import/review` });
    statusEl.textContent = data.warning
      ? `Sent — opening review… (${data.warning})`
      : "Sent — opening review…";
  } catch (err) {
    statusEl.textContent = `Couldn't reach the Meal Planning app at ${APP_ORIGIN} — is it running?`;
    statusEl.className = "message error";
    sendButton.disabled = false;
  }
});

document.getElementById("open-settings").addEventListener("click", (e) => {
  e.preventDefault();
  chrome.runtime.openOptionsPage();
});

loadExtraction();
