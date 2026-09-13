## 佳佳聊天 v1.0.0

首个正式版本，提供 Windows 免安装绿色版和全平台压缩包。

### 下载说明

| 文件 | 适用系统 | 是否需要 Java |
| --- | --- | --- |
| `JiaJiaChat-1.0.0-windows-x64.zip` | Windows 10 / 11（64 位） | 不需要，内置 JRE 21 |
| `jiajia-chat-1.0.0-crossplatform.zip` | Windows / Linux / macOS / 统信 UOS / 银河麒麟（x86_64、ARM64、龙芯） | 需自行安装 JDK 21 |

### Windows 使用方法

1. 下载 `JiaJiaChat-1.0.0-windows-x64.zip` 并解压。
2. 双击 **佳佳聊天-服务端.exe** 启动服务器（窗口保持打开）。
3. 双击 **佳佳聊天.exe** 登录聊天；管理用户用 **佳佳管理后台.exe**。
4. 局域网其他电脑连接：在客户端快捷方式后加参数 `--host=服务器IP`，或使用跨平台包中的启动脚本。

### Linux / 国产系统使用方法

1. 下载跨平台包并解压，进入 `linux/` 目录。
2. 执行 `bash start-server.sh` 启动服务器，`bash start-client.sh --host=服务器IP` 启动客户端。
3. 需要自带 JRE 的 deb/rpm 安装包时，在对应架构的机器上执行源码 `package/` 目录下的 `bash build-linux.sh deb`（或 `rpm`）。

### 默认账号

- 管理员：`admin` / `admin123`（请及时修改）

**完整问题反馈与源码：** https://github.com/Ar1350/chat-app
