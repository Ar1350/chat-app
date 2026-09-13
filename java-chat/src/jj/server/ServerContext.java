package jj.server;

import jj.common.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件共享上下文：数据库、在线连接表、管理员令牌、ID 生成、
 * 用户/群组视图组装、广播、字段校验等所有插件都会用到的能力集中在这里。
 * 插件之间不直接互相依赖，只通过本上下文协作。
 */
public class ServerContext {

    public static final long ADMIN_TTL = 12 * 60 * 60 * 1000L; // 管理员令牌 12 小时有效

    public final Map<String, Object> db;
    public final Path dbFile;
    public final Object dbLock = new Object();
    public final SecureRandom rnd = new SecureRandom();

    /** uid -> 在线连接 */
    public final Map<String, ClientHandler> online = new ConcurrentHashMap<>();
    /** 管理员 token -> 过期时间戳 */
    public final Map<String, Long> adminTokens = new ConcurrentHashMap<>();

    public ServerContext(Map<String, Object> db, Path dbFile) {
        this.db = db;
        this.dbFile = dbFile;
    }

    // ---------------- 数据库 ----------------

    @SuppressWarnings("unchecked")
    public List<Object> users() { return (List<Object>) db.get("users"); }

    @SuppressWarnings("unchecked")
    public List<Object> groups() { return (List<Object>) db.get("groups"); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> messages() { return (Map<String, Object>) db.get("messages"); }

    public void saveDB() {
        try {
            if (dbFile.getParent() != null && !Files.exists(dbFile.getParent())) {
                Files.createDirectories(dbFile.getParent());
            }
            Files.write(dbFile, Json.writePretty(db).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("[警告] 保存数据失败: " + e.getMessage());
        }
    }

    // ---------------- ID 生成 ----------------

    public String genUid() {
        while (true) {
            String uid = String.valueOf(100000 + rnd.nextInt(900000));
            boolean used = false;
            for (Object o : users()) {
                Map<String, Object> u = Json.obj(o);
                if (u != null && uid.equals(u.get("uid"))) { used = true; break; }
            }
            if (!used) return uid;
        }
    }

    public String genMsgId() {
        byte[] b = new byte[8];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------------- 用户查询 ----------------

    public Map<String, Object> findUserByUid(String uid) {
        for (Object o : users()) {
            Map<String, Object> u = Json.obj(o);
            if (u != null && uid.equals(u.get("uid"))) return u;
        }
        return null;
    }

    public Map<String, Object> findUserByUsername(String username) {
        for (Object o : users()) {
            Map<String, Object> u = Json.obj(o);
            if (u != null && username.equals(u.get("username"))) return u;
        }
        return null;
    }

    // ---------------- 对外视图组装 ----------------

    /** 联系人/在线广播用的公开用户信息 */
    public Map<String, Object> pubUser(Map<String, Object> u, boolean isOnline) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uid", u.get("uid"));
        m.put("username", u.get("username"));
        m.put("nickname", u.get("nickname"));
        m.put("avatar", u.get("avatar"));
        m.put("gender", u.get("gender"));
        m.put("age", u.get("age"));
        m.put("phone", u.get("phone"));
        m.put("online", isOnline);
        return m;
    }

    /** 后台用户信息（全部字段，含密码明文） */
    public Map<String, Object> adminUser(Map<String, Object> u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uid", u.get("uid"));
        m.put("username", u.get("username"));
        m.put("realName", u.get("realName") != null ? u.get("realName") : u.get("nickname"));
        m.put("avatar", u.get("avatar"));
        m.put("gender", u.get("gender") != null ? u.get("gender") : "保密");
        m.put("age", u.get("age"));
        m.put("phone", u.get("phone") != null ? u.get("phone") : "");
        m.put("password", u.get("password") != null ? u.get("password") : "");
        m.put("online", online.containsKey(String.valueOf(u.get("uid"))));
        m.put("createdAt", u.get("createdAt"));
        m.put("lastLoginAt", u.get("lastLoginAt"));
        return m;
    }

    /** 登录成功后返回给本人的资料 */
    public Map<String, Object> mePayload(Map<String, Object> u) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("uid", u.get("uid"));
        me.put("username", u.get("username"));
        me.put("realName", u.get("realName") != null ? u.get("realName") : u.get("nickname"));
        me.put("nickname", u.get("nickname"));
        me.put("avatar", u.get("avatar"));
        me.put("gender", u.get("gender"));
        me.put("age", u.get("age"));
        me.put("phone", u.get("phone"));
        me.put("createdAt", u.get("createdAt"));
        me.put("lastLoginAt", u.get("lastLoginAt"));
        return me;
    }

    /** 全员公开信息列表（登录时下发） */
    public List<Object> allUsersList() {
        List<Object> list = new ArrayList<>();
        for (Object o : users()) {
            Map<String, Object> u = Json.obj(o);
            if (u == null || u.get("uid") == null) continue;
            list.add(pubUser(u, online.containsKey(String.valueOf(u.get("uid")))));
        }
        return list;
    }

    /** 群组精简列表（登录时下发） */
    public List<Object> groupsList() {
        List<Object> list = new ArrayList<>();
        for (Object o : groups()) {
            Map<String, Object> g = Json.obj(o);
            if (g == null) continue;
            Map<String, Object> pg = new LinkedHashMap<>();
            pg.put("gid", g.get("gid"));
            pg.put("name", g.get("name"));
            pg.put("members", g.get("members"));
            list.add(pg);
        }
        return list;
    }

    // ---------------- 广播 ----------------

    public void broadcast(Map<String, Object> evt) {
        for (ClientHandler h : online.values()) h.send(evt);
    }

    // ---------------- 管理员令牌 ----------------

    public String createAdminToken() {
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        StringBuilder token = new StringBuilder();
        for (byte x : b) token.append(String.format("%02x", x));
        adminTokens.put(token.toString(), System.currentTimeMillis() + ADMIN_TTL);
        long now = System.currentTimeMillis();
        adminTokens.values().removeIf(exp -> exp < now); // 顺手清理过期令牌
        return token.toString();
    }

    public boolean adminOk(String token) {
        if (token == null) return false;
        Long exp = adminTokens.get(token);
        return exp != null && exp >= System.currentTimeMillis();
    }

    // ---------------- 字段校验（注册 / 改资料 / 改密码 各插件共用） ----------------

    public static boolean validUsername(String s) {
        return s != null && s.matches("[\\w\\u4e00-\\u9fa5]{2,20}");
    }

    public static boolean validPassword(String s) {
        return s != null && s.length() >= 6 && s.length() <= 20;
    }

    /** 解析年龄；空值返回 null，非法时向 err 写入提示 */
    public static Long parseAge(Object o, StringBuilder err) {
        if (o == null) return null;
        if (o instanceof String && ((String) o).trim().isEmpty()) return null;
        Long v = Json.lng(o);
        if (v == null || v < 1 || v > 120) {
            err.append("年龄需为 1-120 的数字");
            return null;
        }
        return v;
    }

    /** 解析手机号；空返回 ""，格式错误返回 null */
    public static String parsePhone(Object o) {
        String p = o == null ? "" : String.valueOf(o).trim();
        if (p.isEmpty()) return "";
        return p.matches("1\\d{10}") ? p : null;
    }

    public static String parseGender(Object o) {
        String g = String.valueOf(o);
        return ("男".equals(g) || "女".equals(g)) ? g : "保密";
    }
}
