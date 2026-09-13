#!/usr/bin/env bash
# =====================================================================
# 佳佳聊天 · 服务端启动脚本（Linux 通用：统信 UOS / 银河麒麟 V10 x86_64 与 ARM64）
# 前台运行并直接打印日志；按 Ctrl+C 停止。
# 后台运行示例：nohup ./start-server.sh > server.log 2>&1 &
# 数据保存在发行包根目录 data/ 下；端口默认 9300。
# =====================================================================
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
JAR="$ROOT/jj-chat.jar"
cd "$ROOT"

find_java() {
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME/bin/java"; return; fi
  if command -v java >/dev/null 2>&1; then command -v java; return; fi
  for j in /usr/lib/jvm/*/bin/java /opt/*jdk*/bin/java; do
    [ -x "$j" ] && { echo "$j"; return; }
  done
}
JAVA="$(find_java)"
if [ -z "$JAVA" ]; then
  echo "[错误] 未找到 Java 21，请先安装（如 sudo apt install openjdk-21-jre-headless）。"
  exit 1
fi

exec "$JAVA" -Dfile.encoding=UTF-8 -cp "$JAR" jj.server.ChatServer "$@"
