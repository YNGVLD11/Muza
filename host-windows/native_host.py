import sys
import os
import struct
import json
import threading
import subprocess
import glob
import re
import requests
import yt_dlp

# Force raw binary mode for stdin and stdout on Windows
if sys.platform == "win32":
    import msvcrt
    msvcrt.setmode(sys.stdin.fileno(), os.O_BINARY)
    msvcrt.setmode(sys.stdout.fileno(), os.O_BINARY)

raw_stdin = sys.stdin.buffer
raw_stdout = sys.stdout.buffer

# Divert any print/logging to stderr so stdout is purely for Native Messaging
sys.stdout = sys.stderr

from config import get_config, save_config

send_lock = threading.Lock()
tasks_lock = threading.Lock()
cancelled_tasks = set()

def send_msg(msg: dict):
    with send_lock:
        try:
            payload = json.dumps(msg, ensure_ascii=False).encode('utf-8')
            raw_stdout.write(struct.pack('<I', len(payload)))
            raw_stdout.write(payload)
            raw_stdout.flush()
        except Exception as e:
            sys.stderr.write(f"Send error: {e}\n")

def read_msg():
    try:
        raw_len = raw_stdin.read(4)
        if len(raw_len) < 4:
            return None
        msg_len = struct.unpack('<I', raw_len)[0]
        if msg_len <= 0:
            return None
        data = raw_stdin.read(msg_len)
        if len(data) < msg_len:
            return None
        return json.loads(data.decode('utf-8'))
    except Exception as e:
        sys.stderr.write(f"Read error: {e}\n")
        return None

def clean_error_text(text: str) -> str:
    ansi_regex = re.compile(r'\x1b\[[0-9;]*[a-zA-Z]')
    cleaned = ansi_regex.sub('', text)
    cleaned = re.sub(r'^\s*ERROR:\s*', '', cleaned)
    return cleaned.strip()

def get_video_info(url: str):
    ydl_opts = {
        'skip_download': True,
        'noplaylist': True,
        'quiet': True,
        'no_warnings': True,
        'socket_timeout': 10,
        'js_runtimes': {'node': {}},
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
        sys.stderr.write(f"Error fetching info: {e}\n")
    return None

def select_directory_dialog():
    cfg = get_config()
    current_dir = cfg.get("download_dir", "")
    escaped_dir = current_dir.replace("'", "''") if current_dir else ""
    ps_cmd = (
        "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8;\n"
        "try {\n"
        "    $csharp = @'\n"
        "    using System;\n"
        "    using System.Runtime.InteropServices;\n"
        "    public class Win32 {\n"
        "        [DllImport(\"kernel32.dll\")]\n"
        "        public static extern ushort SetThreadUILanguage(ushort LangId);\n"
        "    }\n"
        "'@;\n"
        "    Add-Type -TypeDefinition $csharp -ErrorAction SilentlyContinue | Out-Null;\n"
        "    [Win32]::SetThreadUILanguage(1049) | Out-Null;\n"
        "} catch {}\n"
        "[System.Threading.Thread]::CurrentThread.CurrentUICulture = 'ru-RU';\n"
        "[System.Threading.Thread]::CurrentThread.CurrentCulture = 'ru-RU';\n"
        "[System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms') | Out-Null;\n"
        "$f = New-Object System.Windows.Forms.FolderBrowserDialog;\n"
        "$f.Description = 'Выберите папку для сохранения MP3';\n"
        "$f.ShowNewFolderButton = $true;\n"
        + (f"$f.SelectedPath = '{escaped_dir}';\n" if current_dir and os.path.exists(current_dir) else "")
        + "if ($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::WriteLine($f.SelectedPath) }\n"
    )
    try:
        res = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps_cmd],
            capture_output=True,
            text=True,
            encoding="utf-8"
        )
        selected = res.stdout.strip()
        if selected:
            norm_selected = os.path.abspath(selected)
            if os.path.exists(norm_selected):
                cfg["download_dir"] = norm_selected
                save_config(cfg)
                return cfg["download_dir"]
    except Exception as e:
        sys.stderr.write(f"Directory picker error: {e}\n")
    return cfg.get("download_dir")

def open_file_in_explorer(file_path: str):
    try:
        norm = os.path.normpath(file_path)
        if os.path.isdir(norm):
            os.startfile(norm)
            return True
        elif os.path.exists(norm):
            subprocess.Popen(f'explorer /select,"{norm}"')
            return True
        elif os.path.exists(os.path.dirname(norm)):
            os.startfile(os.path.dirname(norm))
            return True
    except Exception as e:
        sys.stderr.write(f"Open file error: {e}\n")
    return False

def check_updates_api():
    try:
        r = requests.get(
            "https://api.github.com/repos/YNGVLD11/Muza/releases/latest",
            headers={"User-Agent": "Muza-NativeHost"},
            timeout=5
        )
        if r.status_code == 200:
            data = r.json()
            return {
                "latest_version": data.get("tag_name"),
                "release_name": data.get("name"),
                "html_url": data.get("html_url"),
            }
    except Exception as e:
        sys.stderr.write(f"Check updates error: {e}\n")
    return None

def download_worker(task: dict):
    task_id = task["id"]
    url = task["url"]
    bitrate = task.get("bitrate", "320")
    target_dir = task.get("target_dir") or get_config().get("download_dir")
    os.makedirs(target_dir, exist_ok=True)

    final_files = []

    def progress_hook(d):
        with tasks_lock:
            if task_id in cancelled_tasks:
                raise Exception("Загрузка отменена")
        if d['status'] == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            downloaded = d.get('downloaded_bytes', 0)
            percent = (downloaded / total * 100.0) if total > 0 else 0.0
            task['status'] = 'DOWNLOADING'
            task['percent'] = round(percent, 1)
            task['status_text'] = f"Скачивание: {int(percent)}%"
            send_msg({"action": "TASK_UPDATE", "task": task})
        elif d['status'] == 'finished':
            task['status'] = 'CONVERTING'
            task['percent'] = 99.0
            task['status_text'] = "Конвертация в MP3..."
            send_msg({"action": "TASK_UPDATE", "task": task})

    def postprocessor_hook(d):
        if d.get('status') == 'finished':
            fp = d.get('info_dict', {}).get('filepath') or d.get('filepath')
            if fp and fp.endswith('.mp3'):
                final_files.append(fp)

    outtmpl = os.path.join(target_dir, f'%(title)s_{bitrate}k.%(ext)s')

    ydl_opts = {
        'format': 'bestaudio/best',
        'outtmpl': outtmpl,
        'js_runtimes': {'node': {}},
        'http_headers': {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
            'Accept-Language': 'ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7',
        },
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

        result_file = None
        if final_files and os.path.exists(final_files[-1]):
            result_file = final_files[-1]
        else:
            mp3_files = glob.glob(os.path.join(target_dir, f"*_{bitrate}k.mp3"))
            if not mp3_files:
                mp3_files = glob.glob(os.path.join(target_dir, "*.mp3"))
            if mp3_files:
                result_file = max(mp3_files, key=os.path.getmtime)

        if result_file and os.path.exists(result_file):
            filename = os.path.basename(result_file)
            task['status'] = 'COMPLETED'
            task['percent'] = 100.0
            task['status_text'] = f"Скачано: {filename}"
            task['file_path'] = os.path.abspath(result_file)
            task['file_name'] = filename
            send_msg({"action": "TASK_UPDATE", "task": task})
        else:
            task['status'] = 'FAILED'
            task['error'] = "Файл не был сохранен в папку назначения"
            task['status_text'] = "Ошибка сохранения"
            send_msg({"action": "TASK_UPDATE", "task": task})

    except Exception as e:
        err_msg = clean_error_text(str(e))
        task['status'] = 'FAILED'
        task['error'] = err_msg
        task['status_text'] = f"Ошибка: {err_msg}"
        send_msg({"action": "TASK_UPDATE", "task": task})

def main():
    while True:
        req = read_msg()
        if req is None:
            break

        action = req.get("action")
        req_id = req.get("request_id")

        if action == "PING":
            send_msg({"action": "PONG", "request_id": req_id, "status": "ok"})

        elif action == "GET_SETTINGS":
            cfg = get_config()
            send_msg({"action": "SETTINGS_RESULT", "request_id": req_id, "data": cfg})

        elif action == "SAVE_SETTINGS":
            cfg = get_config()
            if "download_dir" in req:
                p = os.path.abspath(os.path.expanduser(req["download_dir"].strip()))
                try:
                    os.makedirs(p, exist_ok=True)
                    cfg["download_dir"] = p
                except Exception:
                    pass
            if "default_bitrate" in req:
                cfg["default_bitrate"] = req["default_bitrate"]
            save_config(cfg)
            send_msg({"action": "SETTINGS_RESULT", "request_id": req_id, "data": cfg})

        elif action == "SELECT_DIRECTORY":
            chosen = select_directory_dialog()
            send_msg({"action": "DIRECTORY_RESULT", "request_id": req_id, "selected_path": chosen})

        elif action == "OPEN_FILE":
            path = req.get("path", "")
            success = open_file_in_explorer(path)
            send_msg({"action": "FILE_OPENED", "request_id": req_id, "success": success})

        elif action == "OPEN_DIRECTORY":
            target_dir = req.get("path") or get_config().get("download_dir")
            success = open_file_in_explorer(target_dir)
            send_msg({"action": "DIRECTORY_OPENED", "request_id": req_id, "success": success})

        elif action == "CHECK_UPDATES":
            res = check_updates_api()
            send_msg({"action": "UPDATES_RESULT", "request_id": req_id, "data": res})

        elif action == "GET_INFO":
            url = req.get("url", "")
            info = get_video_info(url)
            send_msg({"action": "INFO_RESULT", "request_id": req_id, "data": info})

        elif action == "CANCEL_DOWNLOAD":
            task_id = req.get("task_id")
            if task_id:
                with tasks_lock:
                    cancelled_tasks.add(task_id)
            send_msg({"action": "CANCEL_RESULT", "request_id": req_id, "success": True})

        elif action == "START_DOWNLOAD":
            task_info = {
                "id": req.get("task_id", "task-1"),
                "url": req.get("url", ""),
                "title": req.get("title", "YouTube Audio"),
                "thumbnail": req.get("thumbnail", ""),
                "bitrate": req.get("bitrate", "320"),
                "status": "DOWNLOADING",
                "percent": 0.0,
                "status_text": "Подготовка...",
                "target_dir": get_config().get("download_dir"),
                "file_path": None,
                "file_name": None,
                "error": None,
            }
            send_msg({"action": "TASK_UPDATE", "task": task_info})
            thread = threading.Thread(target=download_worker, args=(task_info,), daemon=True)
            thread.start()

if __name__ == "__main__":
    main()
