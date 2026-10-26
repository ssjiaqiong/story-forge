@echo off
rem =========================================================================
rem StoryForge remote deploy (Windows cmd wrapper, ASCII only)
rem   Usage: deploy.bat [deploy^|status^|logs^|restart^|stop^|psql] [-Image <ref>] [-FromSource]
rem   Default: pull TCR image ccr.ccs.tencentyun.com/jiaqiong/story-forge:v1.0.0 (no compile)
rem   Config: server.env in this folder
rem =========================================================================
setlocal
set "SCRIPT_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%deploy.ps1" %*
exit /b %ERRORLEVEL%
