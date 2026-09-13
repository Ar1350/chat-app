package jj.server.plugin;

import jj.common.Json;
import jj.protocol.Actions;
import jj.protocol.Protocol;
import jj.server.ClientHandler;
import jj.server.ServerContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息插件：私聊、群聊（默认群全员可达）、历史消息拉取、文件传输。
 *
 * 文件传输设计（JSON 行协议内完成，无需额外端口）：
 *  - 发送：客户端把文件 Base64 编码放在 data 字段随 send_file 上报；
 *  - 存储：服务端把二进制落到 data/files/<fileId>，消息记录里只存
 *          {type:"file", fileId, fileName, size}，数据库不存大块内容；
 *  - 在线：投递实时 msg 事件时附带 data，接收方秒收；
 *  - 离线：历史里只有文件卡片，点击后用 file_download 主动补取。
 */
public class MessagePlugin implements ServerPlugin {

    /** 单文件大小上限：20 MB */
    static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    static final Path FILES_DIR = Paths.get("data", "files");

    @Override
    public String name() {
        return "消息插件(私聊/群聊/历史/文件)";
    }

    @Override
    public Set<String> actions() {
        return Set.of(Actions.SEND, Actions.HISTORY, Actions.SEND_FILE, Actions.FILE_DOWNLOAD);
    }

    @Override
    public void handle(ServerContext ctx, ClientHandler client, String action, Map<String, Object> m) {
        if (client.uid == null) {
            client.send(Protocol.error(action, "请先登录"));
            return;
        }
        switch (action) {
            case Actions.SEND:          doSend(ctx, client, m); break;
            case Actions.HISTORY:       doHistory(ctx, client, m); break;
            case Actions.SEND_FILE:     doSendFile(ctx, client, m); break;
            case Actions.FILE_DOWNLOAD: doFileDownload(ctx, client, m); break;
            default:
        }
    }

    // ---------------- 文字消息 ----------------

    private void doSend(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        String to = Json.str(m.get(Protocol.F_TO));
        String content = Json.str(m.get(Protocol.F_CONTENT));
        if (to == null || content == null || content.trim().isEmpty()) return;
        content = content.trim();
        if (content.length() > 2000) content = content.substring(0, 2000);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", ctx.genMsgId());
        msg.put("from", client.uid);
        msg.put("type", "text");
        msg.put("content", content);
        msg.put("time", System.currentTimeMillis());

        Route route = resolveRoute(ctx, to, client.uid);
        if (route == null) return;
        persistAndDeliver(ctx, route, msg, null);
    }

    // ---------------- 文件发送 ----------------

    private void doSendFile(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        String to = Json.str(m.get(Protocol.F_TO));
        String fileName = Json.str(m.get(Protocol.F_FILENAME));
        String b64 = Json.str(m.get(Protocol.F_DATA));
        if (to == null || fileName == null || b64 == null || b64.isEmpty()) return;

        // 文件名只保留纯名字，防止路径穿越
        fileName = fileName.replace('\\', '/');
        if (fileName.contains("/")) fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        fileName = fileName.trim();
        if (fileName.isEmpty() || fileName.length() > 200) fileName = "未命名文件";

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            client.send(Protocol.error(Actions.SEND_FILE, "文件数据格式错误"));
            return;
        }
        if (bytes.length == 0) {
            client.send(Protocol.error(Actions.SEND_FILE, "文件内容为空"));
            return;
        }
        if (bytes.length > MAX_FILE_SIZE) {
            client.send(Protocol.error(Actions.SEND_FILE, "文件不能超过 20 MB"));
            return;
        }

        Route route = resolveRoute(ctx, to, client.uid);
        if (route == null) return;

        String fileId = ctx.genMsgId();
        try {
            Files.createDirectories(FILES_DIR);
            Files.write(FILES_DIR.resolve(fileId), bytes);
        } catch (Exception e) {
            System.out.println("[文件] 保存失败: " + e.getMessage());
            client.send(Protocol.error(Actions.SEND_FILE, "服务器保存文件失败"));
            return;
        }

        // 入库的消息记录不含二进制内容
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", fileId);
        msg.put("from", client.uid);
        msg.put("type", "file");
        msg.put(Protocol.F_FILE_ID, fileId);
        msg.put(Protocol.F_FILENAME, fileName);
        msg.put(Protocol.F_SIZE, bytes.length);
        msg.put("time", System.currentTimeMillis());

        System.out.println("[文件] " + client.uid + " 发送 " + fileName
                + " (" + bytes.length + " 字节) → " + to);
        // 实时投递时附带内容，在线用户无需再次请求
        persistAndDeliver(ctx, route, msg, Base64.getEncoder().encodeToString(bytes));
    }

    // ---------------- 历史文件补取 ----------------

    private void doFileDownload(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        String fileId = Json.str(m.get(Protocol.F_FILE_ID));
        if (fileId == null || fileId.isEmpty()) return;

        String foundConv = null;
        Map<String, Object> foundMsg = null;
        synchronized (ctx.dbLock) {
            for (Map.Entry<String, Object> e : ctx.messages().entrySet()) {
                List<Object> list = Json.arr(e.getValue());
                if (list == null) continue;
                for (Object o : list) {
                    Map<String, Object> mm = Json.obj(o);
                    if (mm != null && fileId.equals(Json.str(mm.get(Protocol.F_FILE_ID)))) {
                        foundConv = e.getKey();
                        foundMsg = mm;
                        break;
                    }
                }
                if (foundMsg != null) break;
            }
        }
        if (foundMsg == null || !canAccess(ctx, foundConv, client.uid)) {
            client.send(Protocol.error(Actions.FILE_DOWNLOAD, "文件不存在或无权获取"));
            return;
        }
        Path p = FILES_DIR.resolve(fileId);
        if (!Files.exists(p)) {
            client.send(Protocol.error(Actions.FILE_DOWNLOAD, "服务器上的文件已不存在"));
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(p);
            client.send(Protocol.fileData(fileId,
                    Json.str(foundMsg.get(Protocol.F_FILENAME)),
                    bytes.length, Base64.getEncoder().encodeToString(bytes)));
        } catch (Exception e) {
            client.send(Protocol.error(Actions.FILE_DOWNLOAD, "读取文件失败"));
        }
    }

    // ---------------- 历史消息（最近 100 条） ----------------

    private void doHistory(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        String convId = Json.str(m.get(Protocol.F_CONV_ID));
        if (convId == null) return;
        List<Object> all = Json.arr(ctx.messages().get(convId));
        List<Object> out = new ArrayList<>();
        if (all != null) {
            int from = Math.max(0, all.size() - 100);
            for (int i = from; i < all.size(); i++) out.add(all.get(i));
        }
        client.send(Protocol.history(convId, out));
    }

    // ---------------- 路由 / 权限 / 投递 ----------------

    /** 一次投递的目标信息：会话 id + 收件人 uid 列表 */
    private static class Route {
        String convId;
        List<String> targets;
    }

    private Route resolveRoute(ServerContext ctx, String to, String fromUid) {
        Route r = new Route();
        r.targets = new ArrayList<>();
        if (to.startsWith("u:")) {
            String other = to.substring(2);
            if (ctx.findUserByUid(other) == null) return null;
            String[] pair = {fromUid, other};
            Arrays.sort(pair);
            r.convId = "pri:" + pair[0] + "_" + pair[1];
            r.targets.add(fromUid);
            if (!other.equals(fromUid)) r.targets.add(other);
        } else if (to.startsWith("grp:")) {
            String gid = to.substring(4);
            Map<String, Object> group = null;
            for (Object o : ctx.groups()) {
                Map<String, Object> gm = Json.obj(o);
                if (gm != null && gid.equals(gm.get("gid"))) { group = gm; break; }
            }
            if (group == null) return null;
            r.convId = "grp:" + gid;
            if ("default".equals(gid)) {
                for (Object o : ctx.users()) {
                    Map<String, Object> u = Json.obj(o);
                    if (u != null && u.get("uid") != null) r.targets.add(String.valueOf(u.get("uid")));
                }
            } else {
                Object members = group.get("members");
                if (members instanceof List) {
                    for (Object mm : (List<?>) members) r.targets.add(String.valueOf(mm));
                }
            }
            if (!r.targets.contains(fromUid)) r.targets.add(fromUid);
        } else {
            return null;
        }
        return r;
    }

    /** 判断 uid 是否有权访问该会话（用于文件补取鉴权） */
    private boolean canAccess(ServerContext ctx, String convId, String uid) {
        if (convId == null) return false;
        if (convId.startsWith("grp:")) {
            String gid = convId.substring(4);
            if ("default".equals(gid)) return ctx.findUserByUid(uid) != null;
            for (Object o : ctx.groups()) {
                Map<String, Object> gm = Json.obj(o);
                if (gm != null && gid.equals(gm.get("gid"))) {
                    List<Object> members = Json.arr(gm.get("members"));
                    return members != null && members.contains(uid);
                }
            }
            return false;
        }
        if (convId.startsWith("pri:")) {
            for (String p : convId.substring(4).split("_")) {
                if (p.equals(uid)) return true;
            }
        }
        return false;
    }

    /** 消息入库 + 投递给所有在线当事人（realtimeData 非空时为文件消息的实时内容） */
    private void persistAndDeliver(ServerContext ctx, Route route,
                                   Map<String, Object> msg, String realtimeData) {
        synchronized (ctx.dbLock) {
            List<Object> list = Json.arr(ctx.messages().get(route.convId));
            if (list == null) {
                list = new ArrayList<>();
                ctx.messages().put(route.convId, list);
            }
            list.add(msg);
            ctx.saveDB();
        }
        Map<String, Object> pushMsg = msg;
        if (realtimeData != null) {
            pushMsg = new LinkedHashMap<>(msg);
            pushMsg.put(Protocol.F_DATA, realtimeData);
        }
        Map<String, Object> evt = Protocol.message(route.convId, pushMsg);
        for (String t : route.targets) {
            ClientHandler h = ctx.online.get(t);
            if (h != null) h.send(evt);
        }
    }
}
