#!/usr/bin/env bash
# ============================================================
# 龙骨中台 · 下载 ip2region 离线库数据文件
#
#   IP 归属地查询依赖 ip2region 的 xdb 数据文件（约 29MB），
#   二进制文件不适合入库，因此不入 git；克隆代码后执行本脚本补齐。
#
#   用法：
#     bash scripts/fetch-ip2region.sh
#
#   下载后文件位于：
#     scaffold-system/scaffold-framework/src/main/resources/ip2region/
#       ip2region_v4.xdb   (IPv4，约 11MB)
#       ip2region_v6.xdb   (IPv6，约 18MB)
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="$ROOT/scaffold-system/scaffold-framework/src/main/resources/ip2region"
BASE_URL="https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data"

# 国内网络直连 GitHub 不稳，依次尝试多个镜像
MIRRORS=(
  ""
  "https://ghproxy.net/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data"
  "https://gh-proxy.com/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data"
  "https://gitee.com/lionsoul/ip2region/raw/master/data"
)

mkdir -p "$TARGET_DIR"

fetch() {
  local file="$1"
  local dest="$TARGET_DIR/$file"
  # 已存在且大小合理则跳过（v4 > 1MB，v6 > 1MB）
  if [ -f "$dest" ] && [ "$(wc -c < "$dest")" -gt 1000000 ]; then
    echo "  ✓ $file 已存在（$(du -h "$dest" | cut -f1)），跳过下载"
    return 0
  fi
  for mirror in "${MIRRORS[@]}"; do
    local url="$mirror/$file"
    [ -z "$mirror" ] && url="$BASE_URL/$file"
    echo "  → 尝试 $url"
    if curl -fsSL --connect-timeout 15 --max-time 300 -o "$dest" "$url" 2>/dev/null; then
      local size
      size="$(wc -c < "$dest")"
      if [ "$size" -gt 1000000 ]; then
        echo "  ✓ $file 下载完成（$(du -h "$dest" | cut -f1)）"
        return 0
      fi
    fi
    rm -f "$dest"
  done
  echo "  ✗ $file 下载失败，请手动访问 https://github.com/lionsoul2014/ip2region 获取" >&2
  return 1
}

echo "下载 ip2region 离线库到：$TARGET_DIR"
fetch ip2region_v4.xdb
fetch ip2region_v6.xdb
echo "完成。现在可以执行：docker build -f docker/Dockerfile -t keel/keel-admin:<版本> ."
