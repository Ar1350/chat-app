@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================
echo   佳佳聊天 服务器启动中...
echo   本机访问: http://localhost:3000
echo   局域网访问: http://本机IP:3000
echo   关闭此窗口即可停止服务器
echo ============================================
node server.js
pause
