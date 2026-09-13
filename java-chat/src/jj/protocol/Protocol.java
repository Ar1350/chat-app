package jj.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 协议模块 · 统一的 JSON 报文字段名与报文构建器。
 * 服务端各插件只通过这里组装下行消息，保证传输格式统一。
 */
public final class Protocol {

    private Protocol() {}

    // ---------------- 通用字段名 ----------------
    public static final String F_ACTION   = "action";
    public static final String F_EVENT    = "event";
    public static final String F_FOR      = "for";
    public static final String F_MESSAGE  = "message";
    public static final String F_TOKEN    = "token";
    public static final String F_UID      = "uid";
    public static final String F_USERS    = "users";
    public static final String F_GROUPS   = "groups";
    public static final String F_ME       = "me";
    public static final String F_MSG      = "msg";
    public static final String F_MESSAGES = "messages";
    public static final String F_CONV_ID  = "convId";
    public static final String F_TO       = "to";
    public static final String F_CONTENT  = "content";
    public static final String F_USERNAME = "username";
    public static final String F_PASSWORD = "password";
    public static final String F_OLD_PASSWORD = "oldPassword";
    public static final String F_USER     = "user";
    public static final String F_FILE_ID  = "fileId";
    public static final String F_FILENAME = "fileName";
    public static final String F_SIZE     = "size";
    public static final String F_DATA     = "data";   // 文件二进制内容的 Base64

    /** 按 key/value 交替快速构建有序 Map */
    public static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 错误报文：{event:error, for:来源动作, message:错误信息} */
    public static Map<String, Object> error(String forWhat, String message) {
        return of(F_EVENT, Events.ERROR, F_FOR, forWhat, F_MESSAGE, message);
    }

    /** 在线状态/资料变更/删除广播 */
    public static Map<String, Object> presence(String uid, boolean online,
                                               Map<String, Object> user, boolean deleted) {
        Map<String, Object> m = of(F_EVENT, Events.PRESENCE, F_UID, uid, "online", online);
        if (deleted) m.put("deleted", true);
        m.put(F_USER, user);
        return m;
    }

    /** 强制下线通知 */
    public static Map<String, Object> forceLogout() {
        return of(F_EVENT, Events.FORCE_LOGOUT);
    }

    /** 操作成功的通用应答（后台增删改后） */
    public static Map<String, Object> adminSaved() {
        return of(F_EVENT, Events.ADMIN_SAVED);
    }

    /** 单条实时消息推送 */
    public static Map<String, Object> message(String convId, Map<String, Object> msg) {
        return of(F_EVENT, Events.MSG, F_CONV_ID, convId, F_MSG, msg);
    }

    /** 历史消息应答 */
    public static Map<String, Object> history(String convId, Object messages) {
        return of(F_EVENT, Events.HISTORY, F_CONV_ID, convId, F_MESSAGES, messages);
    }

    /** 文件内容下发（在线时实时推送 / 离线后主动补取） */
    public static Map<String, Object> fileData(String fileId, String fileName,
                                               long size, String base64) {
        return of(F_EVENT, Events.FILE_DATA, F_FILE_ID, fileId,
                  F_FILENAME, fileName, F_SIZE, size, F_DATA, base64);
    }
}
