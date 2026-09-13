package jj.server.plugin;

import jj.common.Json;
import jj.protocol.Events;
import jj.protocol.Protocol;
import jj.server.ClientHandler;
import jj.server.ServerContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件注册中心 / 请求调度器。
 * ChatServer 启动时把各功能插件 register 进来；
 * 每条客户端请求按 action 查表路由到对应插件。
 */
public class PluginManager {

    private final ServerContext ctx;
    private final List<ServerPlugin> plugins = new ArrayList<>();
    private final Map<String, ServerPlugin> routes = new HashMap<>();

    public PluginManager(ServerContext ctx) {
        this.ctx = ctx;
    }

    public void register(ServerPlugin plugin) {
        plugin.init(ctx);
        plugins.add(plugin);
        for (String action : plugin.actions()) {
            ServerPlugin old = routes.put(action, plugin);
            if (old != null) {
                throw new IllegalStateException("动作 " + action
                        + " 被多个插件同时注册: " + old.name() + " / " + plugin.name());
            }
        }
        System.out.println("[插件] 已加载: " + plugin.name()
                + "（动作: " + String.join(", ", plugin.actions()) + "）");
    }

    public List<ServerPlugin> plugins() {
        return plugins;
    }

    public void dispatch(ClientHandler client, Map<String, Object> data) {
        String action = Json.str(data.get("action"));
        if (action == null) return;
        ServerPlugin plugin = routes.get(action);
        if (plugin != null) {
            plugin.handle(ctx, client, action, data);
        } else {
            client.send(Protocol.error(Events.ERROR, "未知请求: " + action));
        }
    }
}
