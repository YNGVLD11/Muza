@echo off
cd /d "%~dp0"
echo Registering muza:// protocol handler...
reg add "HKCU\Software\Classes\muza" /ve /d "URL:Muza Protocol" /f
reg add "HKCU\Software\Classes\muza" /v "URL Protocol" /d "" /f
reg add "HKCU\Software\Classes\muza\shell\open\command" /ve /d "wscript.exe \"%~dp0start_host_hidden.vbs\"" /f
echo Done! Protocol muza:// registered.
pause
