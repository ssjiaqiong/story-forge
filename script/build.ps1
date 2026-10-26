#Requires -Version 5.1
<#
.SYNOPSIS
    StoryForge 构建脚本（Windows / PowerShell）

.DESCRIPTION
    构建前端（Vue3 + Vite）→ 合并静态资源 → 打包后端（Spring Boot），产出 target/app.jar。
    前端产物必须在打包前合并进 src/main/resources/static，否则 jar 里没有页面
    （pom.xml 未内置前端构建插件，这是本工程的既有约定）。

.PARAMETER SkipWeb
    跳过前端构建，直接复用现有 src/main/resources/static。

.PARAMETER SkipTypecheck
    前端跳过 vue-tsc 类型检查，只跑 vite build。

.PARAMETER WithTests
    运行单元测试（默认 -DskipTests，因为测试依赖数据库）。

.PARAMETER NoClean
    增量构建，不执行 mvn clean。

.EXAMPLE
    .\script\build.ps1

.EXAMPLE
    .\script\build.ps1 -SkipWeb -NoClean
#>
[CmdletBinding()]
param(
    [switch]$SkipWeb,
    [switch]$SkipTypecheck,
    [switch]$WithTests,
    [switch]$NoClean
)

# 注意：原生命令（java / mvn / npm）会把提示写到 stderr，若用 'Stop' 会被当成
# 终止性错误。这里统一用 'Continue' + 显式校验 $LASTEXITCODE。
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

# 固定 UTF-8 输出：Windows PowerShell 5.1 默认按 GBK 写控制台，重定向到文件/CI 时易乱码
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
}
catch { }

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppHome   = Split-Path -Parent $ScriptDir
$WebDir    = Join-Path $AppHome 'web'
$StaticDir = Join-Path $AppHome 'src\main\resources\static'
$JarPath   = Join-Path $AppHome 'target\app.jar'

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Blue }
function Write-Info([string]$Message) { Write-Host "  - $Message" -ForegroundColor Cyan }
function Write-Ok([string]$Message)   { Write-Host "  OK $Message" -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host "  ! $Message" -ForegroundColor Yellow }
function Fail([string]$Message) {
    Write-Host "  X $Message" -ForegroundColor Red
    exit 1
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = $AppHome,
        [string]$FailureMessage = '命令执行失败'
    )
    Push-Location $WorkingDirectory
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) { Fail "$FailureMessage（退出码 $LASTEXITCODE）" }
    }
    finally { Pop-Location }
}

Set-Location $AppHome

Write-Host '=============================================='
Write-Host ' StoryForge 构建'
Write-Host " 工程根目录：$AppHome"
Write-Host '=============================================='

# ---------- 环境检查 ----------
Write-Step '检查构建环境'

$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCmd) { Fail '未找到 java，请安装 JDK 17+ 并配置 PATH / JAVA_HOME' }

$javaVersionText = (& java -version 2>&1 | Out-String)
if ($javaVersionText -match 'version "(\d+)(?:\.(\d+))?') {
    $javaMajor = [int]$Matches[1]
    if ($javaMajor -eq 1 -and $Matches[2]) { $javaMajor = [int]$Matches[2] }
    if ($javaMajor -lt 17) {
        Fail "检测到 Java $javaMajor，本项目要求 JDK 17+（pom.xml: release=17）"
    }
    Write-Ok "Java $javaMajor"
}
else {
    Write-Warn '无法解析 java 版本，跳过版本校验'
}

$mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvnCmd) { Fail '未找到 mvn，请安装 Maven 3.9+ 并配置 PATH' }
$mvnVersionLine = ((& mvn -v 2>&1 | Out-String) -split "`r?`n" | Where-Object { $_ -match 'Apache Maven' } | Select-Object -First 1)
if (-not $mvnVersionLine) { $mvnVersionLine = 'Apache Maven（版本未知）' }
Write-Ok $mvnVersionLine.Trim()

# ---------- 1. 前端构建 ----------
if ($SkipWeb) {
    Write-Step '跳过前端构建（-SkipWeb）'
    if (-not (Test-Path (Join-Path $StaticDir 'index.html'))) {
        Write-Warn 'static\index.html 不存在，打出来的 jar 将没有前端页面'
    }
}
else {
    Write-Step '构建前端（Vue3 + Vite）'

    $nodeCmd = Get-Command node -ErrorAction SilentlyContinue
    if (-not $nodeCmd) { Fail '未找到 node，请安装 Node 20+ 并配置 PATH' }
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { Fail '未找到 npm，请确认 Node.js 安装完整' }

    $nodeVersion = (& node -v).Trim()
    $nodeMajor = [int]($nodeVersion.TrimStart('v').Split('.')[0])
    if ($nodeMajor -lt 20) { Write-Warn "当前 Node $nodeVersion，建议 Node 20+（低版本 Vite 5 可能构建失败）" }
    Write-Ok "Node $nodeVersion / npm $(& npm -v)"

    if (-not (Test-Path (Join-Path $WebDir 'node_modules'))) {
        Write-Info '未检测到 node_modules，先安装依赖…'
        if (Test-Path (Join-Path $WebDir 'package-lock.json')) {
            Invoke-Checked -Command 'npm' -Arguments @('ci') -WorkingDirectory $WebDir -FailureMessage 'npm ci 失败'
        }
        else {
            Invoke-Checked -Command 'npm' -Arguments @('install') -WorkingDirectory $WebDir -FailureMessage 'npm install 失败'
        }
    }
    else {
        Write-Info 'node_modules 已存在，跳过依赖安装（需重装请手动删除该目录）'
    }

    if ($SkipTypecheck) {
        Write-Info '跳过类型检查（-SkipTypecheck）'
        Invoke-Checked -Command 'npm' -Arguments @('run', 'build') -WorkingDirectory $WebDir -FailureMessage '前端构建失败（npm run build）'
    }
    else {
        Write-Info '执行类型检查 + 构建（npm run build:typecheck）'
        Invoke-Checked -Command 'npm' -Arguments @('run', 'build:typecheck') -WorkingDirectory $WebDir -FailureMessage '前端构建失败（类型检查未通过？可用 -SkipTypecheck 跳过）'
    }

    $distIndex = Join-Path $WebDir 'dist\index.html'
    if (-not (Test-Path $distIndex)) { Fail '前端构建产物缺失：web\dist\index.html' }

    $fileCount = (Get-ChildItem -Path (Join-Path $WebDir 'dist') -Recurse -File).Count
    Write-Ok "前端产物 $fileCount 个文件 -> web\dist"

    Write-Step '合并前端产物到 src\main\resources\static'
    if (-not (Test-Path $StaticDir)) { New-Item -ItemType Directory -Path $StaticDir -Force | Out-Null }
    Copy-Item -Path (Join-Path $WebDir 'dist\*') -Destination $StaticDir -Recurse -Force
    Write-Ok '已同步到 src\main\resources\static（只覆盖，不删除历史 hash 文件）'
}

# ---------- 2. 后端打包 ----------
Write-Step '打包后端（Spring Boot）'
$mvnArgs = @('-B')
if (-not $WithTests) { $mvnArgs += '-DskipTests' }
if (-not $NoClean) { $mvnArgs += 'clean' }
$mvnArgs += 'package'

Write-Info ("命令：mvn " + ($mvnArgs -join ' '))
& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) {
    if (-not $NoClean) {
        Write-Warn '打包失败。若报错为「文件被占用 / cannot delete target\app.jar」，'
        Write-Warn '说明有 StoryForge 进程正在运行并锁定了 jar，请先停止服务再构建。'
    }
    Fail '后端打包失败'
}

if (-not (Test-Path $JarPath)) { Fail "打包完成但未找到 $JarPath" }
$jar = Get-Item $JarPath
Write-Ok ("产物 target\app.jar（{0:N1} MB，{1:yyyy-MM-dd HH:mm:ss}）" -f ($jar.Length / 1MB), $jar.LastWriteTime)

# ---------- 汇总 ----------
Write-Step '构建完成'
Write-Ok '启动：.\script\start.bat   或   .\script\start.ps1'
Write-Info '健康检查：http://127.0.0.1:8080/actuator/health'
