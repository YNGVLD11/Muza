const HOST_URL = "http://127.0.0.1:17890";

let currentUrl = "";
let currentBitrate = "320";
let activeTaskId = null;
let pollInterval = null;
let downloadedFilePath = null;

document.addEventListener("DOMContentLoaded", async () => {
  initUI();
  await checkHostAndInitTab();
});

function initUI() {
  // Retry host button
  document.getElementById("btn-retry-host")?.addEventListener("click", checkHostAndInitTab);

  // Bitrate pills
  document.querySelectorAll(".pill").forEach((pill) => {
    pill.addEventListener("click", () => {
      document.querySelectorAll(".pill").forEach((p) => p.classList.remove("active"));
      pill.classList.add("active");
      currentBitrate = pill.getAttribute("data-bitrate");
    });
  });

  // Download button
  document.getElementById("btn-download")?.addEventListener("click", startDownload);

  // Open folder button
  document.getElementById("btn-open-folder")?.addEventListener("click", openDownloadedFile);

  // Manual URL submit
  document.getElementById("btn-manual-submit")?.addEventListener("click", () => {
    const manualUrl = document.getElementById("manual-url-input").value.trim();
    if (manualUrl) {
      setupMediaView(manualUrl, "Пользовательская ссылка");
    }
  });

  // Settings modal
  const modal = document.getElementById("settings-modal");
  document.getElementById("btn-open-settings")?.addEventListener("click", async () => {
    modal.classList.remove("hidden");
    await loadSettings();
  });
  document.getElementById("btn-close-settings")?.addEventListener("click", () => {
    modal.classList.add("hidden");
  });

  // Browse folder button
  document.getElementById("btn-browse-folder")?.addEventListener("click", async () => {
    try {
      const res = await fetch(`${HOST_URL}/select-directory`, { method: "POST" });
      if (res.ok) {
        const data = await res.json();
        if (data.selected_path) {
          document.getElementById("settings-path-display").textContent = data.selected_path;
        }
      }
    } catch (e) {
      console.error("Failed to select folder", e);
    }
  });

  // Check updates button
  document.getElementById("btn-check-updates")?.addEventListener("click", async () => {
    const updateInfo = document.getElementById("update-status");
    updateInfo.textContent = "Проверка...";
    try {
      const res = await fetch(`${HOST_URL}/check-updates`);
      if (res.ok) {
        const data = await res.json();
        if (data.latest_version) {
          updateInfo.textContent = `Доступна версия: ${data.latest_version}`;
        } else {
          updateInfo.textContent = "У вас актуальная версия";
        }
      } else {
        updateInfo.textContent = "Не удалось проверить";
      }
    } catch (e) {
      updateInfo.textContent = "Ошибка соединения";
    }
  });
}

async function checkHostAndInitTab() {
  const isOnline = await checkHostStatus();
  if (!isOnline) {
    showView("view-host-offline");
    return;
  }

  // Query active tab
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (!tabs || tabs.length === 0) {
      showView("view-no-media");
      return;
    }

    const tab = tabs[0];
    const url = tab.url || "";
    if (isYouTubeUrl(url)) {
      const cleanTitle = (tab.title || "YouTube Audio").replace(/ - YouTube$/, "");
      setupMediaView(url, cleanTitle);
    } else {
      showView("view-no-media");
    }
  });
}

async function checkHostStatus() {
  const dot = document.getElementById("host-status-dot");
  try {
    const res = await fetch(`${HOST_URL}/health`, { signal: AbortSignal.timeout(1500) });
    if (res.ok) {
      dot.className = "status-dot online";
      dot.title = "Хост подключен (127.0.0.1:17890)";
      return true;
    }
  } catch (e) {
    // Offline
  }
  dot.className = "status-dot offline";
  dot.title = "Хост не запущен";
  return false;
}

function isYouTubeUrl(url) {
  return /^(https?:\/\/)?(www\.|m\.)?(youtube\.com\/(watch\?|shorts\/)|youtu\.be\/)/.test(url);
}

function extractYouTubeId(url) {
  const m = url.match(/(?:v=|\/shorts\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/);
  return m ? m[1] : null;
}

function setupMediaView(url, fallbackTitle) {
  currentUrl = url;
  const vid = extractYouTubeId(url);
  const thumbUrl = vid ? `https://i.ytimg.com/vi/${vid}/hqdefault.jpg` : "icons/icon128.png";

  document.getElementById("media-thumbnail").src = thumbUrl;
  document.getElementById("media-title").textContent = fallbackTitle;

  // Reset actions
  document.getElementById("btn-download").classList.remove("hidden");
  document.getElementById("progress-container").classList.add("hidden");
  document.getElementById("btn-open-folder").classList.add("hidden");

  showView("view-media");

  // Asynchronously fetch exact title from host
  fetch(`${HOST_URL}/info?url=${encodeURIComponent(url)}`)
    .then((r) => r.ok ? r.json() : null)
    .then((info) => {
      if (info && info.title) {
        document.getElementById("media-title").textContent = info.title;
        if (info.thumbnail) {
          document.getElementById("media-thumbnail").src = info.thumbnail;
        }
      }
    })
    .catch(() => {});
}

async function startDownload() {
  if (!currentUrl) return;

  const btnDownload = document.getElementById("btn-download");
  const progressBox = document.getElementById("progress-container");
  const progressFill = document.getElementById("progress-fill");
  const progressText = document.getElementById("progress-status-text");

  btnDownload.classList.add("hidden");
  progressBox.classList.remove("hidden");
  progressFill.style.width = "0%";
  progressText.textContent = "Запуск загрузки...";

  try {
    const res = await fetch(`${HOST_URL}/download`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url: currentUrl, bitrate: currentBitrate }),
    });

    if (!res.ok) throw new Error("Не удалось начать скачивание");

    const data = await res.json();
    activeTaskId = data.task_id;
    startPollingTask(activeTaskId);
  } catch (e) {
    progressText.textContent = "Ошибка запуска";
    setTimeout(() => {
      btnDownload.classList.remove("hidden");
      progressBox.classList.add("hidden");
    }, 2000);
  }
}

function startPollingTask(taskId) {
  if (pollInterval) clearInterval(pollInterval);

  const progressFill = document.getElementById("progress-fill");
  const progressText = document.getElementById("progress-status-text");

  pollInterval = setInterval(async () => {
    try {
      const res = await fetch(`${HOST_URL}/tasks/${taskId}`);
      if (!res.ok) return;

      const task = await res.json();
      const percent = task.percent || 0;
      progressFill.style.width = `${percent}%`;
      progressText.textContent = task.status_text || "Обработка...";

      if (task.status === "COMPLETED") {
        clearInterval(pollInterval);
        downloadedFilePath = task.file_path;
        showCompleteState();
      } else if (task.status === "FAILED") {
        clearInterval(pollInterval);
        progressText.textContent = task.error || "Ошибка загрузки";
      }
    } catch (e) {
      console.warn("Poll error", e);
    }
  }, 500);
}

function showCompleteState() {
  document.getElementById("progress-container").classList.add("hidden");
  const btnOpen = document.getElementById("btn-open-folder");
  btnOpen.classList.remove("hidden");
}

async function openDownloadedFile() {
  if (!downloadedFilePath) return;
  try {
    await fetch(`${HOST_URL}/open-file`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ path: downloadedFilePath }),
    });
  } catch (e) {
    console.error("Failed to open file", e);
  }
}

async function loadSettings() {
  try {
    const res = await fetch(`${HOST_URL}/settings`);
    if (res.ok) {
      const cfg = await res.json();
      document.getElementById("settings-path-display").textContent =
        cfg.download_dir || "Не выбрано";
    }
  } catch (e) {
    document.getElementById("settings-path-display").textContent = "Хост недоступен";
  }
}

function showView(viewId) {
  document.querySelectorAll(".view").forEach((v) => v.classList.add("hidden"));
  document.getElementById(viewId)?.classList.remove("hidden");
}
