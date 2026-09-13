@echo off
rem ============================================
rem  佳佳聊天 · 服务端（控制台程序，关闭窗口即停止服务）
rem  端口固定 9300；数据保存在当前目录 data\ 下
rem ============================================
chcp 65001 >nul
cd /d "%~dp0"

set "JAR=jj-chat.jar"
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java"
)

where java >nul 2>&1
if errorlevel 1 (
  if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 未找到 Java 21。请先安装 JDK 21（或使用绿色版目录中的 佳佳聊天-服务端.exe）
    pause
    exit /b 1
  )
)

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -cp "%JAR%" jj.server.ChatServer %*
echo.
echo 服务端已退出。
pause
