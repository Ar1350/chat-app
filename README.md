# 佳佳聊天（JiaJiaChat）

一个类似 QQ / 微信的实时聊天软件，提供两个独立版本：

- **Java Swing 桌面版**（`java-chat/`，主力版本）：插件式架构的 TCP 服务端 + 跨平台桌面客户端 + 管理后台，支持 Windows、Linux（含统信 UOS、银河麒麟，x86_64 / ARM64）和 macOS。
- **网页版**（`chat-app/`，早期版本）：Node.js + Express + Socket.IO，浏览器直接使用。

## 功能特性

- 账号注册 / 登录（注册成功后返回登录页手动登录，不自动进入）
- 私聊、群聊、历史消息、表情与自定义头像
- 文件发送与接收（在线直传、离线补取，自动弹出保存对话框）
- 在线状态实时同步（上线 / 离线 / 重复登录踢下线）
- 用户自助修改密码（校验旧密码、6-20 位新密码、新旧不可相同）
- 聊天主窗口右上角三道杠菜单：修改密码 / 账号资料 / 注销账号 / 退出程序
- 管理后台：查看在线状态、编辑用户资料、重置密码、删除用户（实时踢下线）

## 目录结构

```text
.
├── chat-app/                # 网页版（Node.js + Express + Socket.IO）
│   ├── server.js            # Web 服务端（默认端口 3000）
│   └── public/              # 前端页面
└── java-chat/               # Java 桌面版（JDK 21）
    ├── src/
    │   └── jj/
    │       ├── protocol/    # 协议常量与报文构建（Actions/Events/Protocol）
    │       ├── server/      # 服务端：ChatServer、ClientHandler、ServerContext
    │       │   └── plugin/  # 插件：Auth/Message/Admin/Password
    │       └── client/      # 客户端：ChatApp、LoginFrame、ChatFrame、AdminFrame
    └── package/             # 多平台启动脚本与打包配置
```

## Java 桌面版 · 快速开始

### 方式一：直接使用成品包（推荐普通用户）

到 [Releases](https://github.com/Ar1350/chat-app/releases) 下载：

| 文件 | 适用 | 说明 |
| --- | --- | --- |
| `JiaJiaChat-1.0.0-windows-x64.zip` | Windows 10/11 x64 | 绿色版，内置 JRE 21，解压即用，无需安装 Java |
| `jiajia-chat-1.0.0-crossplatform.zip` | 全平台 | 单个 jar + 各系统启动脚本，需系统装有 JDK/JRE 21 |

Windows 绿色版解压后先启动 **佳佳聊天-服务端.exe**，再打开 **佳佳聊天.exe**；管理用户用 **佳佳管理后台.exe**。

### 方式二：从源码运行（开发者）

需要 JDK 21（推荐 Eclipse Temurin 21），在 `java-chat/` 目录下：

```powershell
# 编译（PowerShell）
javac -encoding UTF-8 -d out (Get-ChildItem -Recurse -Filter *.java src | ForEach-Object FullName)

# 启动服务端（端口 9300，数据存于 data/）
java -cp out jj.server.ChatServer

# 新开窗口：启动聊天客户端 / 管理后台
javaw -cp out jj.client.ChatApp
javaw -cp out jj.client.AdminApp
```

也可以直接双击 `java-chat/` 根目录下的「启动服务器.bat」「启动聊天窗口.bat」「启动管理后台.bat」。

### 局域网 / 跨机器连接服务器

客户端默认连接本机 `127.0.0.1:9300`。客户端与服务端不在同一台机器时，启动时指定服务器地址：

```bash
java -jar jj-chat.jar --host=192.168.1.10 --port=9300      # 聊天客户端
java -cp jj-chat.jar jj.client.AdminApp --host=192.168.1.10 # 管理后台
java -Dchat.port=9300 -cp jj-chat.jar jj.server.ChatServer  # 自定义服务端口
```

## Linux / 国产系统（统信 UOS、银河麒麟）

跨平台包解压后进入 `linux/` 目录：

```bash
chmod +x *.sh
./start-server.sh          # 先启动服务器
./start-client.sh          # 再启动客户端（可加 --host=服务器IP）
bash 安装桌面图标.sh        # 可选：在开始菜单添加三个入口
```

脚本会自动查找系统中的 JDK 21 并校验版本，同一套 jar 在 **x86_64、ARM64（飞腾/鲲鹏）、LoongArch64（龙芯）** 上均可运行。

### 在国产系统上生成自带 JRE 的安装包

jpackage 只能用当前机器的 JDK 裁剪运行时，因此在什么架构的机器上构建，就产出什么架构的包：

```bash
bash package/build-linux.sh deb        # UOS / 银河麒麟桌面 V10 / Ubuntu（x64 或 ARM 机器上各自执行）
bash package/build-linux.sh rpm        # 银河麒麟高级服务器 V10
bash package/build-linux.sh app-image  # 免安装绿色目录
```

Windows 上一键重现绿色版：双击 `package/build-windows.bat`。

## 网页版 · 快速开始

需要 Node.js 16+：

```bash
cd chat-app
npm install
npm start
```

浏览器访问 `http://localhost:3000`，局域网内手机或其他电脑访问控制台提示的 `http://<本机IP>:3000`。

## 默认账号

- 管理员：`admin` / `admin123`（登录后请及时修改密码）

## 数据与备份

- Java 桌面版：账号、消息、收到的文件保存在 `java-chat/data/`（`db-java.json` 与 `files/`），已在 `.gitignore` 中排除，升级或迁移时备份该目录即可。
- 网页版：数据保存在 `chat-app/data/`。

## 技术栈

| 版本 | 技术 |
| --- | --- |
| Java 桌面版 | Java 21、Swing、Socket + JSON 行协议、jpackage 打包 |
| 网页版 | Node.js、Express 4、Socket.IO 4、原生 HTML/CSS/JS |

## 通信协议（Java 版）

TCP 长连接，端口 **9300**，每条报文为一行 JSON。动作名（`register` / `login` / `send` / `file_download` / `change_password` …）与事件名（`login_ok` / `msg` / `file_data` / `presence` …）统一在 [jj/protocol](java-chat/src/jj/protocol) 中定义；服务端按 action 路由到对应插件，新增功能实现 `ServerPlugin` 接口即可挂载。
