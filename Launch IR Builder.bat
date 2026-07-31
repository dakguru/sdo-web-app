@echo off
REM ============================================================
REM  Inspection Report Builder + Sub Divisional Manager — Launcher
REM  Double-click this file to start. Closes when you press any
REM  key in the small window that pops up.
REM ============================================================
title Inspection Report Builder
cd /d "%~dp0"

set IR_PORT=3000

REM start the local server in a separate, minimised window on the chosen port
start "IR Server" /min powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$env:PORT='%IR_PORT%'; & '%~dp0app\Start-Server.ps1'"

REM give the server ~2 seconds to bind the port, then open the browser
ping -n 3 127.0.0.1 >nul
start "" http://localhost:%IR_PORT%/

echo.
echo  ====================================================
echo   Sub Divisional Manager is running at  http://localhost:%IR_PORT%/
echo   Inspection Reports is at              http://localhost:%IR_PORT%/ir-builder/
echo.
echo   Press any key in this window to STOP the server
echo   and close the application.
echo  ====================================================
echo.
pause >nul

REM clean shutdown — kill the powershell listening on our port
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%IR_PORT%" ^| findstr "LISTENING"') do (
  taskkill /F /PID %%a >nul 2>&1
)
exit
