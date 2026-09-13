package jj.client;

import jj.common.Json;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 窗口式登录 / 注册界面
 */
public class LoginFrame extends JFrame implements ChatClient.Listener {

    private static final Color GREEN = new Color(0x07, 0xC1, 0x60);
    private static final Color GREEN_DARK = new Color(0x05, 0x9a, 0x4d);

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JToggleButton tabLogin = new JToggleButton("登 录");

    // 登录表单
    private final HintTextField loginUser = new HintTextField("请输入账号");
    private final HintPasswordField loginPass = new HintPasswordField("请输入密码");
    private final JLabel loginErr = new JLabel(" ");

    // 注册表单
    private final HintTextField regUser = new HintTextField("设置账号（字母/数字，3-20 位）");
    private final HintTextField regRealName = new HintTextField("实际名字");
    private final JComboBox<String> regGender = new JComboBox<>(new String[]{"性别", "保密", "男", "女"});
    private final HintTextField regAge = new HintTextField("年龄");
    private final HintTextField regPhone = new HintTextField("手机号（选填）");
    private final HintPasswordField regPass = new HintPasswordField("设置密码（6-20 位）");
    private final HintPasswordField regPass2 = new HintPasswordField("再次输入密码");
    private final JLabel regErr = new JLabel(" ");
    private final java.util.List<JButton> avatarBtns = new java.util.ArrayList<>();
    private String selectedAvatar = ChatApp.AVATARS[0];

    private ChatClient client;
    private boolean entered = false;

    public LoginFrame() {
        setTitle("佳佳聊天 · 登录");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(400, 620);
        setLocationRelativeTo(null);
        setResizable(false);

        // 年龄框只允许输入数字（最多 3 位），避免中文输入法/字母干扰
        ((javax.swing.text.AbstractDocument) regAge.getDocument()).setDocumentFilter(
                new javax.swing.text.DocumentFilter() {
                    @Override public void replace(FilterBypass fb, int offset, int len, String text,
                                                  javax.swing.text.AttributeSet attr)
                            throws javax.swing.text.BadLocationException {
                        if (text == null || text.isEmpty()) {
                            super.replace(fb, offset, len, text, attr); // 删除操作放行
                            return;
                        }
                        String onlyDigits = text.replaceAll("\\D", "");
                        if (onlyDigits.isEmpty()) return;               // 纯非数字：直接拒绝
                        String cur = fb.getDocument().getText(0, fb.getDocument().getLength());
                        if (cur.length() - len + onlyDigits.length() > 3) return; // 最多 3 位
                        super.replace(fb, offset, len, onlyDigits, attr);
                    }
                    @Override public void insertString(FilterBypass fb, int offset, String text,
                                                       javax.swing.text.AttributeSet attr)
                            throws javax.swing.text.BadLocationException {
                        replace(fb, offset, 0, text, attr);
                    }
                });

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.WHITE);
        setContentPane(root);

        // 顶部标题
        JPanel header = new JPanel();
        header.setBackground(GREEN);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(22, 10, 22, 10));
        JLabel logo = new JLabel("💬", javax.swing.SwingConstants.CENTER);
        logo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 44));
        logo.setAlignmentX(CENTER_ALIGNMENT);
        JLabel title = new JLabel("佳佳聊天", javax.swing.SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 22));
        title.setAlignmentX(CENTER_ALIGNMENT);
        JLabel sub = new JLabel("像 QQ、微信一样，随时开聊");
        sub.setForeground(new Color(0xd9, 0xf7, 0xe7));
        sub.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        sub.setAlignmentX(CENTER_ALIGNMENT);
        header.add(logo);
        header.add(Box.createVerticalStrut(4));
        header.add(title);
        header.add(Box.createVerticalStrut(2));
        header.add(sub);
        root.add(header, BorderLayout.NORTH);

        // 登录 / 注册 切换按钮
        JToggleButton tabReg = new JToggleButton("注 册");
        ButtonGroup tabs = new ButtonGroup();
        tabs.add(tabLogin);
        tabs.add(tabReg);
        tabLogin.setSelected(true);
        Font tabFont = new Font("Microsoft YaHei", Font.BOLD, 15);
        tabLogin.setFont(tabFont);
        tabReg.setFont(tabFont);
        tabLogin.setFocusPainted(false);
        tabReg.setFocusPainted(false);
        JPanel tabPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 10));
        tabPanel.setBackground(Color.WHITE);
        tabPanel.add(tabLogin);
        tabPanel.add(tabReg);
        tabLogin.addActionListener(e -> cards.show(cardPanel, "login"));
        tabReg.addActionListener(e -> cards.show(cardPanel, "register"));

        // 中间卡片
        cardPanel.setBackground(Color.WHITE);
        cardPanel.add(buildLoginCard(), "login");
        cardPanel.add(buildRegisterCard(), "register");

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(Color.WHITE);
        center.add(tabPanel, BorderLayout.NORTH);
        center.add(cardPanel, BorderLayout.CENTER);

        // 底部管理员入口
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottom.setBackground(Color.WHITE);
        JButton adminBtn = new JButton("管理员入口 →");
        adminBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        adminBtn.setForeground(new Color(0x07, 0x57, 0xa8));
        adminBtn.setBorderPainted(false);
        adminBtn.setContentAreaFilled(false);
        adminBtn.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        adminBtn.addActionListener(e -> new AdminFrame((JFrame) null).setVisible(true));
        bottom.add(adminBtn);

        root.add(center, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        // 回车快捷键
        loginPass.addActionListener(e -> doLogin());
        regPass2.addActionListener(e -> doRegister());

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (client != null) client.close();
            }
        });
    }

    // ================= 登录卡片 =================

    private JPanel buildLoginCard() {
        JPanel p = formPanel();
        loginErr.setForeground(new Color(0xd9, 0x30, 0x30));
        loginErr.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        loginErr.setAlignmentX(CENTER_ALIGNMENT);

        p.add(fieldBox(loginUser));
        p.add(Box.createVerticalStrut(10));
        p.add(fieldBox(loginPass));
        p.add(Box.createVerticalStrut(16));
        JButton btn = primaryButton("登 录");
        btn.addActionListener(e -> doLogin());
        p.add(btn);
        p.add(Box.createVerticalStrut(10));
        p.add(loginErr);
        return p;
    }

    // ================= 注册卡片 =================

    private JPanel buildRegisterCard() {
        JPanel p = formPanel();
        regErr.setForeground(new Color(0xd9, 0x30, 0x30));
        regErr.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        regErr.setAlignmentX(CENTER_ALIGNMENT);

        // 头像选择
        JPanel avatarPanel = new JPanel(new java.awt.GridLayout(2, 8, 4, 4));
        avatarPanel.setBackground(Color.WHITE);
        avatarPanel.setAlignmentX(CENTER_ALIGNMENT);
        avatarPanel.setMaximumSize(new Dimension(320, 84));
        for (String av : ChatApp.AVATARS) {
            JButton b = new JButton(av);
            b.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 20));
            b.setFocusPainted(false);
            b.setContentAreaFilled(false);
            b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            b.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            b.addActionListener(e -> {
                selectedAvatar = av;
                for (JButton x : avatarBtns) x.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
                b.setBorder(BorderFactory.createLineBorder(GREEN, 2, true));
            });
            avatarBtns.add(b);
            avatarPanel.add(b);
        }
        if (!avatarBtns.isEmpty()) {
            avatarBtns.get(0).setBorder(BorderFactory.createLineBorder(GREEN, 2, true));
        }
        p.add(avatarPanel);
        p.add(Box.createVerticalStrut(10));

        p.add(fieldBox(regUser));
        p.add(Box.createVerticalStrut(8));
        p.add(fieldBox(regRealName));
        p.add(Box.createVerticalStrut(8));

        // 性别 + 年龄一行：提示文字都在框内，不再有外侧标签
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        row.setBackground(Color.WHITE);
        regGender.setPreferredSize(new Dimension(150, 36));
        regGender.setMinimumSize(new Dimension(150, 36));
        regGender.setMaximumSize(new Dimension(150, 36));
        regGender.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        // 未选择时「性别」显示成灰色提示
        regGender.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if ("性别".equals(value)) setForeground(new Color(0x9a, 0x9a, 0x9a));
                setHorizontalAlignment(CENTER);
                return this;
            }
        });
        regAge.setPreferredSize(new Dimension(150, 36));
        regAge.setMinimumSize(new Dimension(150, 36));
        regAge.setMaximumSize(new Dimension(150, 36));
        regAge.setHorizontalAlignment(javax.swing.JTextField.CENTER);
        regAge.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        regAge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xd0, 0xd0, 0xd0), 1, true),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        regGender.setBackground(Color.WHITE);
        JPanel rowWrap = new JPanel();
        rowWrap.setBackground(Color.WHITE);
        rowWrap.setAlignmentX(CENTER_ALIGNMENT);
        rowWrap.setMaximumSize(new Dimension(314, 40));
        rowWrap.add(regGender);
        rowWrap.add(regAge);
        p.add(rowWrap);
        p.add(Box.createVerticalStrut(8));

        p.add(fieldBox(regPhone));
        p.add(Box.createVerticalStrut(8));
        p.add(fieldBox(regPass));
        p.add(Box.createVerticalStrut(8));
        p.add(fieldBox(regPass2));
        p.add(Box.createVerticalStrut(14));
        JButton btn = primaryButton("注 册");
        btn.addActionListener(e -> doRegister());
        p.add(btn);
        p.add(Box.createVerticalStrut(6));
        p.add(regErr);
        return p;
    }

    // ================= 表单组件辅助 =================

    private JPanel formPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createEmptyBorder(8, 34, 8, 34));
        return p;
    }

    /** 把输入框设为通栏样式（提示文字已在框内，外侧无标签） */
    private JComponent fieldBox(javax.swing.JTextField field) {
        field.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xd0, 0xd0, 0xd0), 1, true),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        field.setAlignmentX(CENTER_ALIGNMENT);
        field.setPreferredSize(new Dimension(314, 38));
        field.setMaximumSize(new Dimension(314, 38));
        return field;
    }

    private JButton primaryButton(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        b.setBackground(GREEN);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        b.setAlignmentX(CENTER_ALIGNMENT);
        b.setMaximumSize(new Dimension(314, 40));
        b.setPreferredSize(new Dimension(314, 40));
        return b;
    }

    // ================= 动作 =================

    private void doLogin() {
        String username = loginUser.getText().trim();
        String password = new String(loginPass.getPassword());
        if (username.isEmpty() || password.isEmpty()) {
            loginErr.setText("请输入账号和密码");
            return;
        }
        loginErr.setText("连接中...");
        connectAndRun(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", "login");
            m.put("username", username);
            m.put("password", password);
            client.send(m);
        }, loginErr);
    }

    private void doRegister() {
        String username = regUser.getText().trim();
        String realName = regRealName.getText().trim();
        String gender = String.valueOf(regGender.getSelectedItem());
        String age = regAge.getText().trim();
        String phone = regPhone.getText().trim();
        String p1 = new String(regPass.getPassword());
        String p2 = new String(regPass2.getPassword());

        if (username.isEmpty() || realName.isEmpty()) { regErr.setText("请填写账号和实际名字"); return; }
        if ("性别".equals(gender)) { regErr.setText("请选择性别"); return; }
        if (p1.length() < 6 || p1.length() > 20) { regErr.setText("密码需为 6-20 位"); return; }
        if (!p1.equals(p2)) { regErr.setText("两次输入的密码不一致"); return; }

        regErr.setText("连接中...");
        connectAndRun(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", "register");
            m.put("username", username);
            m.put("realName", realName);
            m.put("gender", gender);
            m.put("age", age.isEmpty() ? null : age);
            m.put("phone", phone);
            m.put("password", p1);
            m.put("avatar", selectedAvatar);
            client.send(m);
        }, regErr);
    }

    /** 建立连接并在成功后执行发送动作 */
    private void connectAndRun(Runnable action, JLabel errLabel) {
        try {
            client = new ChatClient(ChatApp.HOST, ChatApp.PORT, this);
            action.run();
        } catch (Exception e) {
            errLabel.setText("无法连接服务器，请先启动服务端");
        }
    }

    /** 注册成功：不自动进入聊天，断开注册连接并回到登录页手动登录 */
    private void handleRegisterOk(Map<String, Object> evt) {
        Map<String, Object> newMe = Json.obj(evt.get("me"));
        String username = newMe != null ? Json.str(newMe.get("username")) : null;
        if (username == null || username.isEmpty()) username = regUser.getText().trim();

        // 先摘出当前连接再置空：主动关闭，onDown 不再提示“连接已断开”
        ChatClient regClient = client;
        client = null;
        if (regClient != null) regClient.close(); // 服务端随即把该账号置为离线

        // 清空注册表单里的密码，避免残留/误重复提交
        regPass.setText("");
        regPass2.setText("");
        regErr.setText(" ");

        // 回到登录页，账号自动填好，只留密码给用户输入
        loginUser.setText(username);
        loginPass.setText("");
        loginErr.setText(" ");
        tabLogin.setSelected(true);
        cards.show(cardPanel, "login");
        loginPass.requestFocusInWindow();

        JOptionPane.showMessageDialog(this,
                "注册成功！请使用刚注册的账号和密码登录。",
                "注册成功", JOptionPane.INFORMATION_MESSAGE);
    }

    // ================= 事件处理 =================

    @Override
    public void onEvent(Map<String, Object> evt) {
        String event = Json.str(evt.get("event"));
        SwingUtilities.invokeLater(() -> {
            if (entered) return; // 已交给聊天窗口处理
            switch (String.valueOf(event)) {
                case "login_ok":
                    entered = true;
                    Map<String, Object> me = Json.obj(evt.get("me"));
                    java.util.List<Object> users = Json.arr(evt.get("users"));
                    java.util.List<Object> groups = Json.arr(evt.get("groups"));
                    ChatFrame f = new ChatFrame(client, me, users, groups);
                    client.setListener(f);
                    f.setVisible(true);
                    dispose();
                    break;
                case "register_ok":
                    handleRegisterOk(evt);
                    break;
                case "error":
                    String forWhat = Json.str(evt.get("for"));
                    String msg = Json.str(evt.get("message"));
                    if ("register".equals(forWhat)) {
                        regErr.setText(msg == null ? "注册失败" : msg);
                    } else {
                        loginErr.setText(msg == null ? "登录失败" : msg);
                    }
                    break;
                case "force_logout":
                    javax.swing.JOptionPane.showMessageDialog(this, "账号已在其他窗口登录");
                    break;
                default:
            }
        });
    }

    @Override
    public void onDown() {
        SwingUtilities.invokeLater(() -> {
            // 已进入聊天窗口的连接由 ChatFrame 接管；client==null 是主动关闭（注册成功回登录页）
            if (entered || client == null) return;
            loginErr.setText("连接已断开，请重试");
            regErr.setText("连接已断开，请重试");
            client.close();
            client = null;
        });
    }

    // ================= 框内灰色提示输入框 =================

    /** 普通文本框：内容为空时在框内显示灰色提示字，一打字就消失 */
    static class HintTextField extends javax.swing.JTextField {
        private final String hint;
        HintTextField(String hint) { this.hint = hint; }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty()) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setFont(getFont());
                g2.setColor(new Color(0x9a, 0x9a, 0x9a));
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                java.awt.FontMetrics fm = g2.getFontMetrics();
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                int x = getHorizontalAlignment() == CENTER
                        ? (getWidth() - fm.stringWidth(hint)) / 2
                        : getInsets().left;
                g2.drawString(hint, x, y);
                g2.dispose();
            }
        }
    }

    /** 密码框：未输入时在框内显示灰色提示字（明文显示提示），输入后正常显示圆点 */
    static class HintPasswordField extends JPasswordField {
        private final String hint;
        HintPasswordField(String hint) { this.hint = hint; }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (getPassword().length == 0) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setFont(getFont());
                g2.setColor(new Color(0x9a, 0x9a, 0x9a));
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                java.awt.FontMetrics fm = g2.getFontMetrics();
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(hint, getInsets().left, y);
                g2.dispose();
            }
        }
    }
}
