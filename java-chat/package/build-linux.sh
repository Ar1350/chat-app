#!/usr/bin/env bash
# =====================================================================
# 佳佳聊天 · Linux 一键打包脚本
#
# 在什么机器上执行，就生成什么架构的安装包（jpackage 用本机 JDK 裁剪运行时）：
#   x86_64 机器（普通 PC、麒麟 V10 x86 版）  -> 生成 x86_64 包
#   aarch64 机器（飞腾/鲲鹏 ARM、UOS/麒麟 ARM 版）-> 生成 arm64 包
#   loongarch64 机器（龙芯 + 龙芯 JDK21）    -> 生成 loongarch64 包
#
# 用法：
#   bash build-linux.sh deb        # 生成 .deb（统信 UOS / 银河麒麟桌面 V10 / Ubuntu）
#   bash build-linux.sh rpm        # 生成 .rpm（银河麒麟高级服务器 V10 / CentOS）
#   bash build-linux.sh app-image  # 只生成免安装绿色目录（任何 Linux 通用）
#
# 依赖：JDK 21（含 jpackage）。deb 还需要 fakeroot；rpm 还需要 rpm-build。
#   Debian/UOS/麒麟桌面：sudo apt install openjdk-21-jdk fakeroot
#   麒麟服务器/CentOS  ：sudo yum install java-21-openjdk-devel rpm-build
# =====================================================================
set -e
cd "$(dirname "$0")/.."

TYPE="${1:-deb}"
VERSION="1.0.0"
ARCH="$(uname -m)"

echo "== 目标类型: $TYPE   本机架构: $ARCH =="

if ! command -v javac >/dev/null 2>&1 && [ -z "$JAVA_HOME" ]; then
  echo "[错误] 未找到 JDK 21，请先安装（含 jpackage）。"; exit 1
fi
JP="jpackage"; command -v jpackage >/dev/null 2>&1 || JP="$JAVA_HOME/bin/jpackage"
if [ ! -x "$JP" ] && [ "$TYPE" != "app-image" ]; then :; fi
JAR_TOOL="jar"; command -v jar >/dev/null 2>&1 || JAR_TOOL="$JAVA_HOME/bin/jar"

echo "[1/4] 编译..."
rm -rf out && mkdir -p out
find src -name '*.java' > /tmp/jj_sources.txt
javac -encoding UTF-8 -d out @/tmp/jj_sources.txt

echo "[2/4] 打 jar..."
mkdir -p dist/jardir
echo "Main-Class: jj.client.ChatApp" > /tmp/jj_manifest.mf
$JAR_TOOL cfm dist/jj-chat.jar /tmp/jj_manifest.mf -C out jj
cp dist/jj-chat.jar dist/jardir/

echo "[3/4] jpackage ($TYPE)..."
rm -rf dist/jiajia-chat
COMMON="--name jiajia-chat --app-version $VERSION --vendor JiaJiaChat \
  --input dist/jardir --main-jar jj-chat.jar --main-class jj.client.ChatApp \
  --icon package/icons/icon.png --java-options -Dfile.encoding=UTF-8 \
  --linux-menu-group 'Network;InstantMessaging;Chat;' --linux-shortcut \
  --linux-package-name jiajia-chat --linux-deb-maintainer JiaJiaChat \
  --add-launcher server=package/linux-launchers/server.properties \
  --add-launcher admin=package/linux-launchers/admin.properties"

case "$TYPE" in
  deb)
    command -v fakeroot >/dev/null 2>&1 || echo "[提示] 生成 deb 需要 fakeroot：sudo apt install fakeroot"
    $JP --type deb --dest dist $COMMON
    ;;
  rpm)
    command -v rpmbuild >/dev/null 2>&1 || echo "[提示] 生成 rpm 需要 rpm-build：sudo yum install rpm-build"
    $JP --type rpm --dest dist $COMMON
    ;;
  app-image)
    $JP --type app-image --dest dist $COMMON
    ;;
  *) echo "[错误] 未知类型: $TYPE（支持 deb / rpm / app-image）"; exit 1 ;;
esac

echo "[4/4] 同步跨平台启动脚本到产物..."
mkdir -p dist/jiajia-chat-1.0.0/linux dist/jiajia-chat-1.0.0/mac
cp dist/jj-chat.jar dist/jiajia-chat-1.0.0/
cp package/linux/*.sh package/linux/*.desktop dist/jiajia-chat-1.0.0/linux/ 2>/dev/null || true
cp package/icons/icon.png dist/jiajia-chat-1.0.0/linux/icon.png
cp package/mac/*.command dist/jiajia-chat-1.0.0/mac/ 2>/dev/null || true
chmod +x dist/jiajia-chat-1.0.0/linux/*.sh dist/jiajia-chat-1.0.0/mac/*.command 2>/dev/null || true

echo
echo "==== 打包完成（架构: $ARCH）===="
ls -lh dist/*.deb dist/*.rpm 2>/dev/null || true
[ "$TYPE" = "app-image" ] && echo "绿色目录: dist/jiajia-chat/（整体拷贝到同架构机器即可运行）"
echo
echo "安装命令（deb）：sudo apt install ./jiajia-chat_${VERSION}-1_$( [ "$ARCH" = "aarch64" ] && echo arm64 || echo amd64).deb"
echo "安装后开始菜单出现：佳佳聊天 / 佳佳聊天-服务端 / 佳佳管理后台"
