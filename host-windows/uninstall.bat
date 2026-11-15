@echo off
title Muza Native Host Uninstaller

echo Uninstalling Muza Native Messaging Host...

reg delete "HKCU\Software\Google\Chrome\NativeMessagingHosts\com.muza.host" /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Edge\NativeMessagingHosts\com.muza.host" /f >nul 2>&1
reg delete "HKCU\Software\Chromium\NativeMessagingHosts\com.muza.host" /f >nul 2>&1

echo Muza Native Host removed from browser registries.
pause
