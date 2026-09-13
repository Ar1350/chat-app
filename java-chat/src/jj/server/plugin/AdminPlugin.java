package jj.server.plugin;

import jj.common.Json;
import jj.protocol.Actions;
import jj.protocol.Events;
import jj.protocol.Protocol;
import jj.server.ClientHandler;
import jj.server.ServerContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台管理插件：管理员登录、用户列表（全部字段）、修改用户资料、删除用户。
 * 注意：修改密码已独立为 PasswordPlugin。
 */
public class AdminPlugin implements ServerPlugin {

    private static final String ERR_INFO = "admin_info";
    private static final String ERR_DEL  = "admin_del";

    @Override
    public String name() {
        return "后台管理插件(登录/列表/资料/删除)";
    }

    @Override
    public Set<String> actions() {
        return Set.of(Actions.ADMIN_LOGIN, Actions.ADMIN_USERS,
                Actions.ADMIN_SET_PROFILE, Actions.ADMIN_DELETE);
    }

    @Override
    public void handle(ServerContext ctx, ClientHandler client, String action, Map<String, Object> m) {
        switch (action) {
            case Actions.ADMIN_LOGIN:       doAdminLogin(ctx, client, m); break;
            case Actions.ADMIN_USERS:       doAdminUsers(ctx, client, m); break;
            case Actions.ADMIN_SET_PROFILE: doSetProfile(ctx, client, m); break;
            case Actions.ADMIN_DELETE:      doDelete(ctx, client, m);     break;
            default:
        }
    }

    // ---------------- 管理员登录 ----------------

    private void doAdminLogin(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        String username = Json.str(m.get(Protocol.F_USERNAME));
        String password = Json.str(m.get(Protocol.F_PASSWORD));
        Map<String, Object> admin = Json.obj(ctx.db.get("admin"));
        if (admin == null
                || !String.valueOf(admin.get("username")).equals(username)
                || !String.valueOf(admin.get("password")).equals(password)) {
            client.send(Protocol.error(Actions.ADMIN_LOGIN, "管理员账号或密码不正确"));
            return;
        }
        Map<String, Object> evt = Protocol.of(
                Protocol.F_EVENT, Events.ADMIN_OK,
                Protocol.F_TOKEN, ctx.createAdminToken());
        client.send(evt);
        System.out.println("[后台] 管理员登录成功");
    }

    // ---------------- 用户列表（全部字段） ----------------

    private void doAdminUsers(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        if (!ctx.adminOk(Json.str(m.get(Protocol.F_TOKEN)))) {
            client.send(Protocol.error("admin", "登录已过期，请重新登录"));
            return;
        }
        List<Object> list = new ArrayList<>();
        synchronized (ctx.dbLock) {
            for (Object o : ctx.users()) {
                Map<String, Object> u = Json.obj(o);
                if (u != null) list.add(ctx.adminUser(u));
            }
        }
        client.send(Protocol.of(
                Protocol.F_EVENT, Events.ADMIN_USERS,
                Protocol.F_USERS, list));
    }

    // ---------------- 修改用户资料 ----------------

    private void doSetProfile(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        if (!ctx.adminOk(Json.str(m.get(Protocol.F_TOKEN)))) {
            client.send(Protocol.error("admin", "登录已过期，请重新登录"));
            return;
        }
        String uid = Json.str(m.get(Protocol.F_UID));
        String username = Json.str(m.get(Protocol.F_USERNAME));
        username = username == null ? "" : username.trim();
        String realName = Json.str(m.get("realName"));
        realName = realName == null ? "" : realName.trim();
        String avatar = Json.str(m.get("avatar"));
        if (avatar == null || avatar.isEmpty() || avatar.length() > 8) avatar = "😀";

        if (!ServerContext.validUsername(username)) {
            client.send(Protocol.error(ERR_INFO, "账号需为 2-20 位字母、数字、下划线或中文"));
            return;
        }
        if (realName.isEmpty() || realName.length() > 12) {
            client.send(Protocol.error(ERR_INFO, "实际名字需为 1-12 字"));
            return;
        }
        StringBuilder err = new StringBuilder();
        Long age = ServerContext.parseAge(m.get("age"), err);
        if (err.length() > 0) {
            client.send(Protocol.error(ERR_INFO, err.toString()));
            return;
        }
        String phone = ServerContext.parsePhone(m.get("phone"));
        if (phone == null) {
            client.send(Protocol.error(ERR_INFO, "手机号格式不正确（11 位数字）"));
            return;
        }

        synchronized (ctx.dbLock) {
            Map<String, Object> u = ctx.findUserByUid(uid);
            if (u == null) {
                client.send(Protocol.error(ERR_INFO, "用户不存在"));
                return;
            }
            for (Object o : ctx.users()) {
                Map<String, Object> x = Json.obj(o);
                if (x != null && !uid.equals(x.get("uid"))
                        && username.equalsIgnoreCase(String.valueOf(x.get("username")))) {
                    client.send(Protocol.error(ERR_INFO, "该账号已被其他用户使用"));
                    return;
                }
            }
            u.put("username", username);
            u.put("realName", realName);
            u.put("nickname", realName);
            u.put("avatar", avatar);
            u.put("gender", ServerContext.parseGender(m.get("gender")));
            u.put("age", age);
            u.put("phone", phone);
            ctx.saveDB();

            // 资料变更实时同步给所有在线客户端
            boolean onlineNow = ctx.online.containsKey(uid);
            ctx.broadcast(Protocol.presence(uid, onlineNow, ctx.pubUser(u, onlineNow), false));
            System.out.println("[后台] 管理员修改了 " + realName + "(" + username + ") 的资料");
        }
        client.send(Protocol.adminSaved());
    }

    // ---------------- 删除用户 ----------------

    private void doDelete(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        if (!ctx.adminOk(Json.str(m.get(Protocol.F_TOKEN)))) {
            client.send(Protocol.error("admin", "登录已过期，请重新登录"));
            return;
        }
        String uid = Json.str(m.get(Protocol.F_UID));

        String nickname;
        String username;
        synchronized (ctx.dbLock) {
            Map<String, Object> u = ctx.findUserByUid(uid);
            if (u == null) {
                client.send(Protocol.error(ERR_DEL, "用户不存在"));
                return;
            }
            nickname = String.valueOf(u.get("nickname"));
            username = String.valueOf(u.get("username"));
            ctx.users().remove(u);
            // 从所有群的成员列表移除
            for (Object o : ctx.groups()) {
                Map<String, Object> g = Json.obj(o);
                if (g != null && g.get("members") instanceof List) {
                    ((List<Object>) g.get("members")).remove(uid);
                }
            }
            ctx.saveDB();
            System.out.println("[后台] 管理员删除了用户 " + nickname + "(" + username + ")");
        }

        // 在线则立即踢下线
        ClientHandler h = ctx.online.remove(uid);
        if (h != null) {
            h.send(Protocol.forceLogout());
            h.uid = null; // 避免其断开时再广播一次下线
            h.close();
        }
        // 通知所有在线客户端：该用户已删除
        ctx.broadcast(Protocol.presence(uid, false, null, true));
        client.send(Protocol.adminSaved());
    }
}
