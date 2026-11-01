@echo off
rem Starts all of VAYORA on Windows: Docker Desktop if it is not running, then every
rem service (rebuilt where the code changed), then the app in the browser.
rem Double-click it, or run "run.bat" from a terminal. stop.bat shuts it down.
setlocal
cd /d "%~dp0"
title VAYORA

rem Docker's command-line tools, if Docker Desktop has not put them on the PATH.
where docker >nul 2>nul
if not errorlevel 1 goto have_docker
if not exist "%ProgramFiles%\Docker\Docker\resources\bin\docker.exe" goto no_docker
set "PATH=%ProgramFiles%\Docker\Docker\resources\bin;%PATH%"
:have_docker

docker info >nul 2>nul
if not errorlevel 1 goto docker_ready
echo Starting Docker Desktop...
start "" "%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
set /a tries=0
:wait_docker
timeout /t 5 /nobreak >nul
docker info >nul 2>nul
if not errorlevel 1 goto docker_ready
set /a tries+=1
if %tries% lss 60 goto wait_docker
echo Docker Desktop did not start within 5 minutes. Open it yourself, then run this again.
goto fail
:docker_ready

echo.
echo Building and starting VAYORA. The very first run downloads and prepares the
echo corridor's road map and can take 10 minutes or more; later runs take seconds.
echo.
docker compose up -d --build --wait
if errorlevel 1 goto not_healthy

rem The ports the services were actually given, in case .env changed them.
set "WEB=5173"
set "API=8080"
for /f "tokens=2 delims=:" %%p in ('docker compose port web 80 2^>nul') do set "WEB=%%p"
for /f "tokens=2 delims=:" %%p in ('docker compose port core-api 8080 2^>nul') do set "API=%%p"

echo.
docker compose ps --format "table {{.Service}}\t{{.Status}}"
echo.
echo VAYORA is running.
echo   App:            http://localhost:%WEB%
echo   API reference:  http://localhost:%API%/swagger-ui.html
echo.
echo   Rides to look at, in a terminal here:
echo       set DEMO_PASSWORD=choose-8-or-more-characters
echo       node scripts\demo-data.mjs
echo   To stop it all:   stop.bat
echo.
start "" "http://localhost:%WEB%"
goto done

:no_docker
echo Docker Desktop is not installed. Install it from
echo https://www.docker.com/products/docker-desktop/ and run this again.
goto fail

:not_healthy
echo.
echo Something did not start. Each service's state:
docker compose ps --all --format "table {{.Service}}\t{{.Status}}"
echo.
echo "docker compose logs SERVICE" shows why, for example: docker compose logs core-api
goto fail

:fail
echo.
pause
exit /b 1

:done
pause
exit /b 0
