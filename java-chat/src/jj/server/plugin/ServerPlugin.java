package jj.server.plugin;

import jj.server.ClientHandler;
import jj.server.ServerContext;

import java.util.Map;
import java.util.Set;

/**
 * 服务端功能插件统一接口。
 * 每个功能（注册登录 / 消息 / 修改密码 / 后台管理）实现一个插件，
 * 通过 actions() 声明自己处理的动作，由 PluginManager 统一分发。
 *
 * 新增功能步骤：
 *   1. 实现本接口；2. 在 actions() 里登记动作名（建议引用 jj.protocol.Actions 常量）；
 *   3. 在 ChatServer 启动时 plugins.register(new XxxPlugin())。
 */
public interface ServerPlugin {

    /** 插件名（日志/调试用） */
    String name();

    /** 本插件处理的 action 集合；返回集合中的动作会被路由到 handle() */
    Set<String> actions();

    /** 注册时的初始化钩子（可选） */
    default void init(ServerContext ctx) {}

    /**
     * 处理一条客户端请求。
     *
     * @param ctx    服务端共享上下文（数据库 / 在线表 / 广播 / 工具方法）
     * @param client 发起请求的连接
     * @param action 动作名（已保证属于 actions()）
     * @param data   完整 JSON 请求
     */
    void handle(ServerContext ctx, ClientHandler client, String action, Map<String, Object> data);
}
