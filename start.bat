@echo off
title PartyFiller
setlocal

rem ---- settings -------------------------------------------------------------
rem Minecraft version HeadlessMC should launch (Fabric + the mods in .minecraft\mods)
set "MC_VERSION=fabric:26.1.2"
rem Seconds to wait before sending the launch command (HeadlessMC loads the account async)
set "LAUNCH_DELAY=10"
rem Java 25. Put the full path to java.exe in java-path.txt to use a specific one.
set "JAVA=java"
if exist "%~dp0java-path.txt" set /p JAVA=<"%~dp0java-path.txt"
rem ---------------------------------------------------------------------------

for /f "usebackq delims=" %%J in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\get-headlessmc.ps1"`) do set "HMC_JAR=%%J"
if not defined HMC_JAR (
    echo Could not get HeadlessMC. Check your internet connection.
    pause
    exit /b 1
)

cd /d "%~dp0HeadlessMC"
set /a PINGS=%LAUNCH_DELAY%+1

echo ==============================================
echo   PartyFiller - Hypixel dungeon party filler
echo   %HMC_JAR%  /  %MC_VERSION%
echo   Starts headless (no game window).
echo   Close THIS window to stop the bot.
echo ==============================================
echo.

:loop
( ping -n %PINGS% 127.0.0.1 ^>nul ^& echo launch %MC_VERSION% -lwjgl --game-args "--quickPlayMultiplayer mc.hypixel.net:25565" ) | "%JAVA%" --enable-native-access=ALL-UNNAMED -jar "%HMC_JAR%"

echo.
echo [PartyFiller stopped] Restarting in 15s... close this window to stop for good.
ping -n 16 127.0.0.1 >nul
goto loop
