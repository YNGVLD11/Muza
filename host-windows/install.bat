@echo off
cd /d "%~dp0"
title Muza Native Host Installer

echo Installing Muza Native Messaging Host...

set "RUNNER_PATH=%~dp0host_runner.bat"
set "RUNNER_ESCAPED=%RUNNER_PATH:\=\\%"
set "MANIFEST_PATH=%~dp0com.muza.host.json"

(
echo {
echo   "name": "com.muza.host",
echo   "description": "Muza Desktop Native Messaging Host",
echo   "path": "%RUNNER_ESCAPED%",
echo   "type": "stdio",
echo   "allowed_origins": [
echo     "chrome-extension://epfekipfpogjjgoabajombjnhologdfc/"
echo   ]
echo }
) > "%MANIFEST_PATH%"

reg add "HKCU\Software\Google\Chrome\NativeMessagingHosts\com.muza.host" /ve /d "%MANIFEST_PATH%" /f >nul
reg add "HKCU\Software\Microsoft\Edge\NativeMessagingHosts\com.muza.host" /ve /d "%MANIFEST_PATH%" /f >nul
reg add "HKCU\Software\Chromium\NativeMessagingHosts\com.muza.host" /ve /d "%MANIFEST_PATH%" /f >nul

echo Muza Native Host registered successfully in Google Chrome, Microsoft Edge, and Chromium.
echo Manifest path: %MANIFEST_PATH%
pause
