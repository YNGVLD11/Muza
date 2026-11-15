const NATIVE_HOST = "com.muza.host";

let nativePort = null;
let idleTimer = null;
let convertingAnimTimer = null;
let animAngle = 0;

const pendingRequests = new Map();
let requestIdCounter = 1;

chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.local.get(["queue"], (res) => {
    if (!res.queue) {
      chrome.storage.local.set({ queue: [], activeTaskId: null, hostConnected: false });
    }
  });
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === "ENQUEUE_ITEM") {
    handleEnqueueItem(message.payload).then(sendResponse);
    return true;
  } else if (message.type === "START_QUEUE") {
    handleStartQueue().then(sendResponse);
    return true;
  } else if (message.type === "REMOVE_ITEM") {
    handleRemoveItem(message.payload.id).then(sendResponse);
    return true;
  } else if (message.type === "CLEAR_QUEUE") {
    handleClearQueue().then(sendResponse);
    return true;
  } else if (message.type === "GET_INFO") {
    sendNativeRequest("GET_INFO", { url: message.url })
      .then((res) => sendResponse({ success: true, data: res.data }))
      .catch((err) => sendResponse({ success: false, error: err.message }));
    return true;
  } else if (message.type === "GET_SETTINGS") {
    sendNativeRequest("GET_SETTINGS")
      .then((res) => sendResponse({ success: true, data: res.data }))
      .catch((err) => sendResponse({ success: false, error: err.message }));
    return true;
  } else if (message.type === "SAVE_SETTINGS") {
    sendNativeRequest("SAVE_SETTINGS", message.payload)
      .then((res) => sendResponse({ success: true, data: res.data }))
      .catch((err) => sendResponse({ success: false, error: err.message }));
    return true;
  } else if (message.type === "SELECT_DIRECTORY") {
    sendNativeRequest("SELECT_DIRECTORY")
      .then((res) => sendResponse({ success: true, selected_path: res.selected_path }))
      .catch((err) => sendResponse({ success: false, error: err.message }));
    return true;
  } else if (message.type === "OPEN_FILE") {
    sendNativeRequest("OPEN_FILE", { path: message.path })
      .then((res) => sendResponse({ success: true }))
      .catch((err) => sendResponse({ success: false, error: err.message }));
    return true;
  } else if (message.type === "OPEN_DIRECTORY") {
    sendNativeRequest("OPEN_DIRECTORY", { path: message.path })
      .then((res) => sendResponse({ success: true }))
      .catch((err) => sendResponse({ success: false, error: err.message }));
    return true;
  } else if (message.type === "CHECK_UPDATES") {
    sendNativeRequest("CHECK_UPDATES")
      .then((res) => sendResponse({ success: true, data: res.data }))
      .catch((err) => sendResponse({ success: false, error: err.message }));
    return true;
  } else if (message.type === "CHECK_HOST") {
    sendNativeRequest("PING")
      .then((res) => sendResponse({ success: true, status: res.status }))
      .catch((err) => sendResponse({ success: false, error: err.message }));
    return true;
  }
});

function getOrCreateNativePort() {
  if (nativePort) return nativePort;

  try {
    nativePort = chrome.runtime.connectNative(NATIVE_HOST);

    nativePort.onMessage.addListener((msg) => {
      onNativeMessage(msg);
    });

    nativePort.onDisconnect.addListener(() => {
      const err = chrome.runtime.lastError;
      nativePort = null;
      if (err) {
        console.warn("Native host disconnected:", err.message);
        chrome.storage.local.set({ hostConnected: false, hostError: err.message });
      } else {
        chrome.storage.local.set({ hostConnected: false });
      }
    });

    chrome.storage.local.set({ hostConnected: true, hostError: null });
    return nativePort;
  } catch (e) {
    console.error("connectNative error:", e);
    nativePort = null;
    chrome.storage.local.set({ hostConnected: false, hostError: e.message });
    return null;
  }
}

function sendNativeRequest(action, data = {}) {
  return new Promise((resolve, reject) => {
    const port = getOrCreateNativePort();
    if (!port) {
      return reject(new Error("Хост не установлен. Запустите install.bat в папке host-windows"));
    }

    resetIdleTimer();

    const reqId = `req_${requestIdCounter++}`;
    const timeout = setTimeout(() => {
      if (pendingRequests.has(reqId)) {
        pendingRequests.delete(reqId);
        reject(new Error("Таймаут ожидания ответа"));
      }
    }, 60000);

    pendingRequests.set(reqId, (response) => {
      clearTimeout(timeout);
      resolve(response);
    });

    try {
      port.postMessage({ action, request_id: reqId, ...data });
    } catch (err) {
      pendingRequests.delete(reqId);
      clearTimeout(timeout);
      reject(err);
    }
  });
}

function resetIdleTimer() {
  if (idleTimer) clearTimeout(idleTimer);
  idleTimer = setTimeout(() => {
    chrome.storage.local.get(["activeTaskId", "queue"], (res) => {
      const activeId = res.activeTaskId;
      const isBusy = activeId != null;
      if (!isBusy && nativePort) {
        try {
          nativePort.disconnect();
        } catch (e) {}
        nativePort = null;
      }
    });
  }, 45000);
}

function extractYouTubeId(url) {
  if (!url) return null;
  const m = url.match(/(?:v=|\/shorts\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/);
  return m ? m[1] : null;
}

async function handleEnqueueItem({ url, fallbackTitle, bitrate }) {
  if (!url) return { success: false, error: "Пустая ссылка" };

  const storage = await chrome.storage.local.get(["queue"]);
  const queue = storage.queue || [];

  const targetId = extractYouTubeId(url);

  // Check for duplicate in queue
  const existing = queue.find((it) => {
    if (targetId) {
      return extractYouTubeId(it.url) === targetId;
    }
    return it.url === url;
  });

  if (existing) {
    return { success: true, item: existing, alreadyExisted: true };
  }

  const thumb = targetId ? `https://i.ytimg.com/vi/${targetId}/hqdefault.jpg` : "icons/icon128.png";
  const itemId = `item_${Date.now()}_${Math.floor(Math.random() * 1000)}`;

  const newItem = {
    id: itemId,
    url,
    title: fallbackTitle || "Загрузка информации...",
    thumbnail: thumb,
    bitrate: bitrate || "320",
    status: "QUEUED",
    percent: 0,
    status_text: "В очереди",
    file_path: null,
    file_name: null,
    error: null,
    timestamp: Date.now(),
  };

  queue.push(newItem);
  await chrome.storage.local.set({ queue });

  // Asynchronously query exact title & thumbnail via Native Host
  sendNativeRequest("GET_INFO", { url })
    .then(async (res) => {
      if (res && res.data) {
        const curStorage = await chrome.storage.local.get(["queue"]);
        const curQueue = curStorage.queue || [];
        const idx = curQueue.findIndex((x) => x.id === itemId);
        if (idx !== -1) {
          if (res.data.title) curQueue[idx].title = res.data.title;
          if (res.data.thumbnail) curQueue[idx].thumbnail = res.data.thumbnail;
          await chrome.storage.local.set({ queue: curQueue });
        }
      }
    })
    .catch(() => {});

  return { success: true, item: newItem, alreadyExisted: false };
}

async function handleRemoveItem(itemId) {
  const storage = await chrome.storage.local.get(["queue", "activeTaskId"]);
  let queue = storage.queue || [];
  const activeTaskId = storage.activeTaskId;

  if (activeTaskId === itemId) {
    try {
      const port = getOrCreateNativePort();
      port?.postMessage({ action: "CANCEL_DOWNLOAD", task_id: itemId });
    } catch (e) {}
    stopConvertingAnimation();
  }

  queue = queue.filter((it) => it.id !== itemId);
  const newActiveId = activeTaskId === itemId ? null : activeTaskId;

  await chrome.storage.local.set({ queue, activeTaskId: newActiveId });

  if (activeTaskId === itemId) {
    processNextQueueItem();
  } else {
    updateBadgeFromQueue(queue);
  }

  return { success: true };
}

async function handleClearQueue() {
  const storage = await chrome.storage.local.get(["activeTaskId"]);
  if (storage.activeTaskId) {
    try {
      const port = getOrCreateNativePort();
      port?.postMessage({ action: "CANCEL_DOWNLOAD", task_id: storage.activeTaskId });
    } catch (e) {}
  }
  stopConvertingAnimation();
  await chrome.storage.local.set({ queue: [], activeTaskId: null });
  resetIconAndBadge();
  resetIdleTimer();
  return { success: true };
}

async function handleStartQueue() {
  const storage = await chrome.storage.local.get(["activeTaskId"]);
  if (storage.activeTaskId != null) {
    // Already running
    return { success: true };
  }
  await processNextQueueItem();
  return { success: true };
}

async function processNextQueueItem() {
  const storage = await chrome.storage.local.get(["queue", "activeTaskId"]);
  const queue = storage.queue || [];

  const nextItem = queue.find((it) => it.status === "QUEUED" || it.status === "FETCHING_INFO");
  if (!nextItem) {
    // All done
    await chrome.storage.local.set({ activeTaskId: null });
    stopConvertingAnimation();
    updateBadgeFromQueue(queue);
    resetIdleTimer();
    return;
  }

  const port = getOrCreateNativePort();
  if (!port) {
    console.error("Cannot start task: Native Host not connected");
    return;
  }

  nextItem.status = "DOWNLOADING";
  nextItem.status_text = "Подготовка...";
  nextItem.percent = 0;

  await chrome.storage.local.set({ queue, activeTaskId: nextItem.id });
  resetIdleTimer();

  port.postMessage({
    action: "START_DOWNLOAD",
    task_id: nextItem.id,
    url: nextItem.url,
    bitrate: nextItem.bitrate || "320",
    title: nextItem.title,
    thumbnail: nextItem.thumbnail,
  });

  updateIconAndBadge(nextItem);
}

function onNativeMessage(msg) {
  if (!msg) return;

  if (msg.request_id && pendingRequests.has(msg.request_id)) {
    const resolve = pendingRequests.get(msg.request_id);
    pendingRequests.delete(msg.request_id);
    resolve(msg);
    return;
  }

  if (msg.action === "TASK_UPDATE" && msg.task) {
    handleTaskProgress(msg.task);
  }
}

async function handleTaskProgress(task) {
  const storage = await chrome.storage.local.get(["queue", "activeTaskId"]);
  const queue = storage.queue || [];
  const idx = queue.findIndex((x) => x.id === task.id);

  if (idx !== -1) {
    queue[idx].status = task.status;
    queue[idx].percent = task.percent || 0;
    queue[idx].status_text = task.status_text || "";
    if (task.file_path) queue[idx].file_path = task.file_path;
    if (task.file_name) queue[idx].file_name = task.file_name;
    if (task.error) queue[idx].error = task.error;

    await chrome.storage.local.set({ queue });
  }

  updateIconAndBadge(task);

  if (task.status === "COMPLETED" || task.status === "FAILED") {
    stopConvertingAnimation();
    // Move on to the next queued item
    processNextQueueItem();
  }
}

function updateBadgeFromQueue(queue) {
  const hasCompleted = queue.some((it) => it.status === "COMPLETED");
  if (hasCompleted) {
    chrome.action.setBadgeText({ text: "✓" });
    chrome.action.setBadgeBackgroundColor({ color: "#22C55E" });
  } else {
    resetIconAndBadge();
  }
}

function updateIconAndBadge(task) {
  if (!task) {
    resetIconAndBadge();
    return;
  }

  const percent = task.percent || 0;

  if (task.status === "DOWNLOADING") {
    stopConvertingAnimation();
    chrome.action.setBadgeText({ text: `${Math.round(percent)}%` });
    chrome.action.setBadgeBackgroundColor({ color: "#27272A" });
    drawCircularProgressIcon(percent);
  } else if (task.status === "CONVERTING") {
    chrome.action.setBadgeText({ text: "" });
    startConvertingAnimation();
  } else if (task.status === "COMPLETED") {
    stopConvertingAnimation();
    chrome.action.setBadgeText({ text: "✓" });
    chrome.action.setBadgeBackgroundColor({ color: "#22C55E" });
    drawCircularProgressIcon(100);
  } else if (task.status === "FAILED") {
    stopConvertingAnimation();
    chrome.action.setBadgeText({ text: "!" });
    chrome.action.setBadgeBackgroundColor({ color: "#EF4444" });
    resetIconOnly();
  }
}

function startConvertingAnimation() {
  if (convertingAnimTimer) return;
  animAngle = 0;
  drawSpinnerFrame(animAngle);
  convertingAnimTimer = setInterval(() => {
    animAngle = (animAngle + 0.28) % (Math.PI * 2);
    drawSpinnerFrame(animAngle);
  }, 75);
}

function stopConvertingAnimation() {
  if (convertingAnimTimer) {
    clearInterval(convertingAnimTimer);
    convertingAnimTimer = null;
  }
}

function drawSpinnerFrame(angle) {
  try {
    const size = 32;
    const canvas = new OffscreenCanvas(size, size);
    const ctx = canvas.getContext("2d");

    ctx.clearRect(0, 0, size, size);

    // Track circle
    ctx.beginPath();
    ctx.arc(size / 2, size / 2, 12, 0, 2 * Math.PI);
    ctx.lineWidth = 3;
    ctx.strokeStyle = "#27272A";
    ctx.stroke();

    // Spinning loader arc
    ctx.beginPath();
    ctx.arc(size / 2, size / 2, 12, angle, angle + 1.2 * Math.PI);
    ctx.lineWidth = 3;
    ctx.strokeStyle = "#FAFAFA";
    ctx.lineCap = "round";
    ctx.stroke();

    // Center dot
    ctx.beginPath();
    ctx.arc(size / 2, size / 2, 3.5, 0, 2 * Math.PI);
    ctx.fillStyle = "#A1A1AA";
    ctx.fill();

    const imageData = ctx.getImageData(0, 0, size, size);
    chrome.action.setIcon({ imageData: { 32: imageData } });
  } catch (e) {
    // Canvas fallback
  }
}

function drawCircularProgressIcon(percent) {
  try {
    const size = 32;
    const canvas = new OffscreenCanvas(size, size);
    const ctx = canvas.getContext("2d");

    ctx.clearRect(0, 0, size, size);

    // Dark circle background
    ctx.beginPath();
    ctx.arc(size / 2, size / 2, 13, 0, 2 * Math.PI);
    ctx.lineWidth = 3;
    ctx.strokeStyle = "#27272A";
    ctx.stroke();

    // Foreground progress ring
    if (percent > 0) {
      const startAngle = -Math.PI / 2;
      const endAngle = startAngle + (2 * Math.PI * (percent / 100));
      ctx.beginPath();
      ctx.arc(size / 2, size / 2, 13, startAngle, endAngle);
      ctx.lineWidth = 3;
      ctx.strokeStyle = percent >= 100 ? "#22C55E" : "#FAFAFA";
      ctx.lineCap = "round";
      ctx.stroke();
    }

    // Center dot
    ctx.beginPath();
    ctx.arc(size / 2, size / 2, 4, 0, 2 * Math.PI);
    ctx.fillStyle = percent >= 100 ? "#22C55E" : "#FAFAFA";
    ctx.fill();

    const imageData = ctx.getImageData(0, 0, size, size);
    chrome.action.setIcon({ imageData: { 32: imageData } });
  } catch (e) {
    // Canvas fallback
  }
}

function resetIconOnly() {
  chrome.action.setIcon({
    path: {
      "16": "icons/icon16.png",
      "32": "icons/icon32.png",
      "48": "icons/icon48.png",
      "128": "icons/icon128.png",
    },
  });
}

function resetIconAndBadge() {
  stopConvertingAnimation();
  resetIconOnly();
  chrome.action.setBadgeText({ text: "" });
}
