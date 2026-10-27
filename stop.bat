@echo off
rem Stops every JOURNEX service. Data is kept: run.bat brings it all back as it was.
setlocal
cd /d "%~dp0"
where docker >nul 2>nul
if errorlevel 1 set "PATH=%ProgramFiles%\Docker\Docker\resources\bin;%PATH%"
docker compose down
echo.
echo JOURNEX is stopped. Your data is kept for next time.
pause
