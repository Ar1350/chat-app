package jj.client;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * 佳佳聊天 · 桌面窗口版入口
 */
public class ChatApp {

    /** 服务器地址：默认本机；可用 -Dchat.host=IP 或启动参数 --host=IP 覆盖（局域网部署用） */
    public static String HOST = System.getProperty("chat.host", "127.0.0.1");
    public static int PORT = parseIntOr(System.getProperty("chat.port"), 9300);

    /** 解析启动参数 --host=x.x.x.x / --port=9300，供聊天端与管理端共用 */
    public static void applyArgs(String[] args) {
        if (args == null) return;
        for (String a : args) {
            if (a == null) continue;
            if (a.startsWith("--host=")) HOST = a.substring("--host=".length()).trim();
            else if (a.startsWith("--port=")) PORT = parseIntOr(a.substring("--port=".length()).trim(), PORT);
        }
    }

    private static int parseIntOr(String s, int def) {
        try { return s == null || s.isEmpty() ? def : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    /** 可选的通用 emoji 表情（与网页版一致） */
    public static final String[] EMOJIS = {
            "😀", "😄", "😂", "🤣", "😊", "😍", "😘", "😜",
            "🤔", "😎", "🤩", "🥳", "😴", "😭", "😡", "🥺",
            "😱", "👍", "👎", "👌", "🙏", "👏", "💪", "🤝",
            "❤️", "💔", "🔥", "✨", "🎉", "🎁", "🌹", "☕",
            "🍔", "🍉", "⚽", "🎮", "🚀", "💯", "❓", "❗"
    };

    /** 可选头像 */
    public static final String[] AVATARS = {
            "😀", "🐱", "🦊", "🐼", "🐨", "🦁", "🐸", "🐵",
            "🦄", "🐷", "🐧", "🐰", "🦉", "🐙", "🦋", "🌟"
    };

    public static void main(String[] args) {
        applyArgs(args);
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            // 全局字体设为微软雅黑，中文显示更好
            for (javax.swing.UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                // 使用系统 LAF 后再统一调整默认字体
            }
            java.util.Enumeration<Object> it = UIManager.getDefaults().keys();
            while (it.hasMoreElements()) {
                Object key = it.nextElement();
                Object val = UIManager.get(key);
                if (val instanceof javax.swing.plaf.FontUIResource) {
                    // 使用逻辑字体 Dialog：中文走雅黑回退、emoji 走 Segoe UI 符号回退
                    UIManager.put(key, new javax.swing.plaf.FontUIResource("Dialog", 0, 13));
                }
            }
        } catch (Exception ignored) {
        }

        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }
}
