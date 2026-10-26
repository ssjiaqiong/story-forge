#Requires -Version 5.1
<#
.SYNOPSIS
    StoryForge Windows 桌面版打包（jpackage app-image 绿色版 / exe 安装包）

.DESCRIPTION
    调用 script\build.ps1 完成「前端构建 → static 合并 → Maven 打包」，
    再用 JDK17 自带的 jpackage 打出产物。两种形态：

    -Type app-image（默认，绿色版，解压即用）：

        dist\StoryForge\
        ├── StoryForge.exe      ← 双击入口（自带 JDK17 运行时，无需本机装 Java）
        ├── app\app.jar         ← 后端 + 前端静态资源（单 jar）
        ├── runtime\            ← 精简 JDK 运行时
        └── data\               ← 首次启动生成（SQLite 库 / 封面 / 临时文件 / 日志）

    -Type exe（安装包，正式发布用）：

        dist\StoryForge-<版本>.exe   ← 安装向导：选目录、开始菜单、桌面快捷方式、可卸载
        数据落在 %APPDATA%\StoryForge\data（安装目录是 %LOCALAPPDATA%\StoryForge，
        两者分离），卸载不会删书稿。
        需要 WiX Toolset 3.x（candle.exe / light.exe）：-WixDir 指定，或装到默认位置，
        或放在仓库同级的 .workbuddy\tools\wix314。WiX 4+ 与 jpackage 17 不兼容。

    桌面版固定使用 sqlite profile：数据库就是 data\storyforge.db。
    绿色版整个目录拷走即迁移；安装版备份 %APPDATA%\StoryForge 即迁移。

.PARAMETER Version
    应用版本号，必须是纯数字三段式（如 1.0.0），jpackage 不接受 -SNAPSHOT 后缀。

.PARAMETER SkipBuild
    跳过前端 / Maven 构建，直接用现有 target\app.jar 打包。

.PARAMETER SkipWeb
    传递给 build.ps1：跳过前端构建，复用现有 src\main\resources\static。

.PARAMETER NoConsole
    不显示控制台窗口（日志仍可看 data\logs\）。默认保留控制台，便于定位启动问题；
    正式发布建议加 -NoConsole，启动失败会用弹窗提示。

.PARAMETER Type
    app-image（默认，绿色版目录）/ exe（安装包）。

.PARAMETER WixDir
    WiX Toolset 3.x 所在目录（含 candle.exe）。不填则自动探测。

.PARAMETER UpgradeUuid
    安装包升级标识。同一应用的所有版本必须一致，升级安装才能覆盖旧版本而不是并存。

.EXAMPLE
    .\script\package-win.ps1

.EXAMPLE
    .\script\package-win.ps1 -Type exe -NoConsole -Version 1.0.0

.EXAMPLE
    .\script\package-win.ps1 -Version 1.1.0 -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$Version = "1.0.0",
    [switch]$SkipBuild,
    [switch]$SkipWeb,
    [switch]$NoConsole,
    [ValidateSet('app-image', 'exe')]
    [string]$Type = 'app-image',
    [string]$WixDir = '',
    [string]$UpgradeUuid = '7b3f0a52-9c41-4e8d-b6a2-5d1c8e7f3a90'
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
# 暂存目录放系统临时区并带时间戳：部分环境（沙箱 / 安全软件）会拦截递归删除，
# 每次新建唯一目录即可绕开「先删再建」，旧目录交给系统清理
$StageDir  = Join-Path $env:TEMP ("storyforge-jpackage-" + (Get-Date -Format 'yyyyMMddHHmmss'))
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
Write-Host " 形态：$Type"
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

# ---------- 0.1 安装包需要 WiX 3.x ----------
if ($Type -eq 'exe') {
    if (-not $WixDir) {
        $wixCandidates = @(
            (Join-Path (Split-Path -Parent $AppHome) '.workbuddy\tools\wix314'),
            $env:WIX,
            "${env:ProgramFiles(x86)}\WiX Toolset v3.14",
            "${env:ProgramFiles(x86)}\WiX Toolset v3.11",
            "$env:ProgramFiles\WiX Toolset v3.14"
        )
        foreach ($cand in $wixCandidates) {
            if ($cand -and (Test-Path (Join-Path $cand 'candle.exe'))) { $WixDir = $cand; break }
        }
    }
    if (-not $WixDir -or -not (Test-Path (Join-Path $WixDir 'candle.exe'))) {
        Fail @'
打包 exe 安装包需要 WiX Toolset 3.x（提供 candle.exe / light.exe）。
  三种解决方式（任选其一）：
  1) 下载免安装包并解压到 <仓库同级>\.workbuddy\tools\wix314
     https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip
  2) 用 -WixDir 指定已有 WiX 目录
  3) 安装 WiX 3.14 到默认路径（Program Files (x86)\WiX Toolset v3.14）
  注意：WiX 4 / 5 与 jpackage 17 不兼容（可执行文件已改名 wix.exe，无 candle/light）。
'@
    }
    # jpackage 通过 PATH 查找 candle.exe / light.exe
    $env:PATH = "$WixDir;$env:PATH"
    Write-Ok "WiX：$WixDir"
}

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
New-Item -ItemType Directory -Force $StageDir | Out-Null
Copy-Item -Force $JarPath (Join-Path $StageDir 'app.jar')
Write-Ok "app.jar → $StageDir"

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
    '--type', $Type
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
if ($Type -eq 'exe') {
    # 安装向导：可选安装目录、开始菜单、桌面快捷方式、当前用户安装（免管理员）
    $jpArgs += @(
        '--win-menu'
        '--win-menu-group', 'StoryForge'
        '--win-shortcut'
        '--win-dir-chooser'
        '--win-per-user-install'
        '--win-upgrade-uuid', $UpgradeUuid
        # 安装版数据落 %APPDATA%\StoryForge\data：与安装目录分离，卸载不删书稿
        '--java-options', '-Dstoryforge.storage=user'
    )
}
if (-not $NoConsole) {
    # 保留控制台：启动日志直接可见，排查问题最省事。稳定后可加 -NoConsole 关闭
    $jpArgs += '--win-console'
}

# jpackage 拒绝覆盖已存在的产物：改名备份而不是删除（安全策略可能拦截递归删除，
# Move 更稳，且便于回滚到上一条产物）
$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$appImage = Join-Path $DestDir 'StoryForge'
if ($Type -eq 'app-image' -and (Test-Path $appImage)) {
    $bak = "$appImage.old-$stamp"
    Write-Warn "已存在的 $appImage 改名为 $bak"
    Move-Item -Force $appImage $bak
}
$installer = Join-Path $DestDir "StoryForge-$Version.exe"
if ($Type -eq 'exe' -and (Test-Path $installer)) {
    $bak = "$installer.old-$stamp"
    Write-Warn "已存在的安装包改名为 $bak"
    Move-Item -Force $installer $bak
}

Write-Step 'jpackage 打包（安装包需再经 WiX 编译 MSI，耗时 2~5 分钟）'
Write-Info ("命令：jpackage --type $Type --name StoryForge --app-version $Version" +
            $(if ($NoConsole) { '（无控制台）' } else { '（保留控制台）' }))
& $jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { Fail "jpackage 失败（退出码 $LASTEXITCODE）" }

Write-Step '打包完成'
if ($Type -eq 'exe') {
    if (-not (Test-Path $installer)) { Fail "打包结束但未找到 $installer" }
    $mb = (Get-Item $installer).Length / 1MB
    $hash = (Get-FileHash $installer -Algorithm SHA256).Hash
    Write-Ok "安装包：$installer"
    Write-Ok ("大小：{0:N0} MB" -f $mb)
    Write-Ok "SHA256：$hash"
    Write-Info '安装到当前用户目录（免管理员权限），含开始菜单与桌面快捷方式'
    Write-Info '数据在 %APPDATA%\StoryForge\data（安装目录是 Local\StoryForge），卸载保留'
}
else {
    $exe = Join-Path $appImage 'StoryForge.exe'
    if (-not (Test-Path $exe)) { Fail "打包结束但未找到 $exe" }
    $totalMb = (Get-ChildItem -Path $appImage -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB
    Write-Ok "入口：$exe"
    Write-Ok ("总大小：{0:N0} MB（含自带 JDK17 运行时）" -f $totalMb)
    Write-Info '首次双击启动后会在 exe 同级生成 data\（SQLite 库 / 封面 / 日志），删除即重置'
    Write-Info '整目录拷贝到另一台电脑即可运行，无需安装 Java 与数据库'
}
Write-Info '默认账号 admin / Admin@123456，首次登录强制修改口令（可用 STORYFORGE_ADMIN_PASSWORD 覆盖）'
