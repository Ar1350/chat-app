package jj.client;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.Enumeration;

/**
 * 佳佳聊天 · 管理后台独立启动入口
 * 不经过聊天登录窗口，直接打开管理后台登录界面。
 */
public class AdminApp {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            // 逻辑字体 Dialog：中文走雅黑回退、emoji 走 Segoe UI 符号回退
            Enumeration<Object> it = UIManager.getDefaults().keys();
            while (it.hasMoreElements()) {
                Object key = it.nextElement();
                if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource) {
                    UIManager.put(key, new javax.swing.plaf.FontUIResource("Dialog", 0, 13));
                }
            }
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> new AdminFrame(null).setVisible(true));
    }
}
