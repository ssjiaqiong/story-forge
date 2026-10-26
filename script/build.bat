@echo off
setlocal EnableExtensions
title StoryForge Build

rem ============================================================================
rem  StoryForge build script (Windows / cmd)
rem
rem  Output: target\app.jar  -- frontend static files are merged into the jar,
rem          so the app runs on a single port.
rem
rem  Usage:
rem    script\build.bat                     build frontend + backend
rem    script\build.bat --skip-web          backend only, reuse existing static
rem    script\build.bat --skip-typecheck    skip vue-tsc type check
rem    script\build.bat --with-tests        run unit tests
rem    script\build.bat --no-clean          incremental build
rem    script\build.bat --help
rem
rem  Requires: JDK 17+, Maven 3.9+, Node 20+ on PATH.
rem
rem  NOTE: messages are ASCII on purpose. Chinese UTF-8 text inside a .bat file
rem        shifts cmd.exe line offsets and makes it execute partial lines.
rem        Use build.ps1 if you prefer Chinese output.
rem ============================================================================

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%.." >nul 2>&1
if errorlevel 1 goto :err_home
for %%i in ("%CD%") do set "APP_HOME=%%~fi"

set "SKIP_WEB=0"
set "SKIP_TYPECHECK=0"
set "WITH_TESTS=0"
set "NO_CLEAN=0"

:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--skip-web" goto :opt_skip_web
if /i "%~1"=="--skip-typecheck" goto :opt_skip_typecheck
if /i "%~1"=="--with-tests" goto :opt_with_tests
if /i "%~1"=="--no-clean" goto :opt_no_clean
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--help" goto :usage
echo    X Unknown argument: %~1
echo      Run --help for usage.
goto :err_exit

:opt_skip_web
set "SKIP_WEB=1"
shift
goto :parse
:opt_skip_typecheck
set "SKIP_TYPECHECK=1"
shift
goto :parse
:opt_with_tests
set "WITH_TESTS=1"
shift
goto :parse
:opt_no_clean
set "NO_CLEAN=1"
shift
goto :parse

:parsed
echo ==============================================
echo  StoryForge build
echo  project root: %APP_HOME%
echo ==============================================
echo.

echo [1/4] Checking build environment
where java >nul 2>&1
if errorlevel 1 goto :err_java
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i /c:"version"') do if not defined JAVA_VER set "JAVA_VER=%%~v"
if not defined JAVA_VER set "JAVA_VER=unknown"
echo      Java %JAVA_VER%
echo %JAVA_VER% | findstr /b /c:"1." >nul
if not errorlevel 1 goto :err_java_old

where mvn >nul 2>&1
if errorlevel 1 goto :err_mvn
for /f "tokens=3" %%v in ('mvn -v 2^>^&1 ^| findstr /i /c:"Apache Maven"') do if not defined MVN_VER set "MVN_VER=%%v"
if not defined MVN_VER set "MVN_VER=unknown"
echo      Apache Maven %MVN_VER%

if "%SKIP_WEB%"=="1" goto :env_ready
where node >nul 2>&1
if errorlevel 1 goto :err_node
where npm >nul 2>&1
if errorlevel 1 goto :err_npm
for /f "delims=" %%v in ('node -v') do if not defined NODE_VER set "NODE_VER=%%v"
echo      Node %NODE_VER%
:env_ready
echo.

if "%SKIP_WEB%"=="1" goto :skip_web

rem ---------- 2. frontend ----------
echo [2/4] Building frontend  [Vue3 + Vite]
pushd "%APP_HOME%\web" >nul 2>&1
if errorlevel 1 goto :err_web

if exist node_modules goto :deps_ready
echo      - node_modules not found, installing dependencies...
if exist package-lock.json goto :npm_ci
call npm install
if errorlevel 1 goto :err_npm_install
goto :deps_done
:npm_ci
call npm ci
if errorlevel 1 goto :err_npm_install
goto :deps_done
:deps_ready
echo      - node_modules found, skip install
:deps_done

if "%SKIP_TYPECHECK%"=="1" goto :build_no_tc
echo      - type check + build: npm run build:typecheck
call npm run build:typecheck
if errorlevel 1 goto :err_web
goto :build_web_done
:build_no_tc
echo      - build without type check: npm run build
call npm run build
if errorlevel 1 goto :err_web
:build_web_done

if not exist "dist\index.html" goto :err_web_dist
popd >nul 2>&1
echo      OK web\dist generated
echo.

echo [3/4] Merging web\dist into src\main\resources\static
robocopy "%APP_HOME%\web\dist" "%APP_HOME%\src\main\resources\static" /E /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto :err_copy
echo      OK static files synced (overwrite only, no delete)
goto :build_backend

:skip_web
echo [2/4] Skipping frontend build  [--skip-web]
if not exist "%APP_HOME%\src\main\resources\static\index.html" echo      ! static\index.html missing, jar will have no UI
echo.

rem ---------- 4. backend ----------
:build_backend
echo [4/4] Packaging backend  [Spring Boot]
set "MVN_ARGS=-B"
if "%WITH_TESTS%"=="0" set "MVN_ARGS=%MVN_ARGS% -DskipTests"
if "%NO_CLEAN%"=="0" set "MVN_ARGS=%MVN_ARGS% clean"
set "MVN_ARGS=%MVN_ARGS% package"
echo      command: mvn %MVN_ARGS%
call mvn %MVN_ARGS%
if errorlevel 1 goto :err_pkg

if not exist "%APP_HOME%\target\app.jar" goto :err_jar
echo.
for %%f in ("%APP_HOME%\target\app.jar") do echo      OK target\app.jar  %%~zf bytes  %%~tf
echo.
echo ==============================================
echo  Build finished
echo    start: script\start.bat   or   powershell -File script\start.ps1
echo    health: http://127.0.0.1:8080/actuator/health
echo ==============================================
popd >nul 2>&1
endlocal
exit /b 0

rem ---------- usage ----------
:usage
echo StoryForge build script
echo.
echo   script\build.bat                    build frontend + backend
echo   script\build.bat --skip-web         backend only
echo   script\build.bat --skip-typecheck   skip vue-tsc type check
echo   script\build.bat --with-tests       run unit tests
echo   script\build.bat --no-clean         incremental build
echo.
echo   Requires: JDK 17+, Maven 3.9+, Node 20+
popd >nul 2>&1
endlocal
exit /b 0

rem ---------- errors ----------
:err_java
echo   X java not found. Install JDK 17+ and set PATH / JAVA_HOME.
goto :err_exit
:err_java_old
echo   X Java %JAVA_VER% detected. JDK 17+ is required.
goto :err_exit
:err_mvn
echo   X mvn not found. Install Maven 3.9+ and set PATH.
goto :err_exit
:err_node
echo   X node not found. Install Node 20+ and set PATH.
goto :err_exit
:err_npm
echo   X npm not found. Check your Node.js installation.
goto :err_exit
:err_npm_install
popd >nul 2>&1
echo   X Failed to install frontend dependencies.
goto :err_exit
:err_web
popd >nul 2>&1
echo   X Frontend build failed. For type errors try --skip-typecheck.
goto :err_exit
:err_web_dist
popd >nul 2>&1
echo   X Missing build output: web\dist\index.html
goto :err_exit
:err_copy
echo   X Failed to sync web\dist. Check write permission on static folder.
goto :err_exit
:err_pkg
echo   X Backend packaging failed.
echo     If it says "cannot delete target\app.jar" or "file in use", a running
echo     StoryForge process still holds the jar. Stop it and retry.
goto :err_exit
:err_jar
echo   X Packaging succeeded but target\app.jar is missing.
goto :err_exit
:err_home
echo   X Cannot enter project root: %SCRIPT_DIR%..
exit /b 1
:err_exit
popd >nul 2>&1
endlocal
exit /b 1
