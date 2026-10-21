#!/bin/bash
# ragent 后端启动脚本（后台运行，带停止：重复执行即"重启"）
#   ./start-backend.sh        停掉旧实例 → 后台启动 → 等到端口就绪或报错为止
#   ./start-backend.sh stop   只停止
# 日志：logs/backend.log（上一次的保留为 backend.log.1），看实时日志：tail -f logs/backend.log
# 敏感配置（如 MINERU_API_KEY）放在 .env.local —— 该文件已被 .gitignore 忽略，不会进 git
cd "$(dirname "$0")" || exit 1

PORT=28080
JAR=bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar
LOG_DIR=logs
LOG_FILE=$LOG_DIR/backend.log
PID_FILE=$LOG_DIR/backend.pid
STARTUP_TIMEOUT=180   # 等端口就绪的最长秒数

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
# 1) PID 文件：覆盖"上次还在启动中、端口尚未监听"的窗口期；命令行须含本 jar，防 PID 被复用后误杀
if [ -f "$PID_FILE" ]; then
    FILE_PID=$(cat "$PID_FILE")
    if [ -n "$FILE_PID" ] && ps -p "$FILE_PID" -o cmd= 2>/dev/null | grep -qF "$JAR"; then
        stop_pid "$FILE_PID"
    fi
    rm -f "$PID_FILE"
fi
# 2) 端口：按监听 PID 停（不用 pkill -f，避免误杀无关 java）
OLD_PID=$(ss -ltnp "sport = :$PORT" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -n "$OLD_PID" ]; then
    case "$(ps -p "$OLD_PID" -o cmd=)" in
        *java*) stop_pid "$OLD_PID" ;;
        *)
            echo "端口 $PORT 被 PID=$OLD_PID 占用，但不是 java 进程，不动它，请手动检查："
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

# ---- 加载敏感配置并后台启动 ----
[ -f "$JAR" ] || { echo "找不到 $JAR，先编译：./mvnw -DskipTests package"; exit 1; }
set -a
[ -f .env.local ] && . ./.env.local
set +a

mkdir -p "$LOG_DIR"
[ -f "$LOG_FILE" ] && mv -f "$LOG_FILE" "$LOG_FILE.1"
# setsid 脱离当前终端会话 + nohup 忽略挂断信号：关掉 SSH 窗口进程照常运行
setsid nohup java -jar "$JAR" --server.port=$PORT >"$LOG_FILE" 2>&1 </dev/null &
NEW_PID=$!
echo "$NEW_PID" > "$PID_FILE"
echo "已在后台启动 PID=$NEW_PID，日志：$LOG_FILE"

# ---- 等启动结果：端口就绪=成功；进程提前退出=失败并打印日志尾部 ----
# 等待期间按 Ctrl+C 只会中断这个等待，不影响后台的 java
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
echo "⚠️ ${STARTUP_TIMEOUT}s 内端口仍未就绪，进程还在跑（PID=$NEW_PID），请看日志：tail -f $LOG_FILE"
exit 1
