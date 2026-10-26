#Requires -Version 5.1
<#
.SYNOPSIS
    StoryForge Windows 桌面版打包（jpackage app-image）

.DESCRIPTION
    调用 script\build.ps1 完成「前端构建 → static 合并 → Maven 打包」，
    再用 JDK17 自带的 jpackage 打成自带运行时的应用目录：

        dist\StoryForge\
        ├── StoryForge.exe      ← 双击入口（自带 JDK17 运行时，无需本机装 Java）
        ├── app\app.jar         ← 后端 + 前端静态资源（单 jar）
        ├── runtime\            ← 精简 JDK 运行时
        └── （首次启动自动创建 data\：SQLite 库 / 封面 / 临时文件 / 日志）

    桌面版固定使用 sqlite profile：数据库就是 exe 同级 data\storyforge.db，
    整个目录拷贝即迁移，删掉 data\ 即重置。

.PARAMETER Version
    应用版本号，必须是纯数字三段式（如 1.0.0），jpackage 不接受 -SNAPSHOT 后缀。

.PARAMETER SkipBuild
    跳过前端 / Maven 构建，直接用现有 target\app.jar 打包。

.PARAMETER SkipWeb
    传递给 build.ps1：跳过前端构建，复用现有 src\main\resources\static。

.PARAMETER NoConsole
    不显示控制台窗口（日志仍可看 data\logs\）。默认保留控制台，便于定位启动问题。

.EXAMPLE
    .\script\package-win.ps1

.EXAMPLE
    .\script\package-win.ps1 -Version 1.1.0 -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$Version = "1.0.0",
    [switch]$SkipBuild,
    [switch]$SkipWeb,
    [switch]$NoConsole
)

# 原生命令（mvn / jpackage）会把提示写到 stderr，'Stop' 会被误判为终止性错误，
# 统一用 'Continue' + 显式校验 $LASTEXITCODE。
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
}
catch { }

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppHome   = Split-Path -Parent $ScriptDir
$JarPath   = Join-Path $AppHome 'target\app.jar'
$IconPath  = Join-Path $AppHome 'assets\storyforge.ico'
$StageDir  = Join-Path $AppHome 'build\jpackage-input'
$DestDir   = Join-Path $AppHome 'dist'

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Blue }
function Write-Info([string]$Message) { Write-Host "  - $Message" -ForegroundColor Cyan }
function Write-Ok([string]$Message)   { Write-Host "  OK $Message" -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host "  ! $Message" -ForegroundColor Yellow }
function Fail([string]$Message) {
    Write-Host "  X $Message" -ForegroundColor Red
    exit 1
}

Set-Location $AppHome

Write-Host '=============================================='
Write-Host ' StoryForge Windows 桌面版打包'
Write-Host " 工程根目录：$AppHome"
Write-Host " 版本：$Version"
Write-Host '=============================================='

# ---------- 0. 定位 jpackage（JDK17） ----------
Write-Step '检查打包环境'

$jpackage = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
    if (Test-Path $candidate) { $jpackage = $candidate }
}
if (-not $jpackage) {
    foreach ($cand in @(
        "$env:ProgramFiles\Java\jdk-17",
        "${env:ProgramFiles(x86)}\Java\jdk-17",
        'D:\Program Files\Java\jdk-17'
    )) {
        $candidate = Join-Path $cand 'bin\jpackage.exe'
        if (Test-Path $candidate) { $jpackage = $candidate; break }
    }
}
if (-not $jpackage) {
    Fail '找不到 jpackage.exe。桌面版打包需要 JDK 17（内含 jpackage），' +
         '请安装 JDK17 并设置 JAVA_HOME，或从 https://adoptium.net 获取。'
}
Write-Ok "jpackage：$jpackage"

if (-not (Test-Path $IconPath)) {
    Write-Warn "未找到图标 $IconPath，将使用默认 Java 图标（可先运行 script\gen-icon.ps1 生成）"
}

# ---------- 1. 构建（可选） ----------
if ($SkipBuild) {
    Write-Step '跳过构建（-SkipBuild），直接使用现有 jar'
}
else {
    Write-Step '构建（前端 → static 合并 → Maven 打包）'
    $buildScript = Join-Path $ScriptDir 'build.ps1'
    if ($SkipWeb) {
        & $buildScript -SkipWeb
    }
    else {
        & $buildScript
    }
    if ($LASTEXITCODE -ne 0) { Fail '构建失败，详见上方输出' }
}

if (-not (Test-Path $JarPath)) {
    Fail "未找到 $JarPath（是否用了 -SkipBuild 但从未构建过？）"
}
$jar = Get-Item $JarPath
Write-Ok ("jar：target\app.jar（{0:N1} MB，{1:yyyy-MM-dd HH:mm:ss}）" -f ($jar.Length / 1MB), $jar.LastWriteTime)

# ---------- 2. 暂存目录（jpackage 要求 --input 内只有要进 app\ 的文件） ----------
Write-Step '准备 jpackage 输入目录'
if (Test-Path $StageDir) { Remove-Item -Recurse -Force $StageDir }
New-Item -ItemType Directory -Force $StageDir | Out-Null
Copy-Item -Force $JarPath (Join-Path $StageDir 'app.jar')
Write-Ok "build\jpackage-input\app.jar"

# ---------- 3. jpackage app-image ----------
# 模块清单说明：Spring Boot + Tomcat + Actuator + MyBatis + Flyway + SQLite + Jackson
# 需要 java.sql（JDBC）、java.naming / java.management（Tomcat / Actuator）、
# java.desktop（自动打开浏览器）、java.net.http（AI 接口客户端）、jdk.crypto.ec（TLS）、
# jdk.unsupported（HikariCP）、jdk.zipfs / jdk.localedata（中文与压缩包支持）。
# 漏模块的现象是启动到一半 ClassNotFoundException，极难排查，故宁可多列。
$modules = @(
    'java.base'
    'java.compiler'
    'java.desktop'
    'java.instrument'
    'java.logging'
    'java.management'
    'java.naming'
    'java.net.http'
    'java.prefs'
    'java.rmi'
    'java.scripting'
    'java.security.jgss'
    'java.security.sasl'
    'java.sql'
    'java.sql.rowset'
    'java.transaction.xa'
    'java.xml'
    'java.xml.crypto'
    # jdk.management 提供 com.sun.management.GarbageCollectionNotificationInfo，
    # Micrometer 的 JvmGcMetrics 依赖它采集 GC 指标；漏掉只告警不影响启动，
    # 但仪表盘的 JVM 指标会缺一块
    'jdk.management'
    'jdk.crypto.cryptoki'
    'jdk.crypto.ec'
    'jdk.unsupported'
    'jdk.zipfs'
    'jdk.localedata'
) -join ','

$jpArgs = @(
    '--type', 'app-image'
    '--name', 'StoryForge'
    '--app-version', $Version
    '--vendor', 'StoryForge'
    '--description', 'StoryForge AI 长篇小说创作平台（本地桌面版）'
    '--input', $StageDir
    '--main-jar', 'app.jar'
    # Spring Boot 3.5 嵌套 jar 必须走官方加载器入口，直接写业务主类会因
    # BOOT-INF\lib 不在标准 classpath 而失败
    '--main-class', 'org.springframework.boot.loader.launch.JarLauncher'
    '--dest', $DestDir
    '--add-modules', $modules
    '--java-options', '-Xms256m'
    '--java-options', '-Xmx1024m'
    '--java-options', '-Dfile.encoding=UTF-8'
    '--java-options', '-Duser.timezone=Asia/Shanghai'
    # 桌面版固定 sqlite profile：库文件在 exe 同级 data\ 下
    '--java-options', '-Dspring.profiles.active=sqlite'
    '--java-options', '-Dstoryforge.desktop.auto-open=true'
)
if (Test-Path $IconPath) {
    $jpArgs += @('--icon', $IconPath)
}
if (-not $NoConsole) {
    # 保留控制台：启动日志直接可见，排查问题最省事。稳定后可加 -NoConsole 关闭
    $jpArgs += '--win-console'
}

# jpackage 拒绝写入已存在的同名应用目录
$appImage = Join-Path $DestDir 'StoryForge'
if (Test-Path $appImage) {
    Write-Warn "发现已存在的 $appImage，先删除再打包"
    Remove-Item -Recurse -Force $appImage
}

Write-Step 'jpackage 打包（视机器性能 1~3 分钟）'
Write-Info ("命令：jpackage --type app-image --name StoryForge --app-version $Version" +
            $(if ($NoConsole) { ' -NoConsole' } else { ' --win-console' }))
& $jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { Fail "jpackage 失败（退出码 $LASTEXITCODE）" }

$exe = Join-Path $appImage 'StoryForge.exe'
if (-not (Test-Path $exe)) { Fail "打包结束但未找到 $exe" }

$totalMb = (Get-ChildItem -Path $appImage -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Step '打包完成'
Write-Ok "入口：$exe"
Write-Ok ("总大小：{0:N0} MB（含自带 JDK17 运行时）" -f $totalMb)
Write-Info '首次双击启动后会在 exe 同级生成 data\（SQLite 库 / 封面 / 日志），删除即重置'
Write-Info '整目录拷贝到另一台电脑即可运行，无需安装 Java 与数据库'
Write-Info '默认账号 admin / Admin@123456，首次登录强制修改口令'
