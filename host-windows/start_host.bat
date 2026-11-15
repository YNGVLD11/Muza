@echo off
cd /d "%~dp0"
title Muza Host

rem Register muza:// URL protocol in HKCU
reg add "HKCU\Software\Classes\muza" /ve /d "URL:Muza Protocol" /f >nul 2>&1
reg add "HKCU\Software\Classes\muza" /v "URL Protocol" /d "" /f >nul 2>&1
reg add "HKCU\Software\Classes\muza\shell\open\command" /ve /d "wscript.exe \"%~dp0start_host_hidden.vbs\"" /f >nul 2>&1

powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 17890 -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }"

echo Starting Muza Host on http://127.0.0.1:17890...
python server.py
if not "%1"=="--no-pause" pause
