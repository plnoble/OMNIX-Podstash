@echo off
cd /d "%~dp0"

where py >nul 2>nul
if %errorlevel%==0 (
  set "PY=py"
) else (
  set "PY=python"
)

%PY% -c "import fastapi,uvicorn,httpx,feedparser" 2>nul
if errorlevel 1 (
  echo [1/2] Installing Python packages...
  %PY% -m pip install -r pc\requirements.txt
  if errorlevel 1 (
    echo Install failed. Need Python 3.9+
    pause
    exit /b 1
  )
)

echo [2/2] Starting OMNIX-Podstash at http://127.0.0.1:8765
echo       Close this window to stop.
echo.
%PY% pc\app.py
if errorlevel 1 pause
