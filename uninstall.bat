@echo off
chcp 65001 >nul
echo ============================================
echo  NUDT 校园网自动登录 - 卸载开机自启
echo ============================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0remove_tasks.ps1"
echo.
pause
