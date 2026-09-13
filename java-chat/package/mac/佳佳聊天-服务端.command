#!/bin/bash
# 佳佳聊天 · macOS 服务端（终端运行，Ctrl+C 停止）
cd "$(dirname "$0")/.."
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA="$(command -v java)"; fi
if [ -z "$JAVA" ]; then echo "[错误] 未找到 Java 21"; exit 1; fi
exec "$JAVA" -Dfile.encoding=UTF-8 -cp jj-chat.jar jj.server.ChatServer "$@"
