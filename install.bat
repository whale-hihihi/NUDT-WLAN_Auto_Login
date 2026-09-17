@echo off
chcp 65001 >nul
echo ============================================
echo  NUDT 校园网自动登录 - 安装开机自启
echo ============================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup_tasks.ps1"
echo.
pause
