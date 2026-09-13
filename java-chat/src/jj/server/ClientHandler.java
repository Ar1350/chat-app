package jj.server;

import jj.common.Json;
import jj.protocol.Protocol;
import jj.server.plugin.PluginManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条客户端 TCP 连接。
 * 只负责：读写 JSON 行、维护登录态、上下线广播、把请求交给插件调度器。
 * 业务逻辑全部在 jj.server.plugin 下的各插件中。
 */
public class ClientHandler implements Runnable {

    private final ServerContext ctx;
    private final PluginManager plugins;
    private final Socket sock;

    private BufferedReader in;
    private BufferedWriter out;

    /** 登录后的用户 uid（null = 未登录） */
    public volatile String uid;
    public volatile boolean closed = false;

    public ClientHandler(ServerContext ctx, PluginManager plugins, Socket sock) {
        this.ctx = ctx;
        this.plugins = plugins;
        this.sock = sock;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
            String line;
            while (!closed && (line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    Map<String, Object> m = Json.obj(Json.parse(line));
                    if (m != null) plugins.dispatch(this, m);
                } catch (Exception e) {
                    System.out.println("[警告] 处理消息出错(" + sock.getRemoteSocketAddress() + "): " + e);
                }
            }
        } catch (IOException ignored) {
            // 客户端断开
        } finally {
            onDisconnect();
        }
    }

    public synchronized void send(Map<String, Object> evt) {
        if (closed || out == null) return;
        try {
            out.write(Json.write(evt));
            out.write("\n");
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    public void close() {
        closed = true;
        try { sock.close(); } catch (IOException ignored) {}
    }

    /**
     * 注册/登录成功后的统一处理：
     * 进入在线表 → 下发本人资料+全员+群列表 → 通知其他人上线。
     *
     * @param event 结果事件名（register_ok / login_ok）
     */
    public void loginAs(String event, Map<String, Object> user) {
        uid = String.valueOf(user.get("uid"));
        ctx.online.put(uid, this);

        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("event", event);
        evt.put("me", ctx.mePayload(user));
        evt.put("users", ctx.allUsersList());
        evt.put("groups", ctx.groupsList());
        send(evt);

        System.out.println("[" + ("register_ok".equals(event) ? "注册登录" : "登录") + "] "
                + user.get("nickname") + " (" + user.get("username") + "/" + uid
                + ")，当前在线 " + ctx.online.size() + " 人");

        Map<String, Object> pre = Protocol.presence(uid, true, ctx.pubUser(user, true), false);
        for (ClientHandler h : ctx.online.values()) {
            if (h != this) h.send(pre);
        }
    }

    /** 连接断开：离开在线表并广播下线 */
    private void onDisconnect() {
        closed = true;
        if (uid != null && ctx.online.remove(uid, this)) {
            System.out.println("[离线] " + uid + "，当前在线 " + ctx.online.size() + " 人");
            Map<String, Object> u = ctx.findUserByUid(uid);
            broadcastPresence(u, false, false);
        }
        try { sock.close(); } catch (IOException ignored) {}
    }

    /** 向所有人广播本连接对应用户的 presence（下线/资料变更） */
    public void broadcastPresence(Map<String, Object> u, boolean online, boolean deleted) {
        ctx.broadcast(Protocol.presence(uid, online,
                u == null ? null : ctx.pubUser(u, online), deleted));
    }
}
