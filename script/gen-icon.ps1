# =============================================================================
# 生成 StoryForge 应用图标（PNG + ICO），无需任何外部工具与网络。
#
# 用法：powershell -File script/gen-icon.ps1
# 产物：assets/storyforge.png（512x512）、assets/storyforge.ico（48/256 双入口）
#
# ICO 内嵌 PNG 数据：Vista 起 Windows 资源管理器与 jpackage 均支持，
# 免去为每个尺寸各画一张 BMP 的麻烦。
# =============================================================================
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::InvariantCulture

$root = Split-Path -Parent $PSScriptRoot
$assetsDir = Join-Path $root 'assets'
if (-not (Test-Path $assetsDir)) { New-Item -ItemType Directory -Force $assetsDir | Out-Null }

$SIZE = 512
$bmp = New-Object System.Drawing.Bitmap($SIZE, $SIZE, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb))
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.Clear([System.Drawing.Color]::Transparent)

# ---- 1) 圆角方形底：靛蓝 → 紫的斜向渐变 ----
$rect = New-Object System.Drawing.Rectangle(0, 0, $SIZE, $SIZE)
$brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    $rect,
    ([System.Drawing.ColorTranslator]::FromHtml('#4338CA')),
    ([System.Drawing.ColorTranslator]::FromHtml('#7C3AED')),
    ([System.Drawing.Drawing2D.LinearGradientMode]::ForwardDiagonal))
$radius = 108
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.AddArc(0, 0, $radius, $radius, 180, 90)
$path.AddArc($SIZE - $radius, 0, $radius, $radius, 270, 90)
$path.AddArc($SIZE - $radius, $SIZE - $radius, $radius, $radius, 0, 90)
$path.AddArc(0, $SIZE - $radius, $radius, $radius, 90, 90)
$path.CloseFigure()
$g.FillPath($brush, $path)
$path.Dispose()
$brush.Dispose()

# ---- 2) 翻开的书：两页奶油色纸 + 中缝 + 页线 ----
$ink = [System.Drawing.ColorTranslator]::FromHtml('#F5F3FF')
$bookPath = New-Object System.Drawing.Drawing2D.GraphicsPath
$cx = $SIZE / 2.0
$topY = 168.0
$botY = 372.0
$halfW = 118.0
# 左页（外沿略低，模拟摊开）
$bookPath.AddBezier($cx, $topY + 26, $cx - $halfW * 0.55, $topY - 12, $cx - $halfW * 0.9, $topY - 4, $cx - $halfW, $topY + 22)
$bookPath.AddBezier($cx - $halfW, $topY + 22, $cx - $halfW * 0.75, $botY - 14, $cx - $halfW * 0.35, $botY + 8, $cx, $botY - 8)
# 右页镜像
$bookPath.AddBezier($cx, $botY - 8, $cx + $halfW * 0.35, $botY + 8, $cx + $halfW * 0.75, $botY - 14, $cx + $halfW, $topY + 22)
$bookPath.AddBezier($cx + $halfW, $topY + 22, $cx + $halfW * 0.9, $topY - 4, $cx + $halfW * 0.55, $topY - 12, $cx, $topY + 26)
$bookPath.CloseFigure()
$inkBrush = New-Object System.Drawing.SolidBrush($ink)
$g.FillPath($inkBrush, $bookPath)

# 页线（淡紫）
$linePen = New-Object System.Drawing.Pen(([System.Drawing.Color]::FromArgb(120, 76, 29, 149)), 7)
$linePen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$linePen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
foreach ($side in -1, 1) {
    for ($i = 0; $i -lt 3; $i++) {
        $x0 = $cx + $side * 34
        $x1 = $cx + $side * 92
        $y0 = $topY + 74 + $i * 42
        $y1 = $topY + 66 + $i * 44
        $g.DrawLine($linePen, [float]$x0, [float]$y0, [float]$x1, [float]$y1)
    }
}
$linePen.Dispose()

# 中缝
$spinePen = New-Object System.Drawing.Pen(([System.Drawing.Color]::FromArgb(255, 199, 210, 254)), 5)
$g.DrawLine($spinePen, [float]$cx, [float]($topY + 22), [float]$cx, [float]($botY - 10))
$spinePen.Dispose()
$inkBrush.Dispose()
$bookPath.Dispose()

# ---- 3) 金色星火（灵感） ----
$gold = [System.Drawing.ColorTranslator]::FromHtml('#FBBF24')
$star = New-Object System.Drawing.Drawing2D.GraphicsPath
$sc = 26.0
$star.AddLine($cx, 96 - $sc, $cx + $sc * 0.24, 96 - $sc * 0.24)
$star.AddLine($cx + $sc * 0.24, 96 - $sc * 0.24, $cx + $sc, 96)
$star.AddLine($cx + $sc, 96, $cx + $sc * 0.24, 96 + $sc * 0.24)
$star.AddLine($cx + $sc * 0.24, 96 + $sc * 0.24, $cx, 96 + $sc)
$star.AddLine($cx, 96 + $sc, $cx - $sc * 0.24, 96 + $sc * 0.24)
$star.AddLine($cx - $sc * 0.24, 96 + $sc * 0.24, $cx - $sc, 96)
$star.AddLine($cx - $sc, 96, $cx - $sc * 0.24, 96 - $sc * 0.24)
$star.CloseFigure()
$goldBrush = New-Object System.Drawing.SolidBrush($gold)
$g.FillPath($goldBrush, $star)
$goldBrush.Dispose()
$star.Dispose()

# ---- 4) 保存 PNG ----
$pngPath = Join-Path $assetsDir 'storyforge.png'
$bmp.Save($pngPath, ([System.Drawing.Imaging.ImageFormat]::Png))

# ---- 5) 包装成 ICO（48 与 256 两个 PNG 入口）----
$icoPath = Join-Path $assetsDir 'storyforge.ico'
$pngFull = [System.IO.File]::ReadAllBytes($pngPath)

$bmp48 = New-Object System.Drawing.Bitmap($bmp, 48, 48)
$ms48 = New-Object System.IO.MemoryStream
$bmp48.Save($ms48, ([System.Drawing.Imaging.ImageFormat]::Png))
$png48 = $ms48.ToArray()
$bmp48.Dispose()
$ms48.Dispose()

$ms = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter($ms)
# ICONDIR：保留 0、类型 1（图标）、2 个入口
$bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]2)
# ICONDIRENTRY x2：宽高（256 记 0）、色板 0、保留 0、位平面 1、位深 32、字节数、偏移
$offset = 6 + 16 * 2
foreach ($img in @($png48, $pngFull)) {
    $dim = if ($img.Length -eq $pngFull.Length) { 256 } else { 48 }
    $bw.Write([byte]$(if ($dim -eq 256) { 0 } else { $dim }))
    $bw.Write([byte]$(if ($dim -eq 256) { 0 } else { $dim }))
    $bw.Write([byte]0)   # 调色板颜色数
    $bw.Write([byte]0)   # 保留
    $bw.Write([uint16]1) # 位平面
    $bw.Write([uint16]32) # 位深
    $bw.Write([uint32]$img.Length)
    $bw.Write([uint32]$offset)
    $offset += $img.Length
}
$bw.Write($png48)
$bw.Write($pngFull)
$bw.Flush()
[System.IO.File]::WriteAllBytes($icoPath, $ms.ToArray())
$bw.Close()
$ms.Close()

$g.Dispose()
$bmp.Dispose()
Write-Output ("OK  {0} ({1} bytes)" -f $pngPath, $pngFull.Length)
Write-Output ("OK  {0} ({1} bytes)" -f $icoPath, (Get-Item $icoPath).Length)
