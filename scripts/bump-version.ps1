# ============================================================
# 龙骨中台 · 版本号递增（Windows）
#
#   项目根的 VERSION 文件是唯一版本源：前端界面显示、后端 /api/health 回显、
#   镜像 tag 三者都以它为准。本脚本递增 VERSION，并同步 .env 里的镜像 tag。
#
#   用法：
#     powershell -ExecutionPolicy Bypass -File scripts\bump-version.ps1             # 修订号 +1（1.1.0 → 1.1.1）
#     powershell -ExecutionPolicy Bypass -File scripts\bump-version.ps1 -Part minor # 次版本 +1（1.1.0 → 1.2.0）
#     powershell -ExecutionPolicy Bypass -File scripts\bump-version.ps1 -Part major # 主版本 +1（1.1.0 → 2.0.0）
#
#   递增后按提示重建镜像与容器即可。
# ============================================================

param(
  [ValidateSet('patch', 'minor', 'major')][string]$Part = 'patch'
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$VersionFile = Join-Path $Root 'VERSION'

# Windows PowerShell 5.1 按 ANSI 读取无 BOM 的 UTF-8 脚本，中文会变乱码并导致语法错误。
# 这里先把自身重写为带 BOM 的 UTF-8，保证后续（含本行之后的解析）都正常。
$Self = $MyInvocation.MyCommand.Path
if ($Self) {
  $selfText = [System.IO.File]::ReadAllText($Self, [System.Text.Encoding]::UTF8)
  $selfBytes = [System.IO.File]::ReadAllBytes($Self)
  $hasBom = $selfBytes.Length -ge 3 -and $selfBytes[0] -eq 0xEF -and $selfBytes[1] -eq 0xBB -and $selfBytes[2] -eq 0xBF
  if (-not $hasBom) {
    [System.IO.File]::WriteAllText($Self, $selfText, (New-Object System.Text.UTF8Encoding($true)))
  }
}

if (-not (Test-Path $VersionFile)) { throw "找不到版本文件：$VersionFile" }
$current = (Get-Content $VersionFile -Raw).Trim()
if ($current -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
  throw "VERSION 格式不正确：'$current'（应为 x.y.z）"
}

$major = [int]$Matches[1]
$minor = [int]$Matches[2]
$patch = [int]$Matches[3]
switch ($Part) {
  'major' { $major++; $minor = 0; $patch = 0 }
  'minor' { $minor++; $patch = 0 }
  'patch' { $patch++ }
}
$next = "$major.$minor.$patch"

[System.IO.File]::WriteAllText($VersionFile, "$next`n", (New-Object System.Text.UTF8Encoding($false)))

# 同步 .env 里的镜像 tag
$envFile = Join-Path $Root '.env'
if (Test-Path $envFile) {
  # 必须显式指定 UTF8：Get-Content -Raw 默认按 ANSI 读，会把中文写回时弄乱码，
  # 换行也可能被吞掉，导致 KEEL_IMAGE 行被并进注释。
  $text = [System.IO.File]::ReadAllText($envFile, [System.Text.Encoding]::UTF8)
  $text = [regex]::Replace($text, '(?m)^KEEL_IMAGE=keel/keel-admin:.*$', "KEEL_IMAGE=keel/keel-admin:$next")
  [System.IO.File]::WriteAllText($envFile, $text, (New-Object System.Text.UTF8Encoding($false)))
}

Write-Host "版本已更新：$current → $next" -ForegroundColor Cyan
Write-Host ''
Write-Host '请接着执行：' -ForegroundColor Yellow
Write-Host "  cd `"$Root`""
Write-Host "  docker build -f docker/Dockerfile -t keel/keel-admin:$next ``"
Write-Host "    --build-arg KEEL_VERSION=$next ``"
Write-Host "    --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre-jammy ."
Write-Host "  docker compose up -d"
Write-Host ''
Write-Host "校验：curl http://127.0.0.1:8080/api/health   # 应回显 version=v$next" -ForegroundColor DarkGray
