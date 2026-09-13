#!/usr/bin/env bash
# =====================================================================
# 佳佳聊天 · 客户端启动脚本（Linux 通用）
# 适用：Ubuntu / Debian / 统信 UOS / 银河麒麟桌面版 V10
# 架构：x86_64、aarch64(ARM64)、龙芯 LoongArch64（只要装有 JDK/JRE 21）
# 用法：./start-client.sh [--host=服务器IP] [--port=9300]
# =====================================================================
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$DIR/../jj-chat.jar"

# ---------- 查找 Java 21 ----------
find_java() {
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME/bin/java"; return; fi
  if command -v java >/dev/null 2>&1; then command -v java; return; fi
  # 国产系统常见 JDK 安装位置
  for j in /usr/lib/jvm/*/bin/java /opt/*jdk*/bin/java; do
    [ -x "$j" ] && { echo "$j"; return; }
  done
}
JAVA="$(find_java)"
if [ -z "$JAVA" ]; then
  MSG="未找到 Java 21。请先安装 JDK/JRE 21（如：sudo apt install openjdk-21-jre，或使用系统软件源里的 21 版本）。"
  if command -v zenity >/dev/null 2>&1; then zenity --error --text="$MSG"; else echo "[错误] $MSG"; fi
  exit 1
fi
MAJOR="$("$JAVA" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)"
if [ -n "$MAJOR" ] && [ "$MAJOR" -lt 21 ] 2>/dev/null; then
  MSG="当前 Java 版本为 $MAJOR，佳佳聊天需要 21 及以上版本。"
  if command -v zenity >/dev/null 2>&1; then zenity --error --text="$MSG"; else echo "[错误] $MSG"; fi
  exit 1
fi

exec "$JAVA" -Dfile.encoding=UTF-8 -jar "$JAR" "$@"
