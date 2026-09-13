@echo off
rem ============================================
rem  佳佳聊天 · 客户端（Windows 免安装 Java 的绿色版请用上层 JiaJiaChat 目录里的 佳佳聊天.exe）
rem  本脚本需要系统已安装 JDK/JRE 21，适合用 jar 自行启动
rem  可选参数:  启动聊天窗口.bat --host=192.168.1.10 --port=9300
rem ============================================
chcp 65001 >nul
cd /d "%~dp0"

set "JAR=jj-chat.jar"
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"
) else (
  set "JAVA_EXE=javaw"
)

where java >nul 2>&1
if errorlevel 1 (
  if not exist "%JAVA_HOME%\bin\javaw.exe" (
    echo [错误] 未找到 Java 21。请安装 JDK 21，或直接使用绿色版 佳佳聊天.exe
    pause
    exit /b 1
  )
)

start "" "%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%" %*
exit /b 0
