@echo off
chcp 65001 >nul
title 佳佳聊天 · 聊天窗口
cd /d %~dp0

where javac >nul 2>nul
if errorlevel 1 (
  echo [错误] 未检测到 Java 环境，请先安装 JDK 后重新打开本脚本。
  pause
  exit /b 1
)

if not exist out mkdir out
dir /s /b src\*.java > "%TEMP%\jj_sources.txt" 2>nul
javac -encoding UTF-8 -d out @"%TEMP%\jj_sources.txt"
if errorlevel 1 (
  echo [错误] 编译失败，请检查代码。
  pause
  exit /b 1
)

start "" javaw -cp out jj.client.ChatApp
