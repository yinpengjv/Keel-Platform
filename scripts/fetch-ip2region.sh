#!/usr/bin/env bash
# ============================================================
# 龙骨中台 · 下载 ip2region 离线库数据文件
#
#   IP 归属地查询依赖 ip2region 的 xdb 数据文件（合计约 48MB），
#   二进制文件不适合入库，因此不入 git。
#
#   用法：
#     bash scripts/fetch-ip2region.sh
#
#   下载后文件位于：
#     scaffold-system/scaffold-framework/src/main/resources/ip2region/
#       ip2region_v4.xdb   (IPv4，约 11MB)
#       ip2region_v6.xdb   (IPv6，约 37MB)
#
#   什么时候需要跑：
#   - **换机器克隆源码后**，本地没有这两个文件，构建出来的镜像归属地会回落为
#     「内网/未知」。跑一次本脚本补齐即可，之后不必再跑。
#   - 直接跑镜像部署：不需要，xdb 已打包在镜像里。
#   - 构建镜像过程本身不下载任何东西，Dockerfile 只做 COPY。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="$ROOT/scaffold-system/scaffold-framework/src/main/resources/ip2region"

# 依次尝试多个镜像（国内网络直连 GitHub 不稳）
MIRRORS=(
  "https://ghproxy.net/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data"
  "https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data"
  "https://gh-proxy.com/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data"
  "https://gitee.com/lionsoul/ip2region/raw/master/data"
)

# 内置期望大小（字节）：远端 Content-Length 取不到时用它兜底。
# 上游更新后远端值会自动生效，这里只是保底。
declare -A FALLBACK=(
  [ip2region_v4.xdb]=11122714
  [ip2region_v6.xdb]=37277813
)

mkdir -p "$TARGET_DIR"

fetch() {
  local file="$1"
  local dest="$TARGET_DIR/$file"
  local fallback="${FALLBACK[$file]}"

  # 已存在且大小与内置期望一致则跳过
  if [ -f "$dest" ]; then
    local cur
    cur=$(wc -c < "$dest")
    if [ "$cur" -eq "$fallback" ]; then
      echo "  ✓ $file 已存在且完整（$cur 字节），跳过下载"
      return 0
    fi
    echo "  ! $file 已存在但大小不符（当前 $cur / 期望 $fallback），重新下载"
    rm -f "$dest"
  fi

  for mirror in "${MIRRORS[@]}"; do
    local url="$mirror/$file"
    echo "  → 尝试 $url"
    # 以远端 Content-Length 为准（上游更新自动跟随），取不到则用内置值
    local remote=""
    remote=$(curl -fsIL --connect-timeout 15 --max-time 60 "$url" 2>/dev/null \
             | grep -i "^content-length" | tail -1 | tr -d '\r' | awk '{print $2}') || remote=""
    local expect="${remote:-$fallback}"
    # --continue-at - 支持断点续传；下载完必须与期望大小一致，否则判定残缺并重试
    if curl -fsSL --connect-timeout 15 --max-time 600 -C - -o "$dest" "$url" 2>/dev/null; then
      local size
      size=$(wc -c < "$dest")
      if [ "$size" -eq "$expect" ]; then
        echo "  ✓ $file 下载完成（$size 字节，完整性校验通过）"
        return 0
      fi
      echo "  ! $file 大小不符（$size / 期望 $expect），可能下载不完整，换源重试"
      rm -f "$dest"
    else
      rm -f "$dest"
    fi
  done
  echo "  ✗ $file 下载失败，请手动访问 https://github.com/lionsoul2014/ip2region 获取" >&2
  return 1
}

echo "下载 ip2region 离线库到：$TARGET_DIR"
fetch ip2region_v4.xdb
fetch ip2region_v6.xdb
echo "完成。现在可以执行：docker build -f docker/Dockerfile -t keel/keel-admin:<版本> ."
