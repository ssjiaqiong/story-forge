#Requires -Version 5.1
<#
.SYNOPSIS
    StoryForge 启动脚本（Windows / PowerShell）

.DESCRIPTION
    启动前后端合一的 Spring Boot 服务（target\app.jar）。未设置的环境变量会补本地开发默认值。

    端口通过命令行参数 --server.port 传给 Spring Boot，优先级高于环境变量，
    避免外部注入的 SERVER__PORT 覆盖配置（本项目开发环境踩过该坑）。

.PARAMETER Port
    监听端口，默认取环境变量 SERVER_PORT，未设置则 8080。

.PARAMETER Profile
    Spring profile，默认取 SPRING_PROFILES_ACTIVE，未设置则 dev。

.PARAMETER SkipDbWait
    跳过数据库就绪等待，直接启动。

.EXAMPLE
    .\script\start.ps1

.EXAMPLE
    .\script\start.ps1 -Port 9090 -Profile prod
#>
[CmdletBinding()]
param(
    [int]$Port = 0,
    [string]$Profile = '',
    [switch]$SkipDbWait
)

# 原生命令（java）会把提示写到 stderr，用 'Stop' 会被当成终止性错误，故用 'Continue'。
$ErrorActionPreference = 'Continue'

# 固定 UTF-8 输出：Windows PowerShell 5.1 默认按 GBK 写控制台，重定向到文件/CI 时易乱码
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
}
catch { }
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppHome   = Split-Path -Parent $ScriptDir

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Blue }
function Write-Info([string]$Message) { Write-Host "  - $Message" -ForegroundColor Cyan }
function Write-Ok([string]$Message)   { Write-Host "  OK $Message" -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host "  ! $Message" -ForegroundColor Yellow }
function Fail([string]$Message) {
    Write-Host "  X $Message" -ForegroundColor Red
    exit 1
}

function Set-DefaultEnv([string]$Name, [string]$Value) {
    if (-not [Environment]::GetEnvironmentVariable($Name) -and -not [Environment]::GetEnvironmentVariable($Name, 'Process')) {
        Set-Item -Path "Env:$Name" -Value $Value
    }
}

function Test-TcpPort {
    param([string]$TargetHost, [int]$TargetPort, [int]$TimeoutMs = 1500)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $ar = $client.BeginConnect($TargetHost, $TargetPort, $null, $null)
        if (-not $ar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        $client.EndConnect($ar)
        return $true
    }
    catch { return $false }
    finally { try { $client.Close() } catch { } }
}

# ---------- 默认配置 ----------
Set-Location $AppHome

$effectivePort = if ($Port -gt 0) { $Port } elseif ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }
$effectiveProfile = if ($Profile) { $Profile } elseif ($env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE } else { 'dev' }

$env:SERVER_PORT = "$effectivePort"
$env:SPRING_PROFILES_ACTIVE = $effectiveProfile
Set-DefaultEnv 'DB_HOST' '127.0.0.1'
Set-DefaultEnv 'DB_PORT' '5432'
Set-DefaultEnv 'DB_NAME' 'storyforge'
Set-DefaultEnv 'DB_USER' 'postgres'
Set-DefaultEnv 'DB_PASSWORD' 'postgres'
Set-DefaultEnv 'STORYFORGE_JWT_SECRET' 'storyforge-local-dev-secret-key-32bytes-minimum-2026'
Set-DefaultEnv 'STORYFORGE_SECRET_KEY' 'storyforge-local-crypto-secret-2026'
Set-DefaultEnv 'STORYFORGE_FILE_ROOT' (Join-Path $AppHome 'data\files')
Set-DefaultEnv 'STORYFORGE_COVER_ROOT' (Join-Path $AppHome 'data\covers')

$javaOpts = if ($env:JAVA_OPTS) { $env:JAVA_OPTS } else { '-Xms256m -Xmx1024m' }
$jarPath = if ($env:APP_JAR) { $env:APP_JAR } else { Join-Path $AppHome 'target\app.jar' }

# ---------- 校验 ----------
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Fail '未找到 java，请安装 JDK 17+ 并配置 PATH / JAVA_HOME'
}
if (-not (Test-Path $jarPath)) {
    Fail "未找到 $jarPath，请先执行 .\script\build.ps1 构建"
}
if ($env:STORYFORGE_JWT_SECRET.Length -lt 32) {
    Fail "STORYFORGE_JWT_SECRET 长度为 $($env:STORYFORGE_JWT_SECRET.Length)，要求 >= 32 字节"
}
if ($env:STORYFORGE_JWT_SECRET -eq 'storyforge-local-dev-secret-key-32bytes-minimum-2026') {
    Write-Warn '正在使用内置开发密钥，仅限本机调试；生产必须通过环境变量替换'
}
if ($env:STORYFORGE_SECRET_KEY.Length -lt 32) {
    Fail 'STORYFORGE_SECRET_KEY 长度要求 >= 32 字节（用于加密 AI/搜索的 API Key，上线后不可更换）'
}

foreach ($dir in @($env:STORYFORGE_FILE_ROOT, $env:STORYFORGE_COVER_ROOT)) {
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
}

if (Test-TcpPort -TargetHost '127.0.0.1' -TargetPort $effectivePort -TimeoutMs 600) {
    Fail "端口 $effectivePort 已被占用，请先停止占用进程或用 -Port 指定其他端口"
}

# ---------- 等数据库 ----------
# SQLite 是嵌入式库，没有监听端口，等下去只会超时
if ($effectiveProfile -match 'sqlite') {
    $SkipDbWait = $true
    Write-Step 'SQLite 为嵌入式数据库，跳过端口就绪等待'
}

if (-not $SkipDbWait) {
    Write-Step "等待数据库 $($env:DB_HOST):$($env:DB_PORT)"
    $attempt = 1
    while (-not (Test-TcpPort -TargetHost $env:DB_HOST -TargetPort ([int]$env:DB_PORT))) {
        if ($attempt -ge 30) {
            Fail '等待数据库超时（60s）。请确认 PostgreSQL 已启动，或核对 DB_HOST/DB_PORT'
        }
        $attempt++
        Start-Sleep -Seconds 2
    }
    Write-Ok '数据库已就绪'
}

# ---------- 启动 ----------
Write-Step '启动 StoryForge'
Write-Info "profile = $effectiveProfile   端口 = $effectivePort"
Write-Info "数据库  = $($env:DB_USER)@$($env:DB_HOST):$($env:DB_PORT)/$($env:DB_NAME)"
Write-Info "文件目录 = $($env:STORYFORGE_FILE_ROOT)"
Write-Info "封面目录 = $($env:STORYFORGE_COVER_ROOT)"
Write-Info "JVM 参数 = $javaOpts"
Write-Ok "访问地址 = http://127.0.0.1:$effectivePort/"
Write-Host ''

$javaArgs = @()
$javaArgs += ($javaOpts -split '\s+' | Where-Object { $_ })
$javaArgs += @('-Dfile.encoding=UTF-8', '-Duser.timezone=Asia/Shanghai', '-jar', $jarPath, "--server.port=$effectivePort")

Write-Host ("  命令：java " + ($javaArgs -join ' ')) -ForegroundColor DarkGray
Write-Host ''

& java @javaArgs
$code = $LASTEXITCODE
Write-Host ''
if ($code -eq 0) { Write-Info '服务已正常退出' } else { Write-Warn "服务异常退出，退出码 $code" }
exit $code
