@echo off
rem =========================================================================
rem StoryForge remote deploy (Windows cmd wrapper, ASCII only)
rem   Usage: deploy.bat [deploy|status|logs|restart|stop|psql] [--skip-build]
rem   Config: server.env in this folder
rem =========================================================================
setlocal
set "SCRIPT_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%deploy.ps1" %*
exit /b %ERRORLEVEL%
