#!/usr/bin/env bash
# =====================================================================
# 把佳佳聊天的三个入口注册到当前用户的“开始菜单/启动器”
# 适用：统信 UOS、银河麒麟桌面版 V10、Ubuntu 等带桌面环境的 Linux
# 用法：bash 安装桌面图标.sh
# 卸载：bash 安装桌面图标.sh --remove
# =====================================================================
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$HOME/.local/share/applications"
ICON_DIR="$HOME/.local/share/icons"
mkdir -p "$APP_DIR" "$ICON_DIR"

NAMES="jiajia-chat-client jiajia-chat-server jiajia-chat-admin"

if [ "$1" = "--remove" ]; then
  for n in $NAMES; do rm -f "$APP_DIR/$n.desktop"; done
  rm -f "$ICON_DIR/jiajia-chat.png"
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APP_DIR" || true
  echo "已移除佳佳聊开始菜单图标。"
  exit 0
fi

chmod +x "$DIR"/start-*.sh
cp "$DIR/icon.png" "$ICON_DIR/jiajia-chat.png"
for n in $NAMES; do
  sed -e "s#__DIR__#$DIR#g" "$DIR/$n.desktop" > "$APP_DIR/$n.desktop"
done
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APP_DIR" || true
echo "完成！已在开始菜单添加：佳佳聊天 / 佳佳聊天-服务端 / 佳佳管理后台"
echo "（若没有立即显示，注销重新登录一次即可）"
