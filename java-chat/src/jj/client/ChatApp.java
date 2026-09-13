package jj.client;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * 佳佳聊天 · 桌面窗口版入口
 */
public class ChatApp {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 9300;

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
