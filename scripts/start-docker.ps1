# ============================================================
# 龙骨中台 · Windows 一键启动（Docker 方式）
#
#   1) 确保自定义网络 keel-net 存在
#   2) 确保 MySQL 容器已接入该网络（容器名默认 mysql）
#   3) 拉起后端容器（数据库走容器名 mysql 直连）
#   4) 等待健康检查通过
#
#   用法：powershell -ExecutionPolicy Bypass -File scripts\start-docker.ps1
#
#   环境变量可覆盖默认值：
#     $env:MYSQL_CONTAINER = "mysql"
#     $env:DB_PASSWORD     = "admin"
#     $env:KEEL_PORT      = "8080"
# ============================================================

# 注意：不用 Stop —— docker 会把 WARNING 写到 stderr，Stop 会把它误判为致命错误。
# 这里靠显式检查 $LASTEXITCODE 判断成败（见 Die）。
$ErrorActionPreference = 'Continue'
# 关掉 Invoke-WebRequest 的进度条噪音
$ProgressPreference = 'SilentlyContinue'

$Root          = Split-Path -Parent $PSScriptRoot
$MysqlContainer = if ($env:MYSQL_CONTAINER) { $env:MYSQL_CONTAINER } else { 'mysql' }
$Network       = 'keel-net'
$KeelPort      = if ($env:KEEL_PORT) { $env:KEEL_PORT } else { '8080' }

function Say($msg)  { Write-Host "[keel] $msg" -ForegroundColor Cyan }
function Die($msg)  { Write-Host "[keel] $msg" -ForegroundColor Red; exit 1 }

# ---------- 0. 前置检查 ----------
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  Die '找不到 docker，请先安装并启动 Docker Desktop'
}
$null = docker info 2>$null
if ($LASTEXITCODE -ne 0) { Die 'Docker 未运行，请先启动 Docker Desktop' }

# ---------- 1. MySQL 容器必须在运行 ----------
$mysqlState = docker inspect -f '{{.State.Running}}' $MysqlContainer 2>$null
if (-not $mysqlState) { Die "找不到 MySQL 容器 '$MysqlContainer'，请先创建它" }
if ($mysqlState -ne 'true') { Die "MySQL 容器 '$MysqlContainer' 未运行，请先 docker start $MysqlContainer" }
Say "MySQL 容器 '$MysqlContainer' 运行中"

# ---------- 2. 自定义网络（容器名 DNS 解析必须依赖它）----------
$netExists = docker network ls --filter "name=^${Network}$" -q 2>$null
if (-not $netExists) {
  docker network create $Network 2>$null | Out-Null
  Say "已创建网络 $Network"
}

# ---------- 3. 把 MySQL 接入网络 ----------
$inNet = docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' $MysqlContainer
if ($inNet -notmatch [regex]::Escape($Network)) {
  docker network connect $Network $MysqlContainer
  Say "已把 $MysqlContainer 接入 $Network"
}

# ---------- 4. 拉起后端 ----------
Push-Location $Root
try {
  # compose 的进度输出走 stderr，临时降噪避免被标成错误
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'SilentlyContinue'
  docker compose up -d 2>&1 | Out-String | Write-Host
  $ErrorActionPreference = $prev
} finally {
  Pop-Location
}

# ---------- 5. 等待就绪 ----------
Say '等待后端就绪 ...'
$ready = $false
for ($i = 1; $i -le 60; $i++) {
  try {
    $code = (Invoke-WebRequest -Uri "http://127.0.0.1:$KeelPort/api/health" -UseBasicParsing -TimeoutSec 3).StatusCode
    if ($code -eq 200) { $ready = $true; break }
  } catch { }
  Start-Sleep -Seconds 2
}
if (-not $ready) {
  Say '后端启动超时，请查看日志：docker logs --tail 50 keel'
  exit 1
}

Say '后端已就绪'
Write-Host ''
Write-Host '  ─────────────────────────────────────────────'
Write-Host "   前台入口   http://localhost:$KeelPort"
Write-Host "   后端接口   http://localhost:$KeelPort/api"
Write-Host "   数据库     $MysqlContainer (root) @ ${Network}:3306/scaffold_system"
Write-Host '   账号密码   admin / admin123'
Write-Host '   查看日志   docker logs -f keel'
Write-Host '   停止服务   docker compose down'
Write-Host '  ─────────────────────────────────────────────'
Write-Host ''
