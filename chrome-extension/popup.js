const $ = id => document.getElementById(id);

let activeTabId = null;
let visibleCandidates = [];

function showMessage(message, isError = false) {
  const element = $("message");
  element.textContent = message;
  element.classList.toggle("error", isError);
}

function formatAge(timestamp) {
  const seconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
  if (seconds < 2) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ago`;
}

function labelForType(type) {
  return {
    hls: "HLS",
    dash: "DASH",
    progressive: "VIDEO"
  }[type] || "STREAM";
}

async function currentTabId() {
  const tabs = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  return tabs.length && typeof tabs[0].id === "number" ? tabs[0].id : null;
}

function renderCandidates(candidates) {
  const container = $("candidates");
  container.replaceChildren();
  visibleCandidates = candidates.filter(candidate => candidate.tabId === activeTabId);

  if (!visibleCandidates.length) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = "No playable stream detected on this tab yet. Start the video, wait a moment, then refresh.";
    container.append(empty);
    return;
  }

  visibleCandidates.forEach((candidate, index) => {
    const card = document.createElement("article");
    card.className = "candidate";

    const top = document.createElement("div");
    top.className = "candidate-top";
    const type = document.createElement("span");
    type.className = "candidate-type";
    type.textContent = labelForType(candidate.type);
    const age = document.createElement("span");
    age.className = "candidate-age";
    age.textContent = formatAge(candidate.seenAt);
    top.append(type, age);

    const url = document.createElement("div");
    url.className = "candidate-url";
    url.textContent = candidate.url;
    url.title = candidate.url;

    const bottom = document.createElement("div");
    bottom.className = "candidate-bottom";
    const meta = document.createElement("span");
    meta.className = "candidate-meta";
    const isYouTube = /(?:youtube\.com|googlevideo\.com)$/i.test(new URL(candidate.url).hostname);
    meta.textContent = isYouTube
      ? `${Object.keys(candidate.headers || {}).length} header(s) · YouTube may separate audio/video`
      : `${Object.keys(candidate.headers || {}).length} captured header(s)`;
    const play = document.createElement("button");
    play.type = "button";
    play.textContent = "Play on projector";
    play.addEventListener("click", () => playCandidate(index, play));
    bottom.append(meta, play);

    card.append(top, url, bottom);
    container.append(card);
  });
}

async function refreshCandidates() {
  try {
    const data = await chrome.storage.session.get({ candidates: [] });
    renderCandidates(Array.isArray(data.candidates) ? data.candidates : []);
  } catch (error) {
    showMessage(error.message || String(error), true);
  }
}

async function saveProjectorUrl() {
  const value = $("projectorUrl").value.trim().replace(/\/+$/, "");
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") throw new Error("Use an HTTP or HTTPS projector URL.");
    await chrome.storage.local.set({ projectorUrl: value });
    $("projectorUrl").value = value;
    showMessage("Projector address saved.");
  } catch (error) {
    showMessage(error.message || "Enter a valid projector URL.", true);
  }
}

async function playCandidate(index, button) {
  const candidate = visibleCandidates[index];
  if (!candidate) return;

  const projectorUrl = $("projectorUrl").value.trim().replace(/\/+$/, "");
  if (!projectorUrl) {
    showMessage("Save the projector URL first.", true);
    $("projectorUrl").focus();
    return;
  }

  try {
    const parsed = new URL(projectorUrl);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") throw new Error("Use an HTTP or HTTPS projector URL.");
    button.disabled = true;
    showMessage("Sending the fresh stream URL…");
    const response = await fetch(`${projectorUrl}/play`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        url: candidate.url,
        type: candidate.type,
        headers: candidate.headers || {}
      })
    });
    const text = await response.text();
    if (!response.ok) throw new Error(text || response.statusText);
    showMessage(`Sent ${labelForType(candidate.type)} to the projector.`);
  } catch (error) {
    showMessage(error.message || String(error), true);
  } finally {
    button.disabled = false;
  }
}

async function clearCandidates() {
  await chrome.storage.session.set({ candidates: [] });
  showMessage("Detected streams cleared.");
  refreshCandidates();
}

async function initialize() {
  const settings = await chrome.storage.local.get({ projectorUrl: "" });
  $("projectorUrl").value = settings.projectorUrl;
  activeTabId = await currentTabId();
  await refreshCandidates();
}

$("saveButton").addEventListener("click", saveProjectorUrl);
$("refreshButton").addEventListener("click", refreshCandidates);
$("clearButton").addEventListener("click", clearCandidates);
chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName === "session" && changes.candidates) refreshCandidates();
});

initialize().catch(error => showMessage(error.message || String(error), true));
setInterval(refreshCandidates, 1500);
