#!/usr/bin/env bash
# ============================================================
# 龙骨中台 · 本地一键启动（Linux / macOS）
#
#   ./scripts/start-local.sh              启动后端 + 前端
#   ./scripts/start-local.sh backend      只启后端
#   ./scripts/start-local.sh frontend     只启前端
#
# 所有可调参数都在下面的「配置区」，用环境变量覆盖即可，
# 例如：BACKEND_PORT=8080 ./scripts/start-local.sh
# ============================================================

set -euo pipefail

# ---------- 配置区 ----------
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/.local"
LOG_DIR="$STATE/logs"

BACKEND_PORT="${BACKEND_PORT:-18080}"
FRONTEND_PORT="${FRONTEND_PORT:-15173}"
FRONTEND_HOST="${FRONTEND_HOST:-127.0.0.1}"   # 服务器上对外访问改成 0.0.0.0

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-scaffold_system}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD-}"
DB_TIMEZONE="${DB_TIMEZONE:-+08:00}"
SCAFFOLD_CACHE="${SCAFFOLD_CACHE:-local}"

JAR="$ROOT/scaffold-system/scaffold-admin/target/scaffold-admin-0.1.0-SNAPSHOT.jar"
SECRET_FILE="$STATE/jwt-secret.txt"
# ----------------------------

TARGET="${1:-all}"
mkdir -p "$LOG_DIR"

say() { printf '\033[36m[keel]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[keel] %s\033[0m\n' "$*" >&2; exit 1; }

port_busy() {
  if command -v ss >/dev/null 2>&1; then
    ss -lnt 2>/dev/null | awk '{print $4}' | grep -q ":$1\$"
  elif command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  else
    return 1
  fi
}

wait_http() {
  local url="$1" name="$2" i
  for i in $(seq 1 60); do
    if curl -s --noproxy '*' -o /dev/null "$url" 2>/dev/null; then
      say "$name 已就绪"
      return 0
    fi
    sleep 1
  done
  die "$name 启动超时，查看日志：$3"
}

jwt_secret() {
  if [ -n "${JWT_SECRET:-}" ]; then
    printf '%s' "$JWT_SECRET"
    return
  fi
  if [ -f "$SECRET_FILE" ]; then
    cat "$SECRET_FILE"
    return
  fi
  local generated
  generated="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  printf '%s' "$generated" > "$SECRET_FILE"
  chmod 600 "$SECRET_FILE"
  say "已生成 JWT 密钥并保存到 $SECRET_FILE（重启不会失效）"
  printf '%s' "$generated"
}

start_backend() {
  command -v java >/dev/null 2>&1 || die "找不到 java，请先安装 JDK 17+"
  say "JDK: $(java -version 2>&1 | head -1 | tr -d '"')"

  if port_busy "$BACKEND_PORT"; then
    die "端口 $BACKEND_PORT 已被占用，先执行 ./scripts/stop-local.sh 或换 BACKEND_PORT"
  fi

  if [ ! -f "$JAR" ]; then
    command -v mvn >/dev/null 2>&1 || die "找不到 mvn，请先安装 Maven 3.9+"
    say "未找到 jar，开始构建（首次约 1~3 分钟）"
    ( cd "$ROOT/scaffold-system" && mvn -q -DskipTests package ) || die "后端构建失败"
  fi

  say "启动后端 :$BACKEND_PORT（日志 $LOG_DIR/backend.log）"
  : > "$LOG_DIR/backend.log"
  (
    cd "$ROOT/scaffold-system"
    JWT_SECRET="$(jwt_secret)" \
    DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" DB_NAME="$DB_NAME" \
    DB_USERNAME="$DB_USERNAME" DB_PASSWORD="$DB_PASSWORD" DB_TIMEZONE="$DB_TIMEZONE" \
    SCAFFOLD_CACHE="$SCAFFOLD_CACHE" \
    nohup java -jar "$JAR" --server.port="$BACKEND_PORT" \
      >> "$LOG_DIR/backend.log" 2>&1 &
    echo $! > "$STATE/backend.pid"
  )

  wait_http "http://127.0.0.1:$BACKEND_PORT/api/auth/login-challenge?username=admin" "后端" "$LOG_DIR/backend.log"
}

start_frontend() {
  command -v node >/dev/null 2>&1 || die "找不到 node，请先安装 Node.js 20+"
  command -v npm  >/dev/null 2>&1 || die "找不到 npm"

  if port_busy "$FRONTEND_PORT"; then
    die "端口 $FRONTEND_PORT 已被占用，先执行 ./scripts/stop-local.sh 或换 FRONTEND_PORT"
  fi

  if [ ! -d "$ROOT/scaffold-vue/node_modules" ]; then
    say "安装前端依赖（npm ci）"
    ( cd "$ROOT/scaffold-vue" && npm ci ) || die "前端依赖安装失败"
  fi

  local vite="$ROOT/scaffold-vue/node_modules/.bin/vite"
  [ -x "$vite" ] || die "找不到 $vite，先在 scaffold-vue 下执行 npm ci"

  say "启动前端 :$FRONTEND_PORT（日志 $LOG_DIR/frontend.log）"
  : > "$LOG_DIR/frontend.log"
  (
    cd "$ROOT/scaffold-vue"
    # 直接起 vite 而不是 npm run dev，少一层进程，pid 干净、停服不留孤儿
    VITE_API_PROXY_TARGET="http://127.0.0.1:$BACKEND_PORT" \
    nohup "$vite" --port "$FRONTEND_PORT" --strictPort --host "$FRONTEND_HOST" \
      >> "$LOG_DIR/frontend.log" 2>&1 &
    echo $! > "$STATE/frontend.pid"
  )

  wait_http "http://localhost:$FRONTEND_PORT/" "前端" "$LOG_DIR/frontend.log"
}

case "$TARGET" in
  all)      start_backend; start_frontend ;;
  backend)  start_backend ;;
  frontend) start_frontend ;;
  *)        die "用法：$0 [all|backend|frontend]" ;;
esac

cat <<EOF

  ─────────────────────────────────────────────
   前端入口   http://localhost:$FRONTEND_PORT
   后端接口   http://localhost:$BACKEND_PORT/api
   数据库     $DB_USERNAME@$DB_HOST:$DB_PORT/$DB_NAME
   账号密码   admin / admin123
   日志目录   $LOG_DIR
   停止服务   ./scripts/stop-local.sh
  ─────────────────────────────────────────────

EOF
