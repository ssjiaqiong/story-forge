@echo off
rem ===========================================================================
rem StoryForge Windows desktop packaging (thin wrapper around package-win.ps1)
rem
rem Usage:  script\package-win.bat [options]
rem   -Version 1.0.0   app version (digits only, jpackage rejects -SNAPSHOT)
rem   -SkipBuild       reuse existing target\app.jar
rem   -SkipWeb         skip frontend build, reuse src\main\resources\static
rem   -NoConsole       hide console window
rem
rem This file must stay pure ASCII: cmd reads .bat as OEM codepage, any
rem non-ASCII byte shifts subsequent lines and breaks argument parsing.
rem ===========================================================================
setlocal

cd /d "%~dp0.."

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package-win.ps1" %*
if errorlevel 1 (
    echo.
    echo [package-win.bat] packaging FAILED, see output above.
    exit /b 1
)

exit /b 0
