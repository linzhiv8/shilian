@echo off
setlocal
rem 前端在 web/ 子目录里，后端在 server/（这个脚本只起前端）。
cd /d "%~dp0web"

set "NPM=npm"
where npm >nul 2>nul
if errorlevel 1 (
  if exist "D:\app\nodejs\npm.cmd" (
    set "NPM=D:\app\nodejs\npm.cmd"
  ) else (
    echo [ERROR] npm not found. Please install Node.js first.
    pause
    exit /b 1
  )
)

if not exist "node_modules" (
  echo Installing dependencies, this may take a few minutes...
  call "%NPM%" install
  if errorlevel 1 (
    echo [ERROR] npm install failed.
    pause
    exit /b 1
  )
)

echo.
echo   Shilian frontend starting...
echo   Open http://127.0.0.1:5178/ in your browser.
echo   (backend is separate: cd server ^&^& mvn spring-boot:run)
echo.
call "%NPM%" run dev
