#!/bin/bash
# ragent 前端启动脚本（后台运行，带停止：重复执行即"重启"）
#   ./start-frontend.sh        停掉旧实例 → 后台启动 → 等到端口就绪或报错为止
#   ./start-frontend.sh stop   只停止
# 日志：logs/frontend.log（上一次的保留为 frontend.log.1），看实时日志：tail -f logs/frontend.log
cd "$(dirname "$0")" || exit 1

PORT=5173
FRONTEND_DIR=frontend
LOG_DIR=logs
LOG_FILE=$LOG_DIR/frontend.log
PID_FILE=$LOG_DIR/frontend.pid
STARTUP_TIMEOUT=60   # 等端口就绪的最长秒数

# node 走 nvm，非交互 shell 里可能不在 PATH
NODE_BIN=$(command -v node || true)
[ -n "$NODE_BIN" ] || NODE_BIN=/root/.nvm/versions/node/v24.21.0/bin/node
[ -x "$NODE_BIN" ] || { echo "找不到 node，先确认 nvm 安装"; exit 1; }

# 停一个 PID：先 TERM 最多等 15 秒优雅退出，超时补 kill -9
stop_pid() {
    local pid=$1
    echo "停止旧实例 PID=$pid ..."
    kill "$pid" 2>/dev/null
    for _ in $(seq 1 30); do
        kill -0 "$pid" 2>/dev/null || { echo "旧实例已停止"; return; }
        sleep 0.5
    done
    echo "优雅退出超时，强制 kill -9 PID=$pid"
    kill -9 "$pid" 2>/dev/null
}

# ---- 停旧实例 ----
# 1) PID 文件：覆盖"上次还在启动中、端口尚未监听"的窗口期；命令行须含 vite，防 PID 被复用后误杀
if [ -f "$PID_FILE" ]; then
    FILE_PID=$(cat "$PID_FILE")
    if [ -n "$FILE_PID" ] && ps -p "$FILE_PID" -o cmd= 2>/dev/null | grep -qF "vite"; then
        stop_pid "$FILE_PID"
    fi
    rm -f "$PID_FILE"
fi
# 2) 端口：按监听 PID 停（不用 pkill -f，避免误杀无关 node）
OLD_PID=$(ss -ltnp "sport = :$PORT" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -n "$OLD_PID" ]; then
    case "$(ps -p "$OLD_PID" -o cmd=)" in
        *node*|*vite*) stop_pid "$OLD_PID" ;;
        *)
            echo "端口 $PORT 被 PID=$OLD_PID 占用，但不是 node 进程，不动它，请手动检查："
            echo "  ps -p $OLD_PID -o cmd="
            exit 1
            ;;
    esac
fi
# 等端口真正释放，避免新进程 bind 失败（最多 10 秒）
for _ in $(seq 1 20); do
    ss -ltn "sport = :$PORT" 2>/dev/null | grep -q ":$PORT" || break
    sleep 0.5
done

[ "$1" = "stop" ] && exit 0

# ---- 后台启动 ----
[ -d "$FRONTEND_DIR/node_modules" ] || {
    echo "找不到 $FRONTEND_DIR/node_modules，先装依赖：cd $FRONTEND_DIR && npm install"
    exit 1
}

mkdir -p "$LOG_DIR"
[ -f "$LOG_FILE" ] && mv -f "$LOG_FILE" "$LOG_FILE.1"
# setsid 脱离当前终端会话 + nohup 忽略挂断信号：关掉 SSH 窗口进程照常运行
cd "$FRONTEND_DIR" || exit 1
setsid nohup "$NODE_BIN" node_modules/.bin/vite --host 0.0.0.0 >"../$LOG_FILE" 2>&1 </dev/null &
NEW_PID=$!
cd ..
echo "$NEW_PID" > "$PID_FILE"
echo "已在后台启动 PID=$NEW_PID，日志：$LOG_FILE"

# ---- 等启动结果：端口就绪=成功；进程提前退出=失败并打印日志尾部 ----
printf '等待端口 %s 就绪' "$PORT"
for i in $(seq 1 "$STARTUP_TIMEOUT"); do
    if ! kill -0 "$NEW_PID" 2>/dev/null; then
        echo
        echo "❌ 启动失败：进程已退出，日志最后 40 行："
        tail -n 40 "$LOG_FILE"
        rm -f "$PID_FILE"
        exit 1
    fi
    if ss -ltn "sport = :$PORT" 2>/dev/null | grep -q ":$PORT"; then
        echo
        echo "✅ 启动成功（${i}s），PID=$NEW_PID"
        echo "看实时日志：tail -f $LOG_FILE"
        exit 0
    fi
    [ $((i % 5)) -eq 0 ] && printf '.'
    sleep 1
done

echo
echo "⚠️ 等待 ${STARTUP_TIMEOUT}s 端口仍未就绪，进程还在跑（PID=$NEW_PID），看日志："
tail -n 40 "$LOG_FILE"
exit 1
