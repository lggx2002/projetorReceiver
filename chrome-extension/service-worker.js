const MAX_CANDIDATES = 40;
const MAX_PENDING_REQUESTS = 400;
const candidatesReady = chrome.storage.session.get({ candidates: [] }).then(({ candidates }) => {
  return Array.isArray(candidates) ? candidates : [];
});

const pendingRequests = new Map();
let candidates = [];
let persistQueue = candidatesReady.then(saved => {
  candidates = saved;
});

const REQUEST_FILTER = {
  urls: ["http://*/*", "https://*/*"],
  types: ["media", "xmlhttprequest", "other"]
};

const BLOCKED_HEADERS = new Set([
  "accept-encoding",
  "connection",
  "content-length",
  "host",
  "keep-alive",
  "proxy-connection",
  "proxy-authenticate",
  "proxy-authorization",
  "range",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade"
]);

function pathPart(url) {
  try {
    return new URL(url).pathname.toLowerCase();
  } catch (_error) {
    return url.toLowerCase().split(/[?#]/, 1)[0];
  }
}

function headerValue(headers, name) {
  const wanted = name.toLowerCase();
  const header = (headers || []).find(item => item.name.toLowerCase() === wanted);
  return header && typeof header.value === "string" ? header.value : "";
}

function queryMimeType(url) {
  try {
    const parsed = new URL(url);
    return (parsed.searchParams.get("mime") || parsed.searchParams.get("type") || "")
      .toLowerCase()
      .split(";", 1)[0]
      .trim();
  } catch (_error) {
    return "";
  }
}

function inferType(url, contentType = "") {
  const path = pathPart(url);
  const mime = contentType.toLowerCase().split(";", 1)[0].trim() || queryMimeType(url);

  if (path.includes(".m3u8") || mime.includes("mpegurl") || mime.includes("vnd.apple.mpegurl")) {
    return "hls";
  }
  if (path.includes(".mpd") || mime.includes("dash+xml")) {
    return "dash";
  }
  if (
    /\.(mp4|m4v|mov|webm)(?:$|[?#])/.test(path) ||
    (mime.startsWith("video/") && mime !== "video/mp2t")
  ) {
    return "progressive";
  }
  return null;
}

function sanitizeHeaders(requestHeaders) {
  const result = {};
  for (const header of requestHeaders || []) {
    const name = String(header.name || "").trim();
    const value = typeof header.value === "string" ? header.value : "";
    if (!name || BLOCKED_HEADERS.has(name.toLowerCase()) || /[\r\n]/.test(value)) continue;
    result[name] = value;
  }
  return result;
}

function candidateKey(tabId, url) {
  return `${tabId}:${url}`;
}

function persistCandidates() {
  persistQueue = persistQueue
    .catch(() => {})
    .then(() => chrome.storage.session.set({ candidates }));
}

async function addCandidate(details, type, contentType = "") {
  if (details.tabId < 0 || !type) return;
  await candidatesReady;

  const key = candidateKey(details.tabId, details.url);
  const candidate = {
    key,
    tabId: details.tabId,
    url: details.url,
    type,
    headers: details.headers || {},
    contentType: contentType || details.contentType || "",
    resourceType: details.resourceType || "",
    seenAt: Date.now()
  };

  candidates = [candidate, ...candidates.filter(item => item.key !== key)].slice(0, MAX_CANDIDATES);
  persistCandidates();
}

function trimPendingRequests() {
  const cutoff = Date.now() - 20_000;
  for (const [requestId, request] of pendingRequests) {
    if (request.seenAt < cutoff) pendingRequests.delete(requestId);
  }
  while (pendingRequests.size > MAX_PENDING_REQUESTS) {
    pendingRequests.delete(pendingRequests.keys().next().value);
  }
}

// Register URL-based candidates before waiting for response headers. This is
// important for YouTube and other MSE players whose media responses can be
// cached or have no useful Content-Type exposed to webRequest.
chrome.webRequest.onBeforeRequest.addListener(
  details => {
    if (details.tabId < 0) return;
    const type = inferType(details.url);
    if (!type) return;

    const pending = pendingRequests.get(details.requestId);
    addCandidate({
      tabId: details.tabId,
      url: details.url,
      headers: pending ? pending.headers : {},
      resourceType: details.type
    }, type);
  },
  REQUEST_FILTER
);

chrome.webRequest.onBeforeSendHeaders.addListener(
  details => {
    if (details.tabId < 0) return;

    const headers = sanitizeHeaders(details.requestHeaders);
    const type = inferType(details.url);
    pendingRequests.set(details.requestId, {
      tabId: details.tabId,
      url: details.url,
      headers,
      resourceType: details.type,
      seenAt: Date.now()
    });
    trimPendingRequests();

    if (type) {
      addCandidate({
        tabId: details.tabId,
        url: details.url,
        headers,
        resourceType: details.type
      }, type);
    }
  },
  REQUEST_FILTER,
  ["requestHeaders", "extraHeaders"]
);

chrome.webRequest.onHeadersReceived.addListener(
  details => {
    const pending = pendingRequests.get(details.requestId);
    const responseHeaders = details.responseHeaders || [];
    const contentType = headerValue(responseHeaders, "content-type");
    const type = inferType(details.url, contentType);

    if (type) {
      addCandidate({
        tabId: details.tabId,
        url: details.url,
        headers: pending ? pending.headers : {},
        resourceType: details.type,
        contentType
      }, type, contentType);
    }
    pendingRequests.delete(details.requestId);
  },
  REQUEST_FILTER,
  ["responseHeaders", "extraHeaders"]
);

function forgetPending(details) {
  pendingRequests.delete(details.requestId);
}

chrome.webRequest.onCompleted.addListener(forgetPending, REQUEST_FILTER);
chrome.webRequest.onErrorOccurred.addListener(forgetPending, REQUEST_FILTER);

async function removeCandidatesForTab(tabId) {
  await candidatesReady;
  candidates = candidates.filter(candidate => candidate.tabId !== tabId);
  persistCandidates();
  for (const [requestId, request] of pendingRequests) {
    if (request.tabId === tabId) pendingRequests.delete(requestId);
  }
}

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.status === "loading") removeCandidatesForTab(tabId);
});

chrome.tabs.onRemoved.addListener(tabId => removeCandidatesForTab(tabId));

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName === "session" && changes.candidates) {
    candidates = Array.isArray(changes.candidates.newValue) ? changes.candidates.newValue : [];
  }
});
