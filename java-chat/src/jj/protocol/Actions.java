package jj.protocol;

/**
 * 协议模块 · 客户端 → 服务端的动作名（JSON 里的 "action" 字段取值）。
 * 所有插件通过这里注册自己处理的动作，禁止在业务代码里写魔法字符串。
 */
public final class Actions {

    private Actions() {}

    // —— AuthPlugin：注册 / 登录 / 退出 ——
    public static final String REGISTER         = "register";
    public static final String LOGIN            = "login";
    public static final String LOGOUT           = "logout";

    // —— MessagePlugin：私聊/群聊/历史/文件 ——
    public static final String SEND             = "send";
    public static final String HISTORY          = "history";
    public static final String SEND_FILE        = "send_file";     // 发送文件
    public static final String FILE_DOWNLOAD    = "file_download"; // 补取历史文件

    // —— PasswordPlugin：用户自助改密 / 后台重置密码 ——
    public static final String CHANGE_PASSWORD    = "change_password";
    public static final String ADMIN_SET_PASSWORD = "admin_set_password";

    // —— AdminPlugin：后台登录/列表/改资料/删除 ——
    public static final String ADMIN_LOGIN      = "admin_login";
    public static final String ADMIN_USERS      = "admin_users";
    public static final String ADMIN_SET_PROFILE = "admin_set_profile";
    public static final String ADMIN_DELETE     = "admin_delete";
}
