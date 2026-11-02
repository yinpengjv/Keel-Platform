#!/usr/bin/env bash
# ============================================================
# 龙骨中台 · 清理 Docker 构建残留
#
#   反复构建镜像会累积两类垃圾：
#     ① 悬空镜像（<none>）—— 同名 tag 被新构建顶替，旧镜像失去引用
#     ② 构建缓存 —— 每层 npm/maven 下载与编译产物都留在 BuildKit 里
#
#   用法：
#     bash scripts/clean-docker.sh              # 清悬空镜像 + 构建缓存保留 1GB
#     bash scripts/clean-docker.sh --all        # 连构建缓存一起清（下次构建会变慢）
#     bash scripts/clean-docker.sh --dry-run    # 只看不删
#
#   不会碰：正在运行的容器、具名卷（mysql-data）、带 tag 的镜像。
# ============================================================
set -euo pipefail

KEEP_CACHE="1GB"
ALL=0
DRY=0
for arg in "$@"; do
  case "$arg" in
    --all) ALL=1 ;;
    --dry-run) DRY=1 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg" >&2; exit 1 ;;
  esac
done

echo "=== 清理前 ==="
docker system df

echo
echo "=== 悬空镜像（<none>） ==="
DANGLING=$(docker images -f "dangling=true" -q | wc -l | tr -d ' ')
echo "共 $DANGLING 个"
if [ "$DANGLING" -gt 0 ] && [ "$DRY" -eq 0 ]; then
  docker image prune -f >/dev/null
  echo "已清理"
fi

echo
echo "=== 构建缓存 ==="
if [ "$ALL" -eq 1 ]; then
  echo "模式：全部清理（下次构建需要重新下载依赖，会明显变慢）"
  [ "$DRY" -eq 0 ] && docker builder prune -af >/dev/null && echo "已清理"
else
  echo "模式：保留最近 ${KEEP_CACHE}（常用缓存留着，下次构建仍然快）"
  [ "$DRY" -eq 0 ] && docker builder prune -f --keep-storage "$KEEP_CACHE" >/dev/null && echo "已清理"
fi

echo
echo "=== 清理后 ==="
docker system df

if [ "$DRY" -eq 0 ]; then
  echo
  echo "运行中的服务未受影响："
  docker ps --format "  {{.Names}}  {{.Status}}"
fi
