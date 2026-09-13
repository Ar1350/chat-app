#!/bin/bash
# 佳佳聊天 · macOS 客户端（需安装 JDK 21，如 Temurin 21）
# 用法：双击本文件，或终端执行 ./佳佳聊天.command --host=服务器IP
cd "$(dirname "$0")"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA="$(command -v java)"; fi
if [ -z "$JAVA" ]; then
  osascript -e 'display dialog "未找到 Java 21，请先安装 Temurin JDK 21。" buttons {"好"}' 2>/dev/null
  echo "[错误] 未找到 Java 21"; exit 1
fi
exec "$JAVA" -Dfile.encoding=UTF-8 -jar ../jj-chat.jar "$@"
