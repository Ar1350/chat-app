package jj.protocol;

/**
 * 协议模块 · 服务端 → 客户端的事件名（JSON 里的 "event" 字段取值）。
 */
public final class Events {

    private Events() {}

    // —— 认证结果 ——
    public static final String REGISTER_OK      = "register_ok";
    public static final String LOGIN_OK         = "login_ok";
    public static final String FORCE_LOGOUT     = "force_logout";
    public static final String CHANGE_PASSWORD_OK = "change_password_ok"; // 自助改密成功

    // —— 消息 ——
    public static final String MSG              = "msg";
    public static final String HISTORY          = "history";
    public static final String FILE_DATA        = "file_data"; // 文件内容下发（实时推送或补取应答）
    public static final String PRESENCE         = "presence"; // 在线状态 / 资料更新 / 删除广播

    // —— 后台 ——
    public static final String ADMIN_OK         = "admin_ok";
    public static final String ADMIN_USERS      = "admin_users";
    public static final String ADMIN_SAVED      = "admin_saved";

    // —— 通用 ——
    public static final String ERROR            = "error";
}
