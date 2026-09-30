const originInput = document.getElementById("origin");
const messageEl = document.getElementById("message");

getOrigin().then((origin) => { originInput.value = origin; });

document.getElementById("save").addEventListener("click", async () => {
  messageEl.className = "message";
  let origin;
  try {
    origin = normalizeOrigin(originInput.value);
  } catch (err) {
    messageEl.textContent = err.message;
    messageEl.className = "message error";
    return;
  }

  // Chrome only lets the extension call hosts it has been granted; ask for
  // this one (the prompt is the user's own click, so it is allowed here).
  const granted = await chrome.permissions.request({ origins: [origin + "/*"] });
  if (!granted) {
    messageEl.textContent = "Chrome didn't grant access to " + origin + " — the address was not saved.";
    messageEl.className = "message error";
    return;
  }
  await chrome.storage.sync.set({ origin });
  originInput.value = origin;
  messageEl.textContent = "Saved. The extension will send recipes to " + origin + ".";
});
