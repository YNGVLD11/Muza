let currentBitrate = "320";

document.addEventListener("DOMContentLoaded", async () => {
  initUI();
  await checkHostAndInitQueue();
});

function initUI() {
  // Retry host check button
  document.getElementById("btn-retry-host")?.addEventListener("click", checkHostAndInitQueue);

  // Bitrate pills
  document.querySelectorAll(".pill").forEach((pill) => {
    pill.addEventListener("click", () => {
      document.querySelectorAll(".pill").forEach((p) => p.classList.remove("active"));
      pill.classList.add("active");
      currentBitrate = pill.getAttribute("data-bitrate");

      // Immediately synchronize all pending items in the queue to the selected bitrate
      chrome.storage.local.get(["queue", "activeTaskId"], (res) => {
        const queue = res.queue || [];
        let changed = false;
        queue.forEach((it) => {
          if (it.status === "QUEUED" || it.status === "FETCHING_INFO") {
            it.bitrate = currentBitrate;
            changed = true;
          }
        });
        if (changed) {
          chrome.storage.local.set({ queue }, () => {
            renderQueueState(queue, res.activeTaskId);
          });
        }
      });
    });
  });

  // Submit URL button
  document.getElementById("btn-submit-url")?.addEventListener("click", submitUrlInput);
  document.getElementById("url-input")?.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      submitUrlInput();
    }
  });

  // Clear queue button
  document.getElementById("btn-clear-queue")?.addEventListener("click", () => {
    chrome.runtime.sendMessage({ type: "CLEAR_QUEUE" }, () => {
      renderQueueState([], null);
    });
  });

  // Main action button (Download / Active status / Open folder)
  document.getElementById("btn-main-action")?.addEventListener("click", onMainActionClick);

  // Settings modal
  const modal = document.getElementById("settings-modal");
  document.getElementById("btn-open-settings")?.addEventListener("click", async () => {
    modal.classList.remove("hidden");
    await loadSettings();
  });
  document.getElementById("btn-close-settings")?.addEventListener("click", () => {
    modal.classList.add("hidden");
  });

  // Browse folder button: automatically sets and displays chosen path
  document.getElementById("btn-browse-folder")?.addEventListener("click", () => {
    chrome.runtime.sendMessage({ type: "SELECT_DIRECTORY" }, (res) => {
      if (res && res.success && res.selected_path) {
        const inputEl = document.getElementById("settings-path-input");
        if (inputEl) inputEl.value = res.selected_path;
      }
    });
  });

  // Check updates button
  document.getElementById("btn-check-updates")?.addEventListener("click", () => {
    const updateInfo = document.getElementById("update-status");
    updateInfo.textContent = "Проверка...";
    chrome.runtime.sendMessage({ type: "CHECK_UPDATES" }, (res) => {
      if (res && res.success && res.data) {
        if (res.data.latest_version) {
          updateInfo.textContent = `Доступна версия: ${res.data.latest_version}`;
        } else {
          updateInfo.textContent = "У вас актуальная версия";
        }
      } else {
        updateInfo.textContent = "Не удалось проверить обновления";
      }
    });
  });

  // Listen to background changes to live update the queue
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local") {
      if (changes.queue || changes.activeTaskId) {
        chrome.storage.local.get(["queue", "activeTaskId"], (res) => {
          renderQueueState(res.queue || [], res.activeTaskId);
        });
      }
    }
  });
}

async function checkHostAndInitQueue() {
  const isOnline = await checkHostStatus();
  if (!isOnline) {
    showView("view-host-offline");
    return;
  }

  showView("view-main");

  // Load existing queue from storage
  const storage = await chrome.storage.local.get(["queue", "activeTaskId"]);
  const queue = storage.queue || [];
  renderQueueState(queue, storage.activeTaskId);

  // Load configured default bitrate if available
  chrome.runtime.sendMessage({ type: "GET_SETTINGS" }, (res) => {
    if (res && res.data && res.data.default_bitrate) {
      setBitratePill(res.data.default_bitrate);
    }
  });

  // Check active tab: auto-enqueue if viewing YouTube video
  chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    if (!tabs || tabs.length === 0) return;
    const tab = tabs[0];
    const url = tab.url || "";
    if (isYouTubeUrl(url)) {
      const cleanTitle = (tab.title || "YouTube Audio").replace(/ - YouTube$/, "");
      chrome.runtime.sendMessage({
        type: "ENQUEUE_ITEM",
        payload: {
          url,
          fallbackTitle: cleanTitle,
          bitrate: currentBitrate,
        },
      }, () => {
        chrome.storage.local.get(["queue", "activeTaskId"], (res) => {
          renderQueueState(res.queue || [], res.activeTaskId);
        });
      });
    }
  });
}

function submitUrlInput() {
  const inputEl = document.getElementById("url-input");
  const url = inputEl.value.trim();
  if (!url) return;

  chrome.runtime.sendMessage({
    type: "ENQUEUE_ITEM",
    payload: {
      url,
      fallbackTitle: "YouTube Audio",
      bitrate: currentBitrate,
    },
  }, (res) => {
    if (res && res.success) {
      inputEl.value = "";
      chrome.storage.local.get(["queue", "activeTaskId"], (s) => {
        renderQueueState(s.queue || [], s.activeTaskId);
      });
    }
  });
}

function onMainActionClick() {
  chrome.storage.local.get(["queue", "activeTaskId"], (res) => {
    const queue = res.queue || [];
    const activeId = res.activeTaskId;

    if (activeId != null) return;

    const hasPending = queue.some((it) => it.status === "QUEUED" || it.status === "FETCHING_INFO");
    if (hasPending) {
      // Ensure all pending items use the selected bitrate
      queue.forEach((it) => {
        if (it.status === "QUEUED" || it.status === "FETCHING_INFO") {
          it.bitrate = currentBitrate;
        }
      });
      chrome.storage.local.set({ queue }, () => {
        chrome.runtime.sendMessage({ type: "START_QUEUE" });
      });
    } else {
      const hasCompleted = queue.some((it) => it.status === "COMPLETED");
      if (hasCompleted) {
        chrome.runtime.sendMessage({ type: "OPEN_DIRECTORY" });
      }
    }
  });
}

function renderQueueState(queue, activeTaskId) {
  const listEl = document.getElementById("queue-list");
  const emptyEl = document.getElementById("queue-empty");
  const titleEl = document.getElementById("queue-title");
  const mainBtn = document.getElementById("btn-main-action");
  const mainBtnText = document.getElementById("main-btn-text");
  const mainBtnIcon = document.getElementById("main-btn-icon");

  if (titleEl) {
    titleEl.textContent = `Очередь (${queue.length})`;
  }

  // Update empty queue placeholder
  if (queue.length === 0) {
    emptyEl?.classList.remove("hidden");
    listEl.innerHTML = "";
    if (mainBtn) {
      mainBtn.disabled = true;
      mainBtn.style.opacity = "0.5";
      mainBtnText.textContent = "Скачать MP3";
      if (mainBtnIcon) {
        mainBtnIcon.innerHTML = `
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
          <polyline points="7 10 12 15 17 10"></polyline>
          <line x1="12" y1="15" x2="12" y2="3"></line>
        `;
      }
    }
    return;
  }

  emptyEl?.classList.add("hidden");
  if (mainBtn) {
    mainBtn.disabled = false;
    mainBtn.style.opacity = "1";
  }

  const activeItem = queue.find((it) => it.id === activeTaskId);
  const pendingCount = queue.filter((it) => it.status === "QUEUED" || it.status === "FETCHING_INFO").length;
  const hasCompleted = queue.some((it) => it.status === "COMPLETED");

  // Render Main Action Button text & icon
  if (activeItem) {
    mainBtnText.textContent = activeItem.status_text || "Скачивание...";
    mainBtnIcon.innerHTML = `
      <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-dasharray="28" stroke-dashoffset="10">
        <animateTransform attributeName="transform" type="rotate" from="0 12 12" to="360 12 12" dur="1s" repeatCount="indefinite" />
      </circle>
    `;
  } else if (pendingCount > 0) {
    mainBtnText.textContent = pendingCount === 1 ? "Скачать MP3" : `Скачать всё (${pendingCount})`;
    mainBtnIcon.innerHTML = `
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
      <polyline points="7 10 12 15 17 10"></polyline>
      <line x1="12" y1="15" x2="12" y2="3"></line>
    `;
  } else if (hasCompleted) {
    mainBtnText.textContent = "Открыть папку с треками";
    mainBtnIcon.innerHTML = `
      <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
    `;
  }

  // Render queue item cards
  listEl.innerHTML = "";
  queue.forEach((item) => {
    const isConverting = item.status === "CONVERTING";
    const isCompleted = item.status === "COMPLETED";
    const isFailed = item.status === "FAILED";
    const isDownloading = item.status === "DOWNLOADING" || item.status === "PENDING";

    const card = document.createElement("div");
    card.className = `queue-item-card${isConverting ? " shimmering" : ""}`;
    card.setAttribute("data-id", item.id);

    let statusClass = "";
    let displayStatusText = "В очереди";

    if (isCompleted) {
      statusClass = " completed";
      displayStatusText = "Скачано:";
    } else if (isFailed) {
      statusClass = " error";
      displayStatusText = "Ошибка";
    } else if (isConverting) {
      displayStatusText = "Конвертация...";
    } else if (isDownloading) {
      const progressPercent = Math.round(item.percent || 0);
      displayStatusText = progressPercent > 0 ? `Скачивание: ${progressPercent}%` : (item.status_text || "Подготовка...");
    } else if (item.status === "FETCHING_INFO") {
      displayStatusText = "Получение инфо...";
    }

    const progressPercent = item.percent || 0;
    const showProgress = isDownloading || isConverting;

    card.innerHTML = `
      <div class="item-thumb-box">
        <img class="item-thumb" src="${escapeHtml(item.thumbnail || 'icons/icon128.png')}" alt="Обложка">
      </div>
      <div class="item-body">
        <p class="item-title" title="${escapeHtml(item.title)}">${escapeHtml(item.title)}</p>
        <div class="item-status-row">
          <span class="item-status-text${statusClass}">${escapeHtml(displayStatusText)}</span>
          <span class="item-bitrate-badge">${item.bitrate || currentBitrate}K</span>
        </div>
        ${showProgress ? `
          <div class="item-progress-bar">
            <div class="item-progress-fill" style="width: ${progressPercent}%;"></div>
          </div>
        ` : ''}
      </div>
      <div class="item-actions">
        ${isCompleted && item.file_path ? `
          <button class="item-btn-action item-btn-folder" title="Открыть в папке" data-path="${escapeHtml(item.file_path)}">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
            </svg>
          </button>
        ` : ''}
        <button class="item-btn-action item-btn-remove" title="Удалить из очереди" data-id="${item.id}">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"></line>
            <line x1="6" y1="6" x2="18" y2="18"></line>
          </svg>
        </button>
      </div>
    `;

    card.querySelector(".item-btn-remove")?.addEventListener("click", () => {
      chrome.runtime.sendMessage({ type: "REMOVE_ITEM", payload: { id: item.id } });
    });

    card.querySelector(".item-btn-folder")?.addEventListener("click", () => {
      if (item.file_path) {
        chrome.runtime.sendMessage({ type: "OPEN_FILE", path: item.file_path });
      }
    });

    listEl.appendChild(card);
  });
}

function setBitratePill(bitrate) {
  if (!bitrate) return;
  currentBitrate = String(bitrate);
  document.querySelectorAll(".pill").forEach((p) => {
    if (p.getAttribute("data-bitrate") === currentBitrate) {
      p.classList.add("active");
    } else {
      p.classList.remove("active");
    }
  });
}

function checkHostStatus() {
  return new Promise((resolve) => {
    const dot = document.getElementById("host-status-dot");
    chrome.runtime.sendMessage({ type: "CHECK_HOST" }, (res) => {
      if (res && res.success) {
        dot.className = "status-dot online";
        dot.title = "Native Host подключен";
        resolve(true);
      } else {
        dot.className = "status-dot offline";
        dot.title = "Native Host не подключен";
        resolve(false);
      }
    });
  });
}

function isYouTubeUrl(url) {
  return /^(https?:\/\/)?(www\.|m\.)?(youtube\.com\/(watch\?|shorts\/)|youtu\.be\/)/.test(url);
}

function loadSettings() {
  return new Promise((resolve) => {
    chrome.runtime.sendMessage({ type: "GET_SETTINGS" }, (res) => {
      const inputEl = document.getElementById("settings-path-input");
      if (res && res.success && res.data) {
        if (inputEl) inputEl.value = res.data.download_dir || "";
        if (res.data.default_bitrate) {
          setBitratePill(res.data.default_bitrate);
        }
      } else {
        if (inputEl) inputEl.value = "Хост не подключен";
      }
      resolve();
    });
  });
}

function showView(viewId) {
  document.querySelectorAll(".view").forEach((v) => v.classList.add("hidden"));
  document.getElementById(viewId)?.classList.remove("hidden");
}

function escapeHtml(str) {
  if (!str) return "";
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}
