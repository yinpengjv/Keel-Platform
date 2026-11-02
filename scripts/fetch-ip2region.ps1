# ============================================================
# 龙骨中台 · 下载 ip2region 离线库数据文件（Windows）
#
#   IP 归属地查询依赖 ip2region 的 xdb 数据文件（合计约 48MB），
#   二进制文件不适合入库，因此不入 git；克隆代码后执行本脚本补齐。
#
#   用法：
#     powershell -ExecutionPolicy Bypass -File scripts\fetch-ip2region.ps1
#
#   注意：这是**可选**步骤。
#   - 直接跑镜像部署：不需要，xdb 已打包在镜像里。
#   - 从源码构建镜像：不需要，Dockerfile 会自动兜底下载。
#   本脚本只是让你能提前把文件放进源码树。
# ============================================================

$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 按 ANSI 读取无 BOM 的 UTF-8 脚本会乱码，先自写 BOM
$Self = $MyInvocation.MyCommand.Path
if ($Self) {
    $text = [System.IO.File]::ReadAllText($Self, [System.Text.Encoding]::UTF8)
    $bytes = [System.IO.File]::ReadAllBytes($Self)
    $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    if (-not $hasBom) {
        [System.IO.File]::WriteAllText($Self, $text, (New-Object System.Text.UTF8Encoding($true)))
    }
}

$Root = Split-Path -Parent $PSScriptRoot
$TargetDir = Join-Path $Root 'scaffold-system\scaffold-framework\src\main\resources\ip2region'

# 国内网络直连 GitHub 不稳，依次尝试多个镜像
$Mirrors = @(
    'https://ghproxy.net/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data',
    'https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data',
    'https://gh-proxy.com/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data',
    'https://gitee.com/lionsoul/ip2region/raw/master/data'
)

# 远端期望大小（字节）。ip2region 上游文件大小是稳定的，
# 用它做完整性校验，避免下载中断产生「看着有、其实残缺」的文件 ——
# 残缺文件不会报错，但会让约 1/3 的 IPv6 查询读越界、归属地显示未知。
$Expected = @{
    'ip2region_v4.xdb' = 11122714
    'ip2region_v6.xdb' = 37277813
}

if (-not (Test-Path $TargetDir)) {
    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
}

function Get-RemoteFile {
    param([string]$Name, [string]$Dest)

    $expect = $Expected[$Name]

    # 已存在且大小与远端一致则跳过
    if (Test-Path $Dest) {
        $cur = (Get-Item $Dest).Length
        if ($cur -eq $expect) {
            Write-Host ("  OK  {0} 已存在且完整（{1:N0} 字节），跳过" -f $Name, $cur) -ForegroundColor DarkGray
            return $true
        }
        Write-Host ("  !   {0} 已存在但大小不符（当前 {1:N0} / 期望 {2:N0}），重新下载" -f $Name, $cur, $expect) -ForegroundColor Yellow
        Remove-Item $Dest -Force
    }

    foreach ($mirror in $Mirrors) {
        $url = "$mirror/$Name"
        Write-Host "  -> 尝试 $url"
        try {
            Invoke-WebRequest -Uri $url -OutFile $Dest -UseBasicParsing -TimeoutSec 600
            $size = (Get-Item $Dest).Length
            if ($size -eq $expect) {
                Write-Host ("  OK  {0} 下载完成（{1:N0} 字节，完整性校验通过）" -f $Name, $size) -ForegroundColor Green
                return $true
            }
            Write-Host ("  !   {0} 大小不符（{1:N0} / 期望 {2:N0}），可能下载不完整，换源重试" -f $Name, $size, $expect) -ForegroundColor Yellow
        } catch {
            # 换下一个镜像
        }
        if (Test-Path $Dest) { Remove-Item $Dest -Force }
    }
    Write-Host "  X   $Name 下载失败，请手动访问 https://github.com/lionsoul2014/ip2region 获取" -ForegroundColor Red
    return $false
}

Write-Host "下载 ip2region 离线库到：$TargetDir"
$ok = $true
$ok = (Get-RemoteFile -Name 'ip2region_v4.xdb' -Dest (Join-Path $TargetDir 'ip2region_v4.xdb')) -and $ok
$ok = (Get-RemoteFile -Name 'ip2region_v6.xdb' -Dest (Join-Path $TargetDir 'ip2region_v6.xdb')) -and $ok

if (-not $ok) {
    Write-Host '下载未全部成功，请检查网络后重试。' -ForegroundColor Red
    exit 1
}
Write-Host '完成。现在可以执行：docker build -f docker/Dockerfile -t keel/keel-admin:<版本> .' -ForegroundColor Cyan
