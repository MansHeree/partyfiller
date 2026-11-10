@echo off
title PartyFiller
setlocal
cd /d "%~dp0"

rem Seconds to wait before sending the launch command (HeadlessMC loads the account async)
set "LAUNCH_DELAY=10"
set "MC_VERSION=fabric:26.1.2"

echo Checking for updates...
set "HMC_JAR="
set "JAVA="
for /f "usebackq delims=" %%L in (`powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\setup.ps1"`) do (
    if not defined HMC_JAR (set "HMC_JAR=%%L") else set "JAVA=%%L"
)
if not defined JAVA (
    echo.
    echo Setup failed - see the messages above.
    pause
    exit /b 1
)

cd /d "%~dp0HeadlessMC"

rem No saved account yet: open HeadlessMC so you can log in.
set "ACCOUNTS=HeadlessMC\auth\.accounts.json"
call :has_account || (
    echo.
    echo ==============================================
    echo   No account saved yet. In the prompt below:
    echo     1. type  login  and sign in as the BOT account
    echo     2. type  quit   when it says you're logged in
    echo ==============================================
    "%JAVA%" --enable-native-access=ALL-UNNAMED -jar "%HMC_JAR%"
    call :has_account || ( echo Still no account - run start.bat again to retry. & pause & exit /b 1 )
)

set /a PINGS=%LAUNCH_DELAY%+1
echo.
echo ==============================================
echo   PartyFiller running headless (no window).
echo   Close THIS window to stop the bot.
echo ==============================================
echo.

:loop
( ping -n %PINGS% 127.0.0.1 ^>nul ^& echo launch %MC_VERSION% -lwjgl --game-args "--quickPlayMultiplayer mc.hypixel.net:25565" ) | "%JAVA%" --enable-native-access=ALL-UNNAMED -jar "%HMC_JAR%"

echo.
echo [PartyFiller stopped] Restarting in 15s... close this window to stop for good.
ping -n 16 127.0.0.1 >nul
goto loop

:has_account
if not exist "%ACCOUNTS%" exit /b 1
for %%A in ("%ACCOUNTS%") do if %%~zA LSS 10 exit /b 1
exit /b 0
