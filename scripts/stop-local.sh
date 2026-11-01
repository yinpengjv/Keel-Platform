#!/usr/bin/env bash
# ============================================================
# 龙骨中台 · 停止本地服务
#   ./scripts/stop-local.sh            停后端 + 前端
#   ./scripts/stop-local.sh backend    只停后端
#   ./scripts/stop-local.sh frontend   只停前端
# ============================================================

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/.local"
TARGET="${1:-all}"

say() { printf '\033[36m[keel]\033[0m %s\n' "$*"; }

stop_one() {
  local name="$1" pid_file="$STATE/$1.pid"
  if [ ! -f "$pid_file" ]; then
    say "$name 没有记录 pid，跳过（可能没启动过）"
    return 0
  fi

  local pid
  pid="$(cat "$pid_file")"
  if ! kill -0 "$pid" 2>/dev/null; then
    say "$name 进程已不在（pid $pid）"
    rm -f "$pid_file"
    return 0
  fi

  # 先收子进程，再收主进程；不按进程组 kill，避免连带打到当前终端
  if command -v pkill >/dev/null 2>&1; then
    pkill -TERM -P "$pid" 2>/dev/null || true
  fi
  kill -TERM "$pid" 2>/dev/null || true

  local i
  for i in $(seq 1 15); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done

  if kill -0 "$pid" 2>/dev/null; then
    kill -KILL "$pid" 2>/dev/null || true
    say "$name 未响应 TERM，已强制结束（pid $pid）"
  else
    say "$name 已停止（pid $pid）"
  fi
  rm -f "$pid_file"
}

case "$TARGET" in
  all)      stop_one frontend; stop_one backend ;;
  backend)  stop_one backend ;;
  frontend) stop_one frontend ;;
  *)        say "用法：$0 [all|backend|frontend]"; exit 1 ;;
esac
