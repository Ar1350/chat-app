package jj.server.plugin;

import jj.common.Json;
import jj.protocol.Actions;
import jj.protocol.Events;
import jj.protocol.Protocol;
import jj.server.ClientHandler;
import jj.server.ServerContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 认证插件：用户注册、登录、退出。
 * 独立模块，不与消息/后台等功能耦合。
 */
public class AuthPlugin implements ServerPlugin {

    @Override
    public String name() {
        return "认证插件(注册/登录)";
    }

    @Override
    public Set<String> actions() {
        return Set.of(Actions.REGISTER, Actions.LOGIN, Actions.LOGOUT);
    }

    @Override
    public void handle(ServerContext ctx, ClientHandler client, String action, Map<String, Object> m) {
        switch (action) {
            case Actions.REGISTER: doRegister(ctx, client, m); break;
            case Actions.LOGIN:    doLogin(ctx, client, m);    break;
            case Actions.LOGOUT:   client.close();             break;
            default:
        }
    }

    // ---------------- 注册 ----------------

    private void doRegister(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        String username = Json.str(m.get("username"));
        username = username == null ? "" : username.trim();
        String realName = Json.str(m.get("realName"));
        realName = realName == null ? "" : realName.trim();
        String password = Json.str(m.get("password"));
        String avatar = Json.str(m.get("avatar"));
        if (avatar == null || avatar.isEmpty() || avatar.length() > 8) avatar = "😀";

        if (!ServerContext.validUsername(username)) {
            client.send(Protocol.error(Actions.REGISTER, "账号需为 2-20 位字母、数字、下划线或中文"));
            return;
        }
        if (realName.isEmpty() || realName.length() > 12) {
            client.send(Protocol.error(Actions.REGISTER, "请输入实际名字（12 字以内）"));
            return;
        }
        if (!ServerContext.validPassword(password)) {
            client.send(Protocol.error(Actions.REGISTER, "密码需为 6-20 位"));
            return;
        }

        StringBuilder err = new StringBuilder();
        Long age = ServerContext.parseAge(m.get("age"), err);
        if (err.length() > 0) {
            client.send(Protocol.error(Actions.REGISTER, err.toString()));
            return;
        }
        String phone = ServerContext.parsePhone(m.get("phone"));
        if (phone == null) {
            client.send(Protocol.error(Actions.REGISTER, "手机号格式不正确（11 位数字）"));
            return;
        }

        synchronized (ctx.dbLock) {
            for (Object o : ctx.users()) {
                Map<String, Object> u = Json.obj(o);
                if (u != null && username.equalsIgnoreCase(String.valueOf(u.get("username")))) {
                    client.send(Protocol.error(Actions.REGISTER, "该账号已被注册"));
                    return;
                }
            }
            long now = System.currentTimeMillis();
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("uid", ctx.genUid());
            u.put("username", username);
            u.put("realName", realName);
            u.put("nickname", realName);
            u.put("avatar", avatar);
            u.put("gender", ServerContext.parseGender(m.get("gender")));
            u.put("age", age);
            u.put("phone", phone);
            u.put("password", password);
            u.put("createdAt", now);
            u.put("lastLoginAt", now);
            ctx.users().add(u);
            ctx.saveDB();
            System.out.println("[注册] " + realName + " (账号:" + username + " / 佳佳号:" + u.get("uid") + ")");
            client.loginAs(Events.REGISTER_OK, u);
        }
    }

    // ---------------- 登录 ----------------

    private void doLogin(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        String username = Json.str(m.get("username"));
        username = username == null ? "" : username.trim();
        String password = Json.str(m.get("password"));

        synchronized (ctx.dbLock) {
            Map<String, Object> u = ctx.findUserByUsername(username);
            if (u == null || !String.valueOf(u.get("password")).equals(password)) {
                client.send(Protocol.error(Actions.LOGIN, "账号或密码不正确"));
                return;
            }
            u.put("lastLoginAt", System.currentTimeMillis());
            ctx.saveDB();

            // 同账号重复登录：踢掉旧连接
            String uid = String.valueOf(u.get("uid"));
            ClientHandler old = ctx.online.get(uid);
            if (old != null && old != client) {
                old.send(Protocol.forceLogout());
                old.close();
                ctx.online.remove(uid, old);
            }
            client.loginAs(Events.LOGIN_OK, u);
        }
    }
}
