package jj.server;

import jj.common.Json;
import jj.server.plugin.AdminPlugin;
import jj.server.plugin.AuthPlugin;
import jj.server.plugin.MessagePlugin;
import jj.server.plugin.PasswordPlugin;
import jj.server.plugin.PluginManager;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 佳佳聊天 · Java 服务端（插件式）
 * TCP + JSON 行协议，端口 9300。
 *
 * 启动流程：加载数据库 → 创建插件调度器 → 逐个注册功能插件 → 接受连接。
 * 各功能插件位于 jj.server.plugin 包：
 *   - AuthPlugin     注册 / 登录 / 退出
 *   - MessagePlugin  私聊 / 群聊 / 历史消息
 *   - PasswordPlugin 后台修改密码
 *   - AdminPlugin    后台登录 / 用户列表 / 修改资料 / 删除
 * 通信协议（动作、事件、字段名）统一定义在 jj.protocol 包。
 */
public class ChatServer {

    static final int PORT = 9300;
    static final Path DB_FILE = Paths.get("data", "db-java.json");
    static final Path WEB_DB = Paths.get("..", "chat-app", "data", "db.json");

    public static void main(String[] args) throws Exception {
        Map<String, Object> db = loadDB();
        ServerContext ctx = new ServerContext(db, DB_FILE);

        // 注册功能插件（想增减功能，只需在这里增删一行）
        PluginManager plugins = new PluginManager(ctx);
        plugins.register(new AuthPlugin());
        plugins.register(new MessagePlugin());
        plugins.register(new PasswordPlugin());
        plugins.register(new AdminPlugin());

        System.out.println("==============================================");
        System.out.println("  佳佳聊天 · Java 服务端已启动（插件式架构）");
        System.out.println("  监听端口: " + PORT + "，已加载插件 " + plugins.plugins().size() + " 个");
        System.out.println("  数据文件: " + DB_FILE.toAbsolutePath());
        System.out.println("  按 Ctrl+C 停止服务");
        System.out.println("==============================================");

        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress("0.0.0.0", PORT));
            while (true) {
                Socket sock = ss.accept();
                ClientHandler handler = new ClientHandler(ctx, plugins, sock);
                Thread t = new Thread(handler, "client-" + sock.getRemoteSocketAddress());
                t.start();
            }
        }
    }

    // ---------------- 数据库加载 ----------------

    private static Map<String, Object> loadDB() {
        Map<String, Object> d = null;
        if (Files.exists(DB_FILE)) {
            d = tryRead(DB_FILE);
        }
        // 首次运行：从网页版 db.json 迁移用户数据
        if (d == null && Files.exists(WEB_DB)) {
            d = tryRead(WEB_DB);
            if (d != null) {
                int n = d.get("users") == null ? 0 : Json.arr(d.get("users")).size();
                System.out.println("[数据] 已从网页版 db.json 迁移用户数据（" + n + " 个用户）");
            }
        }
        if (d == null) {
            d = new LinkedHashMap<>();
            d.put("users", new ArrayList<Object>());
            d.put("groups", new ArrayList<Object>());
            d.put("messages", new LinkedHashMap<String, Object>());
            d.put("admin", null);
        }

        // 默认群兜底
        List<Object> groups = Json.arr(d.get("groups"));
        if (groups == null) {
            groups = new ArrayList<>();
            d.put("groups", groups);
        }
        boolean hasDefault = false;
        for (Object g : groups) {
            Map<String, Object> gm = Json.obj(g);
            if (gm != null && "default".equals(gm.get("gid"))) { hasDefault = true; break; }
        }
        if (!hasDefault) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("gid", "default");
            g.put("name", "大家的群");
            g.put("owner", null);
            g.put("members", new ArrayList<Object>());
            g.put("createdAt", System.currentTimeMillis());
            groups.add(0, g);
        }

        // 默认管理员兜底
        if (Json.obj(d.get("admin")) == null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("username", "admin");
            a.put("password", "admin123");
            d.put("admin", a);
        }
        if (d.get("messages") == null) d.put("messages", new LinkedHashMap<String, Object>());
        if (d.get("users") == null) d.put("users", new ArrayList<Object>());

        // 初次落盘（通过 ServerContext 复用保存格式）
        new ServerContext(d, DB_FILE).saveDB();
        return d;
    }

    private static Map<String, Object> tryRead(Path p) {
        try {
            String raw = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            return Json.obj(Json.parse(raw));
        } catch (Exception e) {
            System.out.println("[警告] 读取 " + p + " 失败: " + e.getMessage());
            return null;
        }
    }
}
