# ============================================================
# 龙骨中台 · 清理 Docker 构建残留（Windows）
#
#   反复构建镜像会累积两类垃圾：
#     ① 悬空镜像（<none>）—— 同名 tag 被新构建顶替，旧镜像失去引用
#     ② 构建缓存 —— 每层 npm/maven 下载与编译产物都留在 BuildKit 里
#
#   用法：
#     powershell -ExecutionPolicy Bypass -File scripts\clean-docker.ps1
#     powershell -ExecutionPolicy Bypass -File scripts\clean-docker.ps1 -All
#     powershell -ExecutionPolicy Bypass -File scripts\clean-docker.ps1 -DryRun
#
#   不会碰：正在运行的容器、具名卷（mysql-data）、带 tag 的镜像。
# ============================================================

param(
    [switch]$All,
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'
$KeepCache = '1GB'

# Windows PowerShell 5.1 按 ANSI 读无 BOM 的 UTF-8 会乱码，先自写 BOM
$Self = $MyInvocation.MyCommand.Path
if ($Self) {
    $bytes = [System.IO.File]::ReadAllBytes($Self)
    $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    if (-not $hasBom) {
        $text = [System.IO.File]::ReadAllText($Self, [System.Text.Encoding]::UTF8)
        [System.IO.File]::WriteAllText($Self, $text, (New-Object System.Text.UTF8Encoding($true)))
    }
}

Write-Host '=== 清理前 ===' -ForegroundColor Cyan
docker system df

Write-Host ''
Write-Host '=== 悬空镜像（<none>） ===' -ForegroundColor Cyan
$dangling = @(docker images -f 'dangling=true' -q)
Write-Host ("共 {0} 个" -f $dangling.Count)
if ($dangling.Count -gt 0 -and -not $DryRun) {
    docker image prune -f | Out-Null
    Write-Host '已清理'
}

Write-Host ''
Write-Host '=== 构建缓存 ===' -ForegroundColor Cyan
if ($All) {
    Write-Host '模式：全部清理（下次构建需要重新下载依赖，会明显变慢）'
    if (-not $DryRun) {
        docker builder prune -af | Out-Null
        Write-Host '已清理'
    }
} else {
    Write-Host ("模式：保留最近 {0}（常用缓存留着，下次构建仍然快）" -f $KeepCache)
    if (-not $DryRun) {
        docker builder prune -f --keep-storage $KeepCache | Out-Null
        Write-Host '已清理'
    }
}

Write-Host ''
Write-Host '=== 清理后 ===' -ForegroundColor Cyan
docker system df

if (-not $DryRun) {
    Write-Host ''
    Write-Host '运行中的服务未受影响：'
    docker ps --format '  {{.Names}}  {{.Status}}'
}
