import os
import sys
import subprocess
from typing import Optional
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import uvicorn
import requests

from config import get_config, save_config
from downloader import get_video_info, start_download, get_task_status, get_active_task

app = FastAPI(title="Muza Desktop Host", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class DownloadRequest(BaseModel):
    url: str
    bitrate: Optional[str] = "320"
    title: Optional[str] = ""
    thumbnail: Optional[str] = ""

class SettingsRequest(BaseModel):
    download_dir: Optional[str] = None
    default_bitrate: Optional[str] = None

class OpenFileRequest(BaseModel):
    path: str

@app.get("/health")
def health():
    return {"status": "ok", "app": "Muza Host", "version": "1.0.0"}

@app.get("/info")
def fetch_info(url: str = Query(...)):
    info = get_video_info(url)
    if not info:
        raise HTTPException(status_code=400, detail="Не удалось получить информацию о видео")
    return info

@app.post("/download")
def download(req: DownloadRequest):
    if not req.url:
        raise HTTPException(status_code=400, detail="Отсутствует URL видео")
    task_id = start_download(req.url, req.bitrate or "320", req.title or "", req.thumbnail or "")
    return {"task_id": task_id}

@app.get("/tasks/active")
def active_task():
    task = get_active_task()
    return task or {}

@app.get("/tasks/{task_id}")
def task_status(task_id: str):
    task = get_task_status(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Задача не найдена")
    return task

@app.get("/settings")
def get_current_settings():
    return get_config()

@app.post("/settings")
def update_settings(req: SettingsRequest):
    cfg = get_config()
    if req.download_dir:
        cleaned_path = os.path.abspath(os.path.expanduser(req.download_dir.strip()))
        try:
            os.makedirs(cleaned_path, exist_ok=True)
            cfg["download_dir"] = cleaned_path
        except Exception as e:
            raise HTTPException(status_code=400, detail=f"Недопустимый путь к папке: {e}")
    if req.default_bitrate:
        cfg["default_bitrate"] = req.default_bitrate
    save_config(cfg)
    return cfg

@app.post("/select-directory")
def select_directory():
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
                return {"selected_path": cfg["download_dir"]}
    except Exception as e:
        print(f"Directory picker error: {e}")
    return {"selected_path": cfg.get("download_dir")}

@app.post("/open-file")
def open_file_in_explorer(req: OpenFileRequest):
    file_path = os.path.normpath(req.path)
    if os.path.exists(file_path):
        subprocess.Popen(f'explorer /select,"{file_path}"')
        return {"status": "opened", "path": file_path}
    elif os.path.exists(os.path.dirname(file_path)):
        os.startfile(os.path.dirname(file_path))
        return {"status": "opened_dir", "path": os.path.dirname(file_path)}
    raise HTTPException(status_code=404, detail="Файл или папка не найдены")

@app.post("/open-directory")
def open_directory(req: OpenFileRequest):
    dir_path = os.path.normpath(req.path)
    if os.path.exists(dir_path):
        os.startfile(dir_path)
        return {"status": "opened", "path": dir_path}
    raise HTTPException(status_code=404, detail="Папка не найдена")

@app.get("/check-updates")
def check_updates():
    try:
        r = requests.get(
            "https://api.github.com/repos/YNGVLD11/Muza/releases/latest",
            headers={"User-Agent": "Muza-Desktop"},
            timeout=5
        )
        if r.status_code == 200:
            data = r.json()
            return {
                "latest_version": data.get("tag_name"),
                "release_name": data.get("name"),
                "html_url": data.get("html_url"),
                "published_at": data.get("published_at"),
            }
    except Exception as e:
        print(f"Update check error: {e}")
    return {"error": "Не удалось проверить обновления"}

if __name__ == "__main__":
    port = 17890
    print(f"Starting Muza Host on http://127.0.0.1:{port}...")
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="info")
