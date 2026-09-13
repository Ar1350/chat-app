@echo off
rem ============================================
rem  佳佳聊天 · 管理后台
rem  可选参数:  启动管理后台.bat --host=192.168.1.10
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
    echo [错误] 未找到 Java 21。请安装 JDK 21，或使用绿色版 佳佳管理后台.exe
    pause
    exit /b 1
  )
)

start "" "%JAVA_EXE%" -Dfile.encoding=UTF-8 -cp "%JAR%" jj.client.AdminApp %*
exit /b 0
