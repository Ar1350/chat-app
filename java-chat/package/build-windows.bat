@echo off
rem =====================================================================
rem  JiaJiaChat Windows build script (JDK 21 required)
rem  Produces:
rem    dist\jj-chat.jar                         cross-platform jar
rem    dist\JiaJiaChat\                         portable app with bundled JRE
rem    dist\JiaJiaChat-windows-x64.zip
rem    dist\jiajia-chat-1.0.0\                  jar + launcher scripts
rem    dist\jiajia-chat-1.0.0-crossplatform.zip
rem =====================================================================
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0.."

if defined JAVA_HOME ( set "BIN=%JAVA_HOME%\bin" ) else ( set "BIN=" )
if not exist "%BIN%\javac.exe" (
  for /f "delims=" %%i in ('where javac 2^>nul') do set "BIN=%%~dpi"
)
if not exist "%BIN%\javac.exe" (
  echo [ERROR] JDK 21 not found. Install JDK 21 or set JAVA_HOME.
  pause & exit /b 1
)
echo Using JDK at: %BIN%

if exist out rmdir /s /q out
mkdir out 2>nul
echo [1/5] Compiling...
dir /s /b src\*.java > sources.txt
"%BIN%\javac.exe" -encoding UTF-8 -d out @sources.txt
if errorlevel 1 ( echo [ERROR] compile failed & pause & exit /b 1 )
del sources.txt

echo [2/5] Building jar...
if not exist dist mkdir dist
echo Main-Class: jj.client.ChatApp> manifest.mf
"%BIN%\jar.exe" cfm dist\jj-chat.jar manifest.mf -C out jj
del manifest.mf

if not exist dist\jardir mkdir dist\jardir
copy /y dist\jj-chat.jar dist\jardir\ >nul

echo [3/5] jpackage app-image ^(bundled JRE^)...
if exist dist\JiaJiaChat rmdir /s /q dist\JiaJiaChat
"%BIN%\jpackage.exe" --type app-image --dest dist --name JiaJiaChat --app-version 1.0.0 ^
  --vendor JiaJiaChat --copyright JiaJiaChat ^
  --input dist\jardir --main-jar jj-chat.jar --main-class jj.client.ChatApp ^
  --icon package\icons\icon.ico --java-options "-Dfile.encoding=UTF-8" ^
  --add-launcher "client=package\win-launchers\client.properties" ^
  --add-launcher "server=package\win-launchers\server.properties" ^
  --add-launcher "admin=package\win-launchers\admin.properties"
if errorlevel 1 ( echo [ERROR] jpackage failed & pause & exit /b 1 )
powershell -NoProfile -ExecutionPolicy Bypass -File package\rename-launchers.ps1

echo [4/5] Staging cross-platform bundle...
if exist dist\jiajia-chat-1.0.0 rmdir /s /q dist\jiajia-chat-1.0.0
mkdir dist\jiajia-chat-1.0.0
copy /y dist\jj-chat.jar dist\jiajia-chat-1.0.0\ >nul
copy /y package\windows\*.bat dist\jiajia-chat-1.0.0\ >nul
xcopy /e /i /y package\linux dist\jiajia-chat-1.0.0\linux\ >nul
xcopy /e /i /y package\mac dist\jiajia-chat-1.0.0\mac\ >nul
copy /y package\icons\icon.png dist\jiajia-chat-1.0.0\linux\ >nul

echo [5/5] Zipping...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path dist\JiaJiaChat -DestinationPath dist\JiaJiaChat-windows-x64.zip -Force; Compress-Archive -Path dist\jiajia-chat-1.0.0 -DestinationPath dist\jiajia-chat-1.0.0-crossplatform.zip -Force"

echo.
echo ==== BUILD OK ====
echo   Portable Windows: dist\JiaJiaChat-windows-x64.zip
echo   Cross-platform : dist\jiajia-chat-1.0.0-crossplatform.zip
pause
