import os
import glob
import uuid
import threading
from typing import Dict, Any, Optional
import yt_dlp
from config import get_config

tasks: Dict[str, Dict[str, Any]] = {}
tasks_lock = threading.Lock()

def get_video_info(url: str) -> Optional[dict]:
    ydl_opts = {
        'skip_download': True,
        'noplaylist': True,
        'quiet': True,
        'no_warnings': True,
        'socket_timeout': 10,
    }
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if info:
                return {
                    "id": info.get("id"),
                    "title": info.get("title", "YouTube Video"),
                    "thumbnail": info.get("thumbnail"),
                    "duration": info.get("duration", 0),
                    "uploader": info.get("uploader", ""),
                }
    except Exception as e:
        print(f"Error fetching video info: {e}")
    return None

def start_download(url: str, bitrate: str = "320") -> str:
    task_id = str(uuid.uuid4())
    cfg = get_config()
    target_dir = cfg.get("download_dir", os.path.expanduser("~/Downloads"))
    os.makedirs(target_dir, exist_ok=True)

    task_info = {
        "id": task_id,
        "url": url,
        "bitrate": bitrate,
        "status": "PENDING",
        "percent": 0.0,
        "status_text": "Подготовка...",
        "target_dir": target_dir,
        "file_path": None,
        "file_name": None,
        "error": None,
    }

    with tasks_lock:
        tasks[task_id] = task_info

    thread = threading.Thread(target=_download_worker, args=(task_id,), daemon=True)
    thread.start()

    return task_id

def get_task_status(task_id: str) -> Optional[dict]:
    with tasks_lock:
        task = tasks.get(task_id)
        return dict(task) if task else None

def _download_worker(task_id: str):
    with tasks_lock:
        task = tasks.get(task_id)
    if not task:
        return

    url = task["url"]
    bitrate = task["bitrate"]
    target_dir = task["target_dir"]

    final_files = []

    def progress_hook(d):
        if d['status'] == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            downloaded = d.get('downloaded_bytes', 0)
            percent = (downloaded / total * 100.0) if total > 0 else 0.0
            with tasks_lock:
                task['status'] = 'DOWNLOADING'
                task['percent'] = round(percent, 1)
                task['status_text'] = f"Скачивание: {int(percent)}%"
        elif d['status'] == 'finished':
            with tasks_lock:
                task['status'] = 'CONVERTING'
                task['percent'] = 99.0
                task['status_text'] = "Конвертация в MP3..."

    def postprocessor_hook(d):
        if d.get('status') == 'finished':
            fp = d.get('info_dict', {}).get('filepath') or d.get('filepath')
            if fp and fp.endswith('.mp3'):
                final_files.append(fp)

    outtmpl = os.path.join(target_dir, '%(title)s.%(ext)s')

    ydl_opts = {
        'format': 'ba/b',
        'outtmpl': outtmpl,
        'postprocessors': [
            {
                'key': 'FFmpegExtractAudio',
                'preferredcodec': 'mp3',
                'preferredquality': bitrate,
            },
            {
                'key': 'FFmpegMetadata',
                'add_metadata': True,
            },
            {
                'key': 'EmbedThumbnail',
            }
        ],
        'writethumbnail': True,
        'socket_timeout': 15,
        'noplaylist': True,
        'quiet': True,
        'no_warnings': True,
        'progress_hooks': [progress_hook],
        'postprocessor_hooks': [postprocessor_hook],
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])

        # Find the resulting mp3 file
        result_file = None
        if final_files and os.path.exists(final_files[-1]):
            result_file = final_files[-1]
        else:
            # Fallback search for recently modified mp3 in target_dir
            mp3_files = glob.glob(os.path.join(target_dir, "*.mp3"))
            if mp3_files:
                result_file = max(mp3_files, key=os.path.getmtime)

        if result_file and os.path.exists(result_file):
            filename = os.path.basename(result_file)
            with tasks_lock:
                task['status'] = 'COMPLETED'
                task['percent'] = 100.0
                task['status_text'] = f"Скачано: {filename}"
                task['file_path'] = os.path.abspath(result_file)
                task['file_name'] = filename
        else:
            with tasks_lock:
                task['status'] = 'FAILED'
                task['error'] = "Файл не был сохранен в папку назначения"
                task['status_text'] = "Ошибка сохранения"

    except Exception as e:
        with tasks_lock:
            task['status'] = 'FAILED'
            task['error'] = str(e)
            task['status_text'] = f"Ошибка: {e}"
