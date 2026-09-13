package jj.server.plugin;

import jj.common.Json;
import jj.protocol.Actions;
import jj.protocol.Events;
import jj.protocol.Protocol;
import jj.server.ClientHandler;
import jj.server.ServerContext;

import java.util.Map;
import java.util.Set;

/**
 * 修改密码插件：
 *  1. 用户自助修改密码（change_password）：必须登录、校验旧密码；
 *  2. 管理员重置任意用户密码（admin_set_password）。
 * 独立成一个功能模块，与认证、资料修改互不干扰。
 */
public class PasswordPlugin implements ServerPlugin {

    /** 管理员重置密码的错误来源标记（客户端据此显示在操作区） */
    public static final String ERR_FOR = "admin_pwd";
    /** 用户自助改密的错误来源标记 */
    public static final String SELF_ERR_FOR = "change_password";

    @Override
    public String name() {
        return "修改密码插件(自助改密/后台重置)";
    }

    @Override
    public Set<String> actions() {
        return Set.of(Actions.CHANGE_PASSWORD, Actions.ADMIN_SET_PASSWORD);
    }

    @Override
    public void handle(ServerContext ctx, ClientHandler client, String action, Map<String, Object> m) {
        if (Actions.CHANGE_PASSWORD.equals(action)) {
            doSelfChange(ctx, client, m);
        } else if (Actions.ADMIN_SET_PASSWORD.equals(action)) {
            doAdminReset(ctx, client, m);
        }
    }

    // ---------------- 用户自助修改 ----------------

    private void doSelfChange(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        if (client.uid == null) {
            client.send(Protocol.error(SELF_ERR_FOR, "请先登录"));
            return;
        }
        String oldPwd = Json.str(m.get(Protocol.F_OLD_PASSWORD));
        String newPwd = Json.str(m.get(Protocol.F_PASSWORD));
        if (oldPwd == null || oldPwd.isEmpty() || newPwd == null) {
            client.send(Protocol.error(SELF_ERR_FOR, "请填写旧密码和新密码"));
            return;
        }
        if (!ServerContext.validPassword(newPwd)) {
            client.send(Protocol.error(SELF_ERR_FOR, "新密码需为 6-20 位"));
            return;
        }

        synchronized (ctx.dbLock) {
            Map<String, Object> u = ctx.findUserByUid(client.uid);
            if (u == null) {
                client.send(Protocol.error(SELF_ERR_FOR, "账号不存在"));
                return;
            }
            String current = Json.str(u.get(Protocol.F_PASSWORD));
            if (!oldPwd.equals(current)) {
                client.send(Protocol.error(SELF_ERR_FOR, "旧密码不正确"));
                return;
            }
            if (newPwd.equals(current)) {
                client.send(Protocol.error(SELF_ERR_FOR, "新密码不能与旧密码相同"));
                return;
            }
            u.put(Protocol.F_PASSWORD, newPwd);
            ctx.saveDB();
            System.out.println("[改密] 用户 " + u.get("username") + " 自助修改了密码");
        }
        client.send(Protocol.of(Protocol.F_EVENT, Events.CHANGE_PASSWORD_OK));
    }

    // ---------------- 管理员重置 ----------------

    private void doAdminReset(ServerContext ctx, ClientHandler client, Map<String, Object> m) {
        if (!ctx.adminOk(Json.str(m.get(Protocol.F_TOKEN)))) {
            client.send(Protocol.error("admin", "登录已过期，请重新登录"));
            return;
        }
        String uid = Json.str(m.get(Protocol.F_UID));
        String password = Json.str(m.get(Protocol.F_PASSWORD));
        if (!ServerContext.validPassword(password)) {
            client.send(Protocol.error(ERR_FOR, "密码需为 6-20 位"));
            return;
        }

        synchronized (ctx.dbLock) {
            Map<String, Object> u = ctx.findUserByUid(uid);
            if (u == null) {
                client.send(Protocol.error(ERR_FOR, "用户不存在"));
                return;
            }
            u.put("password", password);
            ctx.saveDB();
            System.out.println("[后台] 管理员重置了 " + u.get("nickname") + "(" + u.get("username") + ") 的密码");
        }
        client.send(Protocol.adminSaved());
    }
}
