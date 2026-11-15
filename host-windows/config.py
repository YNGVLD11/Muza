import os
import json
from pathlib import Path

APP_DATA_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "Muza"
CONFIG_FILE = APP_DATA_DIR / "config.json"

DEFAULT_DOWNLOAD_DIR = str(Path.home() / "Downloads")
DEFAULT_BITRATE = "320"

def get_config() -> dict:
    APP_DATA_DIR.mkdir(parents=True, exist_ok=True)
    if not CONFIG_FILE.exists():
        default_config = {
            "download_dir": DEFAULT_DOWNLOAD_DIR,
            "default_bitrate": DEFAULT_BITRATE,
        }
        save_config(default_config)
        return default_config

    try:
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
            if "download_dir" not in data or not os.path.exists(data["download_dir"]):
                data["download_dir"] = DEFAULT_DOWNLOAD_DIR
            if "default_bitrate" not in data:
                data["default_bitrate"] = DEFAULT_BITRATE
            return data
    except Exception:
        return {
            "download_dir": DEFAULT_DOWNLOAD_DIR,
            "default_bitrate": DEFAULT_BITRATE,
        }

def save_config(cfg: dict):
    APP_DATA_DIR.mkdir(parents=True, exist_ok=True)
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
