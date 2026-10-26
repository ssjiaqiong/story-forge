@echo off
setlocal EnableExtensions
title StoryForge

rem ============================================================================
rem  StoryForge start script (Windows / cmd)
rem
rem  Requires target\app.jar: run script\build.bat first.
rem
rem  Usage:
rem    script\start.bat                  port 8080, profile dev
rem    script\start.bat --port 9090      custom port
rem    script\start.bat --profile prod   custom Spring profile
rem    script\start.bat --no-wait        skip database wait
rem    script\start.bat --help
rem
rem  Env vars (local dev defaults are filled in when unset):
rem    DB_HOST=127.0.0.1  DB_PORT=5432  DB_NAME=storyforge
rem    DB_USER=postgres   DB_PASSWORD=postgres
rem    STORYFORGE_JWT_SECRET / STORYFORGE_SECRET_KEY  built-in dev keys
rem    JAVA_OPTS=-Xms256m -Xmx1024m
rem
rem  The port is passed as --server.port on the command line so that an
rem  injected SERVER__PORT env var cannot override it (known pitfall here).
rem
rem  NOTE: messages are ASCII on purpose. Chinese UTF-8 text inside a .bat file
rem        shifts cmd.exe line offsets and makes it execute partial lines.
rem        Use start.ps1 if you prefer Chinese output.
rem ============================================================================

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%.." >nul 2>&1
if errorlevel 1 goto :err_home
for %%i in ("%CD%") do set "APP_HOME=%%~fi"

set "PORT_ARG="
set "PROFILE_ARG="
set "WAIT_DB=1"

:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--port" goto :opt_port
if /i "%~1"=="--profile" goto :opt_profile
if /i "%~1"=="--no-wait" goto :opt_nowait
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--help" goto :usage
echo    X Unknown argument: %~1
echo      Run --help for usage.
goto :err_exit

:opt_port
if "%~2"=="" goto :err_port_value
set "PORT_ARG=%~2"
shift
shift
goto :parse

:opt_profile
if "%~2"=="" goto :err_profile_value
set "PROFILE_ARG=%~2"
shift
shift
goto :parse

:opt_nowait
set "WAIT_DB=0"
shift
goto :parse

:parsed
rem ---------- defaults ----------
if defined PORT_ARG set "SERVER_PORT=%PORT_ARG%"
if not defined SERVER_PORT set "SERVER_PORT=8080"
if defined PROFILE_ARG set "SPRING_PROFILES_ACTIVE=%PROFILE_ARG%"
if not defined SPRING_PROFILES_ACTIVE set "SPRING_PROFILES_ACTIVE=dev"
if not defined DB_HOST set "DB_HOST=127.0.0.1"
if not defined DB_PORT set "DB_PORT=5432"
if not defined DB_NAME set "DB_NAME=storyforge"
if not defined DB_USER set "DB_USER=postgres"
if not defined DB_PASSWORD set "DB_PASSWORD=postgres"
if not defined STORYFORGE_JWT_SECRET set "STORYFORGE_JWT_SECRET=storyforge-local-dev-secret-key-32bytes-minimum-2026"
if not defined STORYFORGE_SECRET_KEY set "STORYFORGE_SECRET_KEY=storyforge-local-crypto-secret-2026"
if not defined STORYFORGE_FILE_ROOT set "STORYFORGE_FILE_ROOT=%APP_HOME%\data\files"
if not defined STORYFORGE_COVER_ROOT set "STORYFORGE_COVER_ROOT=%APP_HOME%\data\covers"
if not defined JAVA_OPTS set "JAVA_OPTS=-Xms256m -Xmx1024m"
if not defined APP_JAR set "APP_JAR=%APP_HOME%\target\app.jar"

rem ---------- checks ----------
where java >nul 2>&1
if errorlevel 1 goto :err_java
if not exist "%APP_JAR%" goto :err_jar

set "JWT_LEN=0"
set "JWT_TMP=%STORYFORGE_JWT_SECRET%"
:jwt_len_loop
if not defined JWT_TMP goto :jwt_len_done
set "JWT_TMP=%JWT_TMP:~1%"
set /a JWT_LEN+=1
goto :jwt_len_loop
:jwt_len_done
if %JWT_LEN% LSS 32 goto :err_jwt

set "SK_LEN=0"
set "SK_TMP=%STORYFORGE_SECRET_KEY%"
:sk_len_loop
if not defined SK_TMP goto :sk_len_done
set "SK_TMP=%SK_TMP:~1%"
set /a SK_LEN+=1
goto :sk_len_loop
:sk_len_done
if %SK_LEN% LSS 32 goto :err_sk

if not exist "%STORYFORGE_FILE_ROOT%" mkdir "%STORYFORGE_FILE_ROOT%" >nul 2>&1
if not exist "%STORYFORGE_COVER_ROOT%" mkdir "%STORYFORGE_COVER_ROOT%" >nul 2>&1

rem ---------- port availability ----------
powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient;$a=$c.BeginConnect('127.0.0.1',%SERVER_PORT%,$null,$null);if(-not $a.AsyncWaitHandle.WaitOne(600)){exit 1};try{$c.EndConnect($a);exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 goto :err_port_busy

rem ---------- wait for database ----------
rem SQLite is embedded: there is no port to wait for
echo %SPRING_PROFILES_ACTIVE% | findstr /i "sqlite" >nul
if not errorlevel 1 set "WAIT_DB=0"
if "%WAIT_DB%"=="0" goto :db_ready
echo ==^> Waiting for database %DB_HOST%:%DB_PORT%
set /a DB_TRY=1
:wait_db
powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient;$a=$c.BeginConnect('%DB_HOST%',%DB_PORT%,$null,$null);if(-not $a.AsyncWaitHandle.WaitOne(1500)){exit 1};try{$c.EndConnect($a);exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 goto :db_ready
if %DB_TRY% GEQ 30 goto :err_db_timeout
set /a DB_TRY+=1
timeout /t 2 /nobreak >nul
goto :wait_db

:db_ready
if "%WAIT_DB%"=="1" echo      OK database is ready
echo.

rem ---------- start ----------
echo ==============================================
echo  StoryForge
echo ==============================================
echo      profile  = %SPRING_PROFILES_ACTIVE%   port = %SERVER_PORT%
echo      database = %DB_USER%@%DB_HOST%:%DB_PORT%/%DB_NAME%
echo      files    = %STORYFORGE_FILE_ROOT%
echo      covers   = %STORYFORGE_COVER_ROOT%
echo      jvm opts = %JAVA_OPTS%
echo      url      = http://127.0.0.1:%SERVER_PORT%/
echo.
echo   Press Ctrl+C to stop.
echo.

java %JAVA_OPTS% -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -jar "%APP_JAR%" --server.port=%SERVER_PORT%
set "RUN_CODE=%ERRORLEVEL%"
echo.
if "%RUN_CODE%"=="0" echo     Service exited normally.
if not "%RUN_CODE%"=="0" echo     Service exited with code %RUN_CODE%.
popd >nul 2>&1
endlocal
exit /b %RUN_CODE%

rem ---------- usage ----------
:usage
echo StoryForge start script
echo.
echo   script\start.bat                  port 8080, profile dev
echo   script\start.bat --port 9090      custom port
echo   script\start.bat --profile prod   custom profile
echo   script\start.bat --no-wait        skip database wait
echo.
popd >nul 2>&1
endlocal
exit /b 0

rem ---------- errors ----------
:err_java
echo   X java not found. Install JDK 17+ and set PATH / JAVA_HOME.
goto :err_exit
:err_jar
echo   X %APP_JAR% not found. Run script\build.bat first.
goto :err_exit
:err_jwt
echo   X STORYFORGE_JWT_SECRET length is %JWT_LEN%, at least 32 required.
goto :err_exit
:err_sk
echo   X STORYFORGE_SECRET_KEY length is %SK_LEN%, at least 32 required.
goto :err_exit
:err_port_busy
echo   X Port %SERVER_PORT% is already in use. Stop it or use --port.
goto :err_exit
:err_db_timeout
echo   X Database wait timed out after 60s. Check PostgreSQL and DB_HOST/DB_PORT.
goto :err_exit
:err_port_value
echo   X --port requires a value, e.g. --port 9090
goto :err_exit
:err_profile_value
echo   X --profile requires a value, e.g. --profile prod
goto :err_exit
:err_home
echo   X Cannot enter project root: %SCRIPT_DIR%..
exit /b 1
:err_exit
popd >nul 2>&1
endlocal
exit /b 1
