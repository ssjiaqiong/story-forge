#Requires -Version 5.1
# =============================================================================
# StoryForge 远程部署脚本（Windows PowerShell）
#
#   用法：
#     .\script\deploy\deploy.ps1                # 构建 -> 上传 -> 远端 compose 启动 -> 健康检查
#     .\script\deploy\deploy.ps1 -SkipBuild     # 复用已有 target/app.jar
#     .\script\deploy\deploy.ps1 status
#     .\script\deploy\deploy.ps1 logs [-Lines 200]
#     .\script\deploy\deploy.ps1 restart
#     .\script\deploy\deploy.ps1 stop
#
#   配置：同目录 server.env；首次部署自动生成 .env，之后保留远端 .env。
# =============================================================================
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('deploy', 'status', 'logs', 'restart', 'stop', 'psql', 'help')]
    [string]$Action = 'deploy',
    [switch]$SkipBuild,
    [int]$Lines = 200
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppHome   = (Resolve-Path (Join-Path $ScriptDir '..\..')).Path
$Config    = Join-Path $ScriptDir 'server.env'

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host $m -ForegroundColor Green }
function Warn($m) { Write-Host $m -ForegroundColor Yellow }
function Fail($m) { Write-Host "错误：$m" -ForegroundColor Red; exit 1 }

function Invoke-Rsh([string]$RemoteCmd) {
    $all = @()
    foreach ($a in $script:SshArgs) { $all += $a }
    & ssh @all $RemoteCmd
    return $LASTEXITCODE
}

function Invoke-Rcp([string[]]$LocalPaths, [string]$RemotePath) {
    $all = @()
    foreach ($a in $script:ScpArgs) { $all += $a }
    foreach ($p in $LocalPaths) { $all += $p }
    $all += $RemotePath
    & scp @all
    if ($LASTEXITCODE -ne 0) { Fail "scp 上传失败" }
}

if ($Action -eq 'help') {
    Get-Content $MyInvocation.MyCommand.Path | Select-Object -Skip 1 -First 17 | ForEach-Object { $_ -replace '^# ?', '' }
    exit 0
}

# ---------- 读配置 ----------
if (-not (Test-Path $Config)) { Fail "缺少配置文件 $Config" }
$cfg = @{}
Get-Content $Config -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $kv = $line.Split('=', 2)
        $cfg[$kv[0].Trim()] = $kv[1].Trim()
    }
}
foreach ($k in @('HOST')) { if (-not $cfg[$k]) { Fail "server.env 缺少 $k" } }

$Host_   = $cfg['HOST']
$SshUser = if ($cfg['USER']) { $cfg['USER'] } else { 'root' }
$SshPort = if ($cfg['SSH_PORT']) { $cfg['SSH_PORT'] } else { '22' }
$Key     = $cfg['KEY']
$RemoteDir = if ($cfg['REMOTE_DIR']) { $cfg['REMOTE_DIR'] } else { '/opt/storyforge' }
$AppPort = if ($cfg['APP_PORT']) { $cfg['APP_PORT'] } else { '8080' }
$JavaOpts = if ($cfg['JAVA_OPTS']) { $cfg['JAVA_OPTS'] } else { '-Xms128m -Xmx640m' }

if ($Key) {
    if (-not [System.IO.Path]::IsPathRooted($Key)) { $Key = Join-Path $ScriptDir $Key }
    if (-not (Test-Path $Key)) { Fail "SSH 私钥不存在：$Key" }
}

$script:SshArgs = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-o', 'StrictHostKeyChecking=accept-new',
                    '-o', 'PasswordAuthentication=no', '-p', $SshPort)
$script:ScpArgs = @('-q', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-o', 'StrictHostKeyChecking=accept-new',
                    '-o', 'PasswordAuthentication=no', '-P', $SshPort)
if ($Key) { $script:SshArgs += @('-i', $Key); $script:ScpArgs += @('-i', $Key) }
$Remote = "${SshUser}@${Host_}"

switch ($Action) {
    'status' {
        Step "远端状态 $Remote`:$RemoteDir"
        Invoke-Rsh "cd $RemoteDir && docker compose ps && echo '--- health ---' && (curl -sf http://127.0.0.1:$AppPort/actuator/health || echo 'health: 不可用') && echo '--- disk/mem ---' && df -h / | tail -1 && free -m | head -2"
    }
    'logs' {
        Step "应用日志（最近 $Lines 行）"
        Invoke-Rsh "cd $RemoteDir && docker compose logs --tail=$Lines app"
    }
    'restart' {
        Step "重启应用"
        Invoke-Rsh "cd $RemoteDir && docker compose restart app && sleep 3 && docker compose ps"
        Ok "重启完成"
    }
    'stop' {
        Step "停止服务（数据保留在 $RemoteDir/data）"
        Invoke-Rsh "cd $RemoteDir && docker compose down"
        Ok "已停止"
    }
    'psql' {
        Step "进入 PostgreSQL（\q 退出）"
        Invoke-Rsh "cd $RemoteDir && docker compose exec postgres psql -U storyforge -d storyforge"
    }
    'deploy' {
        # ---------- 1. 构建 ----------
        $JarPath = Join-Path $AppHome 'target\app.jar'
        if ($SkipBuild) {
            if (-not (Test-Path $JarPath)) { Fail "-SkipBuild 需要已有 target/app.jar" }
            Warn "跳过构建，使用现有 target/app.jar"
        } else {
            Step "构建（复用 script/build.ps1）"
            & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ScriptDir '..\build.ps1') -SkipTypecheck
            if ($LASTEXITCODE -ne 0) { Fail "构建失败" }
            if (-not (Test-Path $JarPath)) { Fail "构建后未找到 target/app.jar" }
        }
        $jarInfo = Get-Item $JarPath
        Ok ("app.jar = {0:N1} MB" -f ($jarInfo.Length / 1MB))

        # ---------- 2. 准备上传包 ----------
        Step "准备部署包"
        $Staging = Join-Path $AppHome 'target\deploy-staging'
        if (Test-Path $Staging) { Remove-Item -Recurse -Force $Staging -ErrorAction SilentlyContinue }
        New-Item -ItemType Directory -Path $Staging | Out-Null
        Copy-Item $JarPath (Join-Path $Staging 'app.jar')
        Copy-Item (Join-Path $ScriptDir 'remote\Dockerfile') $Staging
        Copy-Item (Join-Path $ScriptDir 'remote\docker-compose.yml') $Staging
        Copy-Item (Join-Path $AppHome 'docker\start.sh') $Staging

        # ---------- 3. 远端目录与 .env ----------
        Step "同步到 $Remote`:$RemoteDir"
        Invoke-Rsh "mkdir -p $RemoteDir/data" | Out-Null
        $envExists = & ssh @($script:SshArgs) "test -f $RemoteDir/.env && echo yes || echo no"
        if ($envExists -match 'yes') {
            Warn "远端已存在 .env，保留不覆盖（密钥不可随意更换）"
        } else {
            Step "首次部署：生成 .env（随机密钥）"
            $JwtSecret    = & ssh @($script:SshArgs) "openssl rand -base64 48 | tr -d '\n'"
            $SecretKey    = & ssh @($script:SshArgs) "openssl rand -base64 48 | tr -d '\n'"
            $DbPassword   = & ssh @($script:SshArgs) "openssl rand -base64 18 | tr -dc 'a-zA-Z0-9' | head -c 24"
            $AdminRaw     = & ssh @($script:SshArgs) "openssl rand -base64 9 | tr -dc 'a-zA-Z0-9' | head -c 12"
            $AdminPassword = "$AdminRaw" + 'Aa1!'
            $envText = @"
APP_PORT=$AppPort
DB_NAME=storyforge
DB_USER=storyforge
DB_PASSWORD=$DbPassword
STORYFORGE_JWT_SECRET=$JwtSecret
STORYFORGE_SECRET_KEY=$SecretKey
STORYFORGE_ADMIN_USERNAME=admin
STORYFORGE_ADMIN_PASSWORD=$AdminPassword
STORYFORGE_ADMIN_FORCE_CHANGE=false
JAVA_OPTS=$JavaOpts
"@
            $envFile = Join-Path $Staging '.env'
            # LF 行尾 + UTF8 无 BOM，避免 .env 解析异常
            [System.IO.File]::WriteAllText($envFile, ($envText -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding $false))
            Invoke-Rcp @($envFile) "${Remote}:$RemoteDir/.env"
            Invoke-Rsh "chmod 600 $RemoteDir/.env" | Out-Null
            Ok "已生成 .env"
            Warn "=============================================================="
            Warn "初始管理员：admin / $AdminPassword"
            Warn "（仅显示这一次，请立即保存并登录后修改）"
            Warn "=============================================================="
        }

        # ---------- 4. 上传 ----------
        Invoke-Rcp @((Join-Path $Staging 'app.jar'), (Join-Path $Staging 'Dockerfile'),
                     (Join-Path $Staging 'docker-compose.yml'), (Join-Path $Staging 'start.sh')) "${Remote}:$RemoteDir/"

        # ---------- 5. 镜像加速 ----------
        $mirrored = & ssh @($script:SshArgs) "grep -q mirror.ccs.tencentyun.com /etc/docker/daemon.json 2>/dev/null && echo yes || echo no"
        if ($mirrored -notmatch 'yes') {
            Step "配置 Docker 镜像加速（腾讯云内网源）"
            Invoke-Rsh "mkdir -p /etc/docker && printf '{`"registry-mirrors`":[`"https://mirror.ccs.tencentyun.com`",`"https://docker.m.daocloud.io`"]}' > /etc/docker/daemon.json && (systemctl restart docker || service docker restart) && sleep 2" | Out-Null
        }

        # ---------- 6. 构建并启动 ----------
        Step "远端构建镜像并启动（compose up -d --build）"
        Invoke-Rsh "cd $RemoteDir && docker compose up -d --build"
        if ($LASTEXITCODE -ne 0) { Fail "compose 启动失败，请执行 deploy.ps1 logs 查看" }

        # ---------- 7. 健康检查 ----------
        Step "健康检查"
        $health = $null
        for ($i = 1; $i -le 40; $i++) {
            $h = & ssh @($script:SshArgs) "curl -sf http://127.0.0.1:$AppPort/actuator/health"
            if ($LASTEXITCODE -eq 0 -and $h) { $health = $h; break }
            Start-Sleep -Seconds 3
        }
        if (-not $health) { Fail "健康检查超时，请执行 deploy.ps1 logs 查看原因" }
        Ok "部署成功：$health"
        Ok "--------------------------------------------------------------"
        Ok "  内网访问：http://127.0.0.1:$AppPort"
        Ok "  公网访问：http://${Host_}:$AppPort"
        Ok "  部署目录：$RemoteDir（数据在 $RemoteDir/data）"
        Ok "  管理命令：deploy.ps1 status | logs | restart | stop | psql"
        Ok "--------------------------------------------------------------"
        Warn "提示：若公网无法访问，请在腾讯云轻量服务器控制台「防火墙」放行 TCP $AppPort"
    }
}

Remove-Item -Recurse -Force $Staging -ErrorAction SilentlyContinue
