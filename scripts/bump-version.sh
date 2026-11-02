#!/usr/bin/env bash
# ============================================================
# 龙骨中台 · 版本号递增（Linux / macOS）
#
#   项目根的 VERSION 文件是唯一版本源：前端界面显示、后端 /api/health 回显、
#   镜像 tag 三者都以它为准。本脚本递增 VERSION，并同步 .env 里的镜像 tag。
#
#   用法：
#     ./scripts/bump-version.sh             # 修订号 +1（1.1.0 → 1.1.1）
#     ./scripts/bump-version.sh minor       # 次版本 +1（1.1.0 → 1.2.0）
#     ./scripts/bump-version.sh major       # 主版本 +1（1.1.0 → 2.0.0）
# ============================================================

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PART="${1:-patch}"
VERSION_FILE="$ROOT/VERSION"

[ -f "$VERSION_FILE" ] || { echo "找不到版本文件：$VERSION_FILE" >&2; exit 1; }
current="$(tr -d '[:space:]' < "$VERSION_FILE")"
if ! [[ "$current" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "VERSION 格式不正确：'$current'（应为 x.y.z）" >&2
  exit 1
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
case "$PART" in
  major) major=$((major + 1)); minor=0; patch=0 ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  patch) patch=$((patch + 1)) ;;
  *) echo "用法：$0 [patch|minor|major]" >&2; exit 1 ;;
esac
next="$major.$minor.$patch"

printf '%s\n' "$next" > "$VERSION_FILE"

# 同步 .env 里的镜像 tag
if [ -f "$ROOT/.env" ]; then
  sed -i.bak -E "s|^KEEL_IMAGE=keel/keel-admin:.*$|KEEL_IMAGE=keel/keel-admin:$next|" "$ROOT/.env"
  rm -f "$ROOT/.env.bak"
fi

echo "版本已更新：$current → $next"
echo
echo "请接着执行："
echo "  cd \"$ROOT\""
echo "  docker build -f docker/Dockerfile -t keel/keel-admin:$next \\"
echo "    --build-arg KEEL_VERSION=$next \\"
echo "    --build-arg JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre-jammy ."
echo "  docker compose up -d"
echo
echo "校验：curl http://127.0.0.1:8080/api/health   # 应回显 version=v$next"
