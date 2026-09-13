#!/usr/bin/env bash
# =====================================================================
# 佳佳聊天 · 管理后台启动脚本（Linux 通用：统信 UOS / 银河麒麟，x86_64 与 ARM64）
# 用法：./start-admin.sh [--host=服务器IP] [--port=9300]
# =====================================================================
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$DIR/../jj-chat.jar"

find_java() {
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME/bin/java"; return; fi
  if command -v java >/dev/null 2>&1; then command -v java; return; fi
  for j in /usr/lib/jvm/*/bin/java /opt/*jdk*/bin/java; do
    [ -x "$j" ] && { echo "$j"; return; }
  done
}
JAVA="$(find_java)"
if [ -z "$JAVA" ]; then
  MSG="未找到 Java 21。请先安装 JDK/JRE 21。"
  if command -v zenity >/dev/null 2>&1; then zenity --error --text="$MSG"; else echo "[错误] $MSG"; fi
  exit 1
fi

exec "$JAVA" -Dfile.encoding=UTF-8 -cp "$JAR" jj.client.AdminApp "$@"
