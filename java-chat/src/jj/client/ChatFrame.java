package jj.client;

import jj.common.Json;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 聊天主窗口：联系人列表 + 群聊/私聊 + 表情
 */
public class ChatFrame extends JFrame implements ChatClient.Listener {

    private static final Color GREEN = new Color(0x07, 0xC1, 0x60);
    /** 文件大小上限（与服务端 MessagePlugin 保持一致）：20 MB */
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    private final ChatClient client;
    private Map<String, Object> me;
    private final Map<String, Map<String, Object>> users = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> groups = new LinkedHashMap<>();

    private final DefaultListModel<Contact> contactModel = new DefaultListModel<>();
    private final JList<Contact> contactList = new JList<>(contactModel);
    private Contact active = null;
    private final Set<String> historyLoaded = new HashSet<>();

    private final JLabel chatTitle = new JLabel("  ");
    private final JTextPane chatPane = new JTextPane();
    private final JTextField input = new JTextField();
    private final JPanel emojiPanel = new JPanel(new GridLayout(0, 8, 2, 2));

    /** fileId → 已收到的文件内容（实时推送或补取成功后缓存） */
    private final Map<String, byte[]> fileCache = new LinkedHashMap<>();
    /** 已发起补取、等待 file_data 回来的 fileId */
    private final Set<String> pendingDownloads = new HashSet<>();
    /** 正在补取的文件名（file_data 到达后自动弹保存框用） */
    private final Map<String, String> pendingFileNames = new LinkedHashMap<>();
    /** 补取超时定时器：15 秒无响应则恢复可点击状态 */
    private final Map<String, javax.swing.Timer> downloadTimers = new LinkedHashMap<>();

    /** 修改密码弹窗的运行时引用（服务端应答后回填提示/关闭） */
    private JDialog changePwdDialog;
    private JLabel changePwdErr;
    private JButton changePwdBtn;
    private JPasswordField cpOld;
    private JPasswordField cpNew;
    private JPasswordField cpNew2;

    static class Contact {
        String key;      // "u:<uid>" 或 "grp:<gid>"
        String title;
        String avatar;
        boolean isGroup;
        int unread = 0;

        String convId() {
            if (key.startsWith("grp:")) return key;
            return null; // 私聊由 ChatFrame 计算成员相关 id
        }
    }

    public ChatFrame(ChatClient client, Map<String, Object> me, List<Object> users, List<Object> groups) {
        this.client = client;
        this.me = me;
        setTitle("佳佳聊天 - " + Json.str(me.get("realName")));
        setSize(920, 620);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        for (Object o : users) {
            Map<String, Object> u = Json.obj(o);
            if (u != null) this.users.put(Json.str(u.get("uid")), u);
        }
        for (Object o : groups) {
            Map<String, Object> g = Json.obj(o);
            if (g != null) this.groups.put(Json.str(g.get("gid")), g);
        }

        buildUI();
        refreshContacts();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                client.close();
            }
        });
    }

    // ================= 界面搭建 =================

    private void buildUI() {
        // 左侧联系人
        contactList.setCellRenderer(new ContactRenderer());
        contactList.setBackground(Color.WHITE);
        contactList.setFixedCellHeight(48);
        contactList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) selectContact(contactList.getSelectedValue());
        });
        JScrollPane leftScroll = new JScrollPane(contactList);
        leftScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0xe5, 0xe5, 0xe5)));
        leftScroll.setPreferredSize(new Dimension(240, 0));

        // 右侧聊天区
        chatPane.setEditable(false);
        chatPane.setBackground(new Color(0xf5, 0xf5, 0xf5));
        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.getVerticalScrollBar().setUnitIncrement(16);

        // 标题栏：左侧会话标题，右上角三道杠菜单（修改密码 / 账号资料 / 注销 / 退出）
        chatTitle.setFont(new Font("Dialog", Font.BOLD, 16));
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(Color.WHITE);
        topBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xe5, 0xe5, 0xe5)));
        chatTitle.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 10));

        JPopupMenu menu = buildUserMenu();
        HamburgerButton hamBtn = new HamburgerButton();
        hamBtn.setToolTipText("菜单：修改密码 / 账号资料 / 注销 / 退出");
        hamBtn.addActionListener(e -> {
            Dimension ms = menu.getPreferredSize();
            menu.show(hamBtn, hamBtn.getWidth() - ms.width, hamBtn.getHeight() + 2);
        });
        JPanel topBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 5));
        topBtns.setBackground(Color.WHITE);
        topBtns.add(hamBtn);
        topBar.add(chatTitle, BorderLayout.CENTER);
        topBar.add(topBtns, BorderLayout.EAST);

        // 表情面板（默认隐藏）
        emojiPanel.setBackground(Color.WHITE);
        emojiPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xe0, 0xe0, 0xe0)));
        for (String em : ChatApp.EMOJIS) {
            JButton b = new JButton(em);
            b.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
            b.setContentAreaFilled(false);
            b.setFocusPainted(false);
            b.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            b.addActionListener(e -> input.setText(input.getText() + em));
            emojiPanel.add(b);
        }
        emojiPanel.setVisible(false);

        // 底部输入区
        JButton emojiBtn = new JButton("😀");
        emojiBtn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        emojiBtn.setFocusPainted(false);
        emojiBtn.setContentAreaFilled(false);
        emojiBtn.setToolTipText("表情");
        emojiBtn.addActionListener(e -> emojiPanel.setVisible(!emojiPanel.isVisible()));

        JButton fileBtn = new JButton("📎");
        fileBtn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 17));
        fileBtn.setFocusPainted(false);
        fileBtn.setContentAreaFilled(false);
        fileBtn.setToolTipText("发送文件（单个不超过 20 MB）");
        fileBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        fileBtn.addActionListener(e -> doSendFile());

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        tools.setBackground(Color.WHITE);
        tools.add(emojiBtn);
        tools.add(fileBtn);

        JButton sendBtn = new JButton("发送");
        sendBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        sendBtn.setBackground(GREEN);
        sendBtn.setForeground(Color.WHITE);
        sendBtn.setFocusPainted(false);
        sendBtn.addActionListener(e -> doSend());

        input.setFont(new Font("Dialog", Font.PLAIN, 14));
        input.addActionListener(e -> doSend());

        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.setBackground(Color.WHITE);
        inputRow.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        inputRow.add(tools, BorderLayout.WEST);
        inputRow.add(input, BorderLayout.CENTER);
        inputRow.add(sendBtn, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(Color.WHITE);
        south.add(emojiPanel, BorderLayout.NORTH);
        south.add(inputRow, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(Color.WHITE);
        right.add(topBar, BorderLayout.NORTH);
        right.add(chatScroll, BorderLayout.CENTER);
        right.add(south, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, right);
        split.setDividerLocation(240);
        split.setDividerSize(1);
        split.setEnabled(false);
        setContentPane(split);
    }

    // ================= 联系人 =================

    private void refreshContacts() {
        contactModel.clear();
        // 默认群置顶
        for (Map<String, Object> g : groups.values()) {
            Contact c = new Contact();
            c.key = "grp:" + g.get("gid");
            c.title = Json.str(g.get("name"));
            c.avatar = "default".equals(g.get("gid")) ? "🏠" : "👥";
            c.isGroup = true;
            contactModel.addElement(c);
        }
        for (Map<String, Object> u : users.values()) {
            if (String.valueOf(u.get("uid")).equals(Json.str(me.get("uid")))) continue;
            Contact c = new Contact();
            c.key = "u:" + u.get("uid");
            c.title = Json.str(u.get("nickname"));
            c.avatar = Json.str(u.get("avatar"));
            c.isGroup = false;
            contactModel.addElement(c);
        }
        contactList.repaint();
    }

    private Contact findContact(String key) {
        for (int i = 0; i < contactModel.size(); i++) {
            Contact c = contactModel.get(i);
            if (c.key.equals(key)) return c;
        }
        return null;
    }

    private void selectContact(Contact c) {
        active = c;
        if (c == null) return;
        c.unread = 0;
        chatTitle.setText(" " + c.avatar + "  " + c.title + onlineSuffix(c));
        chatTitle.repaint();
        if (!historyLoaded.contains(c.key)) {
            historyLoaded.add(c.key);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", "history");
            m.put("convId", convIdOf(c));
            client.send(m);
        } else {
            clearChat();
            // 已加载过：重放内存中的内容不必要，向服务器再要一次即可
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", "history");
            m.put("convId", convIdOf(c));
            client.send(m);
        }
        input.requestFocusInWindow();
        contactList.repaint();
    }

    private String convIdOf(Contact c) {
        if (c.key.startsWith("grp:")) return c.key;
        String other = c.key.substring(2);
        String myUid = Json.str(me.get("uid"));
        String[] pair = {myUid, other};
        Arrays.sort(pair);
        return "pri:" + pair[0] + "_" + pair[1];
    }

    private String onlineSuffix(Contact c) {
        if (c.isGroup) return "";
        Map<String, Object> u = users.get(c.key.substring(2));
        boolean on = u != null && Boolean.TRUE.equals(u.get("online"));
        return on ? "   ● 在线" : "   ○ 离线";
    }

    // ================= 消息渲染 =================

    private void clearChat() {
        chatPane.setText("");
    }

    private void appendMsg(String name, String content, long time, boolean self) {
        try {
            StyledDocument doc = chatPane.getStyledDocument();
            java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("HH:mm");

            SimpleAttributeSet align = new SimpleAttributeSet();
            StyleConstants.setAlignment(align, self ? StyleConstants.ALIGN_RIGHT
                                                    : StyleConstants.ALIGN_LEFT);
            SimpleAttributeSet nameAttr = new SimpleAttributeSet();
            StyleConstants.setForeground(nameAttr, self ? GREEN : new Color(0x07, 0x57, 0xa8));
            StyleConstants.setBold(nameAttr, true);
            StyleConstants.setFontSize(nameAttr, 13);
            SimpleAttributeSet timeAttr = new SimpleAttributeSet();
            StyleConstants.setForeground(timeAttr, new Color(0xaa, 0xaa, 0xaa));
            StyleConstants.setFontSize(timeAttr, 11);
            SimpleAttributeSet bodyAttr = new SimpleAttributeSet();
            StyleConstants.setForeground(bodyAttr, new Color(0x22, 0x22, 0x22));
            StyleConstants.setFontSize(bodyAttr, 14);

            int start = doc.getLength();
            doc.insertString(doc.getLength(), name + "  ", nameAttr);
            doc.insertString(doc.getLength(), tf.format(new java.util.Date(time)) + "\n", timeAttr);
            doc.insertString(doc.getLength(), content + "\n\n", bodyAttr);
            doc.setParagraphAttributes(start, doc.getLength() - start, align, false);
            chatPane.setCaretPosition(doc.getLength());
        } catch (Exception ignored) {
        }
    }

    private void appendSystem(String content) {
        try {
            StyledDocument doc = chatPane.getStyledDocument();
            SimpleAttributeSet attr = new SimpleAttributeSet();
            StyleConstants.setForeground(attr, new Color(0xb0, 0xb0, 0xb0));
            StyleConstants.setFontSize(attr, 12);
            StyleConstants.setAlignment(attr, StyleConstants.ALIGN_CENTER);
            int start = doc.getLength();
            doc.insertString(doc.getLength(), "— " + content + " —\n\n", attr);
            doc.setParagraphAttributes(start, doc.getLength() - start, attr, false);
            chatPane.setCaretPosition(doc.getLength());
        } catch (Exception ignored) {
        }
    }

    /** 渲染一条文件消息：名字/时间 + 可点击保存的文件卡片 */
    private void appendFile(String name, String fileId, String fileName,
                            long size, long time, boolean self) {
        try {
            StyledDocument doc = chatPane.getStyledDocument();
            java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("HH:mm");

            SimpleAttributeSet align = new SimpleAttributeSet();
            StyleConstants.setAlignment(align, self ? StyleConstants.ALIGN_RIGHT
                                                    : StyleConstants.ALIGN_LEFT);
            SimpleAttributeSet nameAttr = new SimpleAttributeSet();
            StyleConstants.setForeground(nameAttr, self ? GREEN : new Color(0x07, 0x57, 0xa8));
            StyleConstants.setBold(nameAttr, true);
            StyleConstants.setFontSize(nameAttr, 13);
            SimpleAttributeSet timeAttr = new SimpleAttributeSet();
            StyleConstants.setForeground(timeAttr, new Color(0xaa, 0xaa, 0xaa));
            StyleConstants.setFontSize(timeAttr, 11);

            JButton card = new JButton("📎  " + fileName + "    " + humanSize(size) + "    点击下载保存");
            card.setFont(new Font("Dialog", Font.PLAIN, 13));
            card.setFocusPainted(false);
            card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(self ? GREEN : new Color(0xcf, 0xcf, 0xcf), 1, true),
                    BorderFactory.createEmptyBorder(7, 12, 7, 12)));
            if (self) {
                card.setBackground(new Color(0xe9, 0xfb, 0xf1));
                card.setForeground(new Color(0x1a, 0x7f, 0x4b));
            } else {
                card.setBackground(Color.WHITE);
                card.setForeground(new Color(0x22, 0x22, 0x22));
            }
            card.addActionListener(e -> onFileClick(fileId, fileName));

            SimpleAttributeSet cardAttr = new SimpleAttributeSet();
            StyleConstants.setComponent(cardAttr, card);

            int start = doc.getLength();
            doc.insertString(doc.getLength(), name + "  ", nameAttr);
            doc.insertString(doc.getLength(), tf.format(new java.util.Date(time)) + "\n", timeAttr);
            doc.insertString(doc.getLength(), " ", cardAttr);
            doc.insertString(doc.getLength(), "\n\n", timeAttr);
            doc.setParagraphAttributes(start, doc.getLength() - start, align, false);
            chatPane.setCaretPosition(doc.getLength());
        } catch (Exception ignored) {
        }
    }

    // ================= 发送 =================

    private void doSend() {
        if (active == null) return;
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "send");
        m.put("to", active.key);
        m.put("content", text);
        client.send(m);
        input.setText("");
    }

    // ================= 右上角三道杠菜单 =================

    /** 三道杠图标按钮（直接画三条线，避免 emoji 字体渲染成方块） */
    private class HamburgerButton extends JButton {
        private boolean hover = false;
        HamburgerButton() {
            setPreferredSize(new Dimension(38, 32));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (hover) {
                g2.setColor(new Color(0xee, 0xee, 0xee));
                g2.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 8, 8);
            }
            g2.setColor(new Color(0x33, 0x33, 0x33));
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int x1 = 11, x2 = getWidth() - 11, y0 = getHeight() / 2;
            g2.drawLine(x1, y0 - 6, x2, y0 - 6);
            g2.drawLine(x1, y0,     x2, y0);
            g2.drawLine(x1, y0 + 6, x2, y0 + 6);
            g2.dispose();
        }
    }

    private JPopupMenu buildUserMenu() {
        JPopupMenu menu = new JPopupMenu();
        Font mf = new Font("Microsoft YaHei", Font.PLAIN, 14);

        JMenuItem miPwd = new JMenuItem("修改密码");
        miPwd.setFont(mf);
        miPwd.addActionListener(e -> showChangePasswordDialog());

        JMenuItem miProfile = new JMenuItem("账号资料");
        miProfile.setFont(mf);
        miProfile.addActionListener(e -> showProfileDialog());

        JMenuItem miLogout = new JMenuItem("注销账号");
        miLogout.setFont(mf);
        miLogout.addActionListener(e -> doLogout());

        JMenuItem miExit = new JMenuItem("退出程序");
        miExit.setFont(mf);
        miExit.addActionListener(e -> doExit());

        menu.add(miPwd);
        menu.add(miProfile);
        menu.addSeparator();
        menu.add(miLogout);
        menu.add(miExit);
        return menu;
    }

    // ---------------- 修改密码弹窗 ----------------

    private void showChangePasswordDialog() {
        if (changePwdDialog != null && changePwdDialog.isVisible()) {
            changePwdDialog.toFront();
            return;
        }
        JDialog dlg = new JDialog(this, "修改密码", true);
        dlg.setLayout(new BorderLayout());

        JLabel title = new JLabel("修改我的登录密码");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        title.setBorder(BorderFactory.createEmptyBorder(16, 20, 10, 20));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(4, 20, 4, 20));
        form.setBackground(Color.WHITE);

        cpOld = new JPasswordField(18);
        cpNew = new JPasswordField(18);
        cpNew2 = new JPasswordField(18);
        Font ff = new Font("Microsoft YaHei", Font.PLAIN, 14);
        cpOld.setFont(ff); cpNew.setFont(ff); cpNew2.setFont(ff);

        form.add(pwdRow("旧 密 码", cpOld));
        form.add(Box.createVerticalStrut(8));
        form.add(pwdRow("新 密 码", cpNew));
        form.add(Box.createVerticalStrut(8));
        form.add(pwdRow("确认新密码", cpNew2));
        form.add(Box.createVerticalStrut(6));
        JLabel tip = new JLabel("新密码为 6-20 位，修改后下次登录使用新密码");
        tip.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        tip.setForeground(new Color(0x9a, 0x9a, 0x9a));
        tip.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(tip);

        changePwdErr = new JLabel(" ");
        changePwdErr.setForeground(new Color(0xd9, 0x30, 0x30));
        changePwdErr.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        changePwdErr.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(changePwdErr);

        changePwdBtn = new JButton("确认修改");
        changePwdBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        changePwdBtn.setBackground(GREEN);
        changePwdBtn.setForeground(Color.WHITE);
        changePwdBtn.setFocusPainted(false);
        changePwdBtn.setBorderPainted(false);
        changePwdBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        changePwdBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        changePwdBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        changePwdBtn.addActionListener(e -> submitChangePassword());
        form.add(changePwdBtn);

        JButton cancelBtn = new JButton("取消");
        cancelBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        cancelBtn.setFocusPainted(false);
        cancelBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        cancelBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        cancelBtn.addActionListener(e -> dlg.dispose());
        form.add(Box.createVerticalStrut(6));
        form.add(cancelBtn);
        form.add(Box.createVerticalStrut(14));

        cpNew2.addActionListener(e -> submitChangePassword());

        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(Color.WHITE);
        north.add(title, BorderLayout.NORTH);
        north.add(form, BorderLayout.CENTER);
        dlg.add(north, BorderLayout.CENTER);
        dlg.pack();
        dlg.setSize(340, dlg.getHeight());
        dlg.setLocationRelativeTo(this);
        changePwdDialog = dlg;
        dlg.setVisible(true);
    }

    private JPanel pwdRow(String label, JPasswordField field) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setBackground(Color.WHITE);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        JLabel l = new JLabel(label);
        l.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        l.setPreferredSize(new Dimension(72, 30));
        field.setPreferredSize(new Dimension(190, 32));
        row.add(l, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private void submitChangePassword() {
        String oldP = new String(cpOld.getPassword());
        String p1 = new String(cpNew.getPassword());
        String p2 = new String(cpNew2.getPassword());
        if (oldP.isEmpty() || p1.isEmpty() || p2.isEmpty()) {
            changePwdErr.setText("请填写完整");
            return;
        }
        if (p1.length() < 6 || p1.length() > 20) {
            changePwdErr.setText("新密码需为 6-20 位");
            return;
        }
        if (!p1.equals(p2)) {
            changePwdErr.setText("两次输入的新密码不一致");
            return;
        }
        if (p1.equals(oldP)) {
            changePwdErr.setText("新密码不能与旧密码相同");
            return;
        }
        changePwdErr.setText("提交中…");
        changePwdBtn.setEnabled(false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "change_password");
        m.put("oldPassword", oldP);
        m.put("password", p1);
        client.send(m);
    }

    private void onChangePasswordResult(boolean ok, String message) {
        if (changePwdDialog == null || !changePwdDialog.isVisible()) {
            if (!ok) JOptionPane.showMessageDialog(this, message);
            return;
        }
        changePwdBtn.setEnabled(true);
        if (ok) {
            changePwdDialog.dispose();
            JOptionPane.showMessageDialog(this, "密码修改成功！\n下次登录请使用新密码。");
        } else {
            changePwdErr.setText(message == null ? "修改失败，请重试" : message);
        }
    }

    // ---------------- 账号资料弹窗（设置） ----------------

    private void showProfileDialog() {
        JDialog dlg = new JDialog(this, "账号资料", true);
        dlg.setLayout(new BorderLayout());
        dlg.setBackground(Color.WHITE);

        JPanel head = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 14));
        head.setBackground(Color.WHITE);
        JLabel avatar = new JLabel(me.get("avatar") == null ? "🙂" : String.valueOf(me.get("avatar")));
        avatar.setFont(new Font("Dialog", Font.PLAIN, 40));
        JLabel nick = new JLabel(displayName(me));
        nick.setFont(new Font("Microsoft YaHei", Font.BOLD, 18));
        head.add(avatar);
        head.add(nick);

        JPanel grid = new JPanel(new GridLayout(0, 2, 6, 10));
        grid.setBackground(Color.WHITE);
        grid.setBorder(BorderFactory.createEmptyBorder(6, 30, 14, 30));
        addInfoRow(grid, "账号", str("username"));
        addInfoRow(grid, "佳佳号", str("uid"));
        addInfoRow(grid, "实际名字", str("realName"));
        addInfoRow(grid, "性别", str("gender"));
        addInfoRow(grid, "年龄", str("age"));
        addInfoRow(grid, "手机号", str("phone"));
        Object created = me.get("createdAt");
        String createdText = "-";
        if (created instanceof Number) {
            createdText = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                    .format(new java.util.Date(((Number) created).longValue()));
        }
        addInfoRow(grid, "注册时间", createdText);

        JButton closeBtn = new JButton("关 闭");
        closeBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        closeBtn.setBackground(GREEN);
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setFocusPainted(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.setPreferredSize(new Dimension(260, 36));
        closeBtn.addActionListener(e -> dlg.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 12));
        south.setBackground(Color.WHITE);
        south.add(closeBtn);

        dlg.add(head, BorderLayout.NORTH);
        dlg.add(grid, BorderLayout.CENTER);
        dlg.add(south, BorderLayout.SOUTH);
        dlg.setSize(340, 430);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private void addInfoRow(JPanel grid, String label, String value) {
        JLabel l = new JLabel(label);
        l.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        l.setForeground(new Color(0x88, 0x88, 0x88));
        JLabel v = new JLabel(value == null || value.isEmpty() ? "-" : value);
        v.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        grid.add(l);
        grid.add(v);
    }

    private String str(String key) {
        Object v = me.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private String displayName(Map<String, Object> u) {
        String n = Json.str(u.get("nickname"));
        if (n == null || n.isEmpty()) n = Json.str(u.get("username"));
        return n == null ? "" : n;
    }

    // ================= 注销 / 退出 =================

    private void doLogout() {
        int ok = JOptionPane.showConfirmDialog(this,
                "确定注销当前账号吗？", "注销",
                JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "logout");
        client.send(m);
        backToLogin();
    }

    private void doExit() {
        int ok = JOptionPane.showConfirmDialog(this,
                "确定退出佳佳聊天吗？", "退出",
                JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        client.close();
        System.exit(0);
    }

    // ================= 文件发送 =================

    private void doSendFile() {
        if (active == null) {
            javax.swing.JOptionPane.showMessageDialog(this, "请先在左侧选择一个联系人或群");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择要发送的文件（发给：" + active.title + "）");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        if (f == null || !f.isFile()) return;
        if (f.length() > MAX_FILE_SIZE) {
            javax.swing.JOptionPane.showMessageDialog(this, "文件不能超过 20 MB");
            return;
        }

        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(f.toPath());
        } catch (Exception ex) {
            javax.swing.JOptionPane.showMessageDialog(this, "读取文件失败：" + ex.getMessage());
            return;
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "send_file");
        m.put("to", active.key);
        m.put("fileName", f.getName());
        m.put("size", bytes.length);
        m.put("data", Base64.getEncoder().encodeToString(bytes));
        client.send(m);
        appendSystem("正在发送文件：" + f.getName() + "（" + humanSize(bytes.length) + "）");
    }

    /** 点击文件卡片：本地有内容直接保存；没有就向服务器补取，数据回来后自动弹保存框（只需点一次） */
    private void onFileClick(String fileId, String fileName) {
        byte[] bytes = fileCache.get(fileId);
        if (bytes != null) {
            saveFileDialog(fileName, bytes);
            return;
        }
        if (pendingDownloads.contains(fileId)) return; // 正在下载，忽略重复点击
        pendingDownloads.add(fileId);
        pendingFileNames.put(fileId, fileName);
        appendSystem("正在从服务器下载文件：" + fileName + " …");
        // 15 秒没收到响应：清除等待状态，允许重新点击
        javax.swing.Timer timer = new javax.swing.Timer(15000, ev -> {
            if (pendingDownloads.remove(fileId)) {
                pendingFileNames.remove(fileId);
                downloadTimers.remove(fileId);
                appendSystem("文件 " + fileName + " 下载超时，请确认服务器开着，然后重新点击卡片");
            }
        });
        timer.setRepeats(false);
        downloadTimers.put(fileId, timer);
        timer.start();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("action", "file_download");
        req.put("fileId", fileId);
        client.send(req);
    }

    private void saveFileDialog(String fileName, byte[] bytes) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存文件");
        // 默认存到“下载”文件夹，避免上次定位到系统目录导致写入被拒
        File downloads = new File(System.getProperty("user.home"), "Downloads");
        chooser.setCurrentDirectory(downloads.isDirectory() ? downloads
                : new File(System.getProperty("user.home")));
        chooser.setSelectedFile(new File(fileName));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File target = chooser.getSelectedFile();
        try {
            Files.write(target.toPath(), bytes);
            javax.swing.JOptionPane.showMessageDialog(this,
                    "文件已保存到：\n" + target.getAbsolutePath());
        } catch (Exception ex) {
            javax.swing.JOptionPane.showMessageDialog(this, "保存失败：" + ex.getMessage());
        }
    }

    private static String humanSize(long size) {
        if (size >= 1024L * 1024 * 1024) return String.format("%.1f GB", size / 1024.0 / 1024 / 1024);
        if (size >= 1024L * 1024) return String.format("%.1f MB", size / 1024.0 / 1024);
        if (size >= 1024L) return String.format("%.1f KB", size / 1024.0);
        return size + " B";
    }

    // ================= 事件处理 =================

    @Override
    public void onEvent(Map<String, Object> evt) {
        String event = Json.str(evt.get("event"));
        SwingUtilities.invokeLater(() -> handleEvent(event, evt));
    }

    private void handleEvent(String event, Map<String, Object> evt) {
        switch (String.valueOf(event)) {
            case "msg": {
                Map<String, Object> msg = Json.obj(evt.get("msg"));
                if (msg == null) return;
                String convId = Json.str(evt.get("convId"));
                Contact target = contactOfConvId(convId);
                String fromUid = Json.str(msg.get("from"));
                Map<String, Object> fromUser = users.get(fromUid);
                String name = fromUser != null && Json.str(fromUser.get("nickname")) != null
                        ? Json.str(fromUser.get("nickname")) : "未知用户";
                boolean self = fromUid != null && fromUid.equals(Json.str(me.get("uid")));
                long time = msg.get("time") instanceof Number ? ((Number) msg.get("time")).longValue()
                                                              : System.currentTimeMillis();
                boolean isFile = "file".equals(String.valueOf(msg.get("type")));

                if (isFile) {
                    // 在线收到的实时文件：先缓存内容
                    String dataB64 = Json.str(msg.get("data"));
                    String fileId = Json.str(msg.get("fileId"));
                    if (dataB64 != null && fileId != null) {
                        try {
                            fileCache.put(fileId, Base64.getDecoder().decode(dataB64));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    if (target == active) {
                        appendFile(name, fileId, Json.str(msg.get("fileName")),
                                msg.get("size") instanceof Number ? ((Number) msg.get("size")).longValue() : 0,
                                time, self);
                    } else if (target != null) {
                        target.unread++;
                        contactList.repaint();
                    }
                } else {
                    String content = Json.str(msg.get("content"));
                    if (target == active) {
                        appendMsg(name, content, time, self);
                    } else if (target != null) {
                        target.unread++;
                        contactList.repaint();
                    }
                }
                break;
            }
            case "history": {
                String convId = Json.str(evt.get("convId"));
                if (active == null || !convIdOf(active).equals(convId)) return;
                clearChat();
                List<Object> msgs = Json.arr(evt.get("messages"));
                if (msgs != null) {
                    for (Object o : msgs) {
                        Map<String, Object> msg = Json.obj(o);
                        if (msg == null) continue;
                        String fromUid = Json.str(msg.get("from"));
                        boolean self = fromUid != null && fromUid.equals(Json.str(me.get("uid")));
                        Map<String, Object> fromUser = users.get(fromUid);
                        String name = self ? "我"
                                : (fromUser != null && Json.str(fromUser.get("nickname")) != null
                                        ? Json.str(fromUser.get("nickname")) : "系统");
                        long time = msg.get("time") instanceof Number
                                ? ((Number) msg.get("time")).longValue() : System.currentTimeMillis();
                        if ("file".equals(String.valueOf(msg.get("type")))) {
                            // 历史文件卡片：内容不在本地时点击会向服务器补取
                            String fileId = Json.str(msg.get("fileId"));
                            String dataB64 = Json.str(msg.get("data"));
                            if (dataB64 != null && fileId != null) {
                                try { fileCache.put(fileId, Base64.getDecoder().decode(dataB64)); }
                                catch (IllegalArgumentException ignored) {}
                            }
                            appendFile(name, fileId, Json.str(msg.get("fileName")),
                                    msg.get("size") instanceof Number ? ((Number) msg.get("size")).longValue() : 0,
                                    time, self);
                        } else {
                            appendMsg(name, Json.str(msg.get("content")), time, self);
                        }
                    }
                }
                if (msgs == null || msgs.isEmpty()) appendSystem("还没有消息，发一条打个招呼吧");
                break;
            }
            case "file_data": {
                String fileId = Json.str(evt.get("fileId"));
                String fileName = Json.str(evt.get("fileName"));
                String dataB64 = Json.str(evt.get("data"));
                if (fileId == null || dataB64 == null) break;
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(dataB64);
                } catch (IllegalArgumentException e) {
                    appendSystem("文件接收失败：数据损坏，请重新点击卡片");
                    pendingDownloads.remove(fileId);
                    pendingFileNames.remove(fileId);
                    javax.swing.Timer bad = downloadTimers.remove(fileId);
                    if (bad != null) bad.stop();
                    break;
                }
                fileCache.put(fileId, bytes);
                boolean requested = pendingDownloads.remove(fileId);
                String saveName = pendingFileNames.remove(fileId);
                javax.swing.Timer t = downloadTimers.remove(fileId);
                if (t != null) t.stop();
                // 用户点过卡片：自动弹出保存框，无需再点第二次
                if (requested && saveName != null) {
                    saveFileDialog(saveName, bytes);
                }
                break;
            }
            case "change_password_ok":
                onChangePasswordResult(true, null);
                break;
            case "error": {
                String forWhat = Json.str(evt.get("for"));
                String msgText = Json.str(evt.get("message"));
                if ("send_file".equals(forWhat) || "file_download".equals(forWhat)) {
                    pendingDownloads.clear();
                    pendingFileNames.clear();
                    downloadTimers.values().forEach(javax.swing.Timer::stop);
                    downloadTimers.clear();
                    javax.swing.JOptionPane.showMessageDialog(this,
                            "文件下载失败：" + (msgText == null ? "服务器无响应" : msgText)
                            + "\n\n请重新点击文件卡片再试一次");
                } else if ("change_password".equals(forWhat)) {
                    onChangePasswordResult(false, msgText);
                }
                break;
            }
            case "presence": {
                String uid = Json.str(evt.get("uid"));
                boolean deleted = Boolean.TRUE.equals(evt.get("deleted"));
                @SuppressWarnings("unchecked")
                Map<String, Object> user = Json.obj(evt.get("user"));
                if (deleted || (user == null && uid != null && users.containsKey(uid))) {
                    users.remove(uid);
                    Contact c = findContact("u:" + uid);
                    if (c != null) contactModel.removeElement(c);
                    if (active != null && active.key.equals("u:" + uid)) {
                        chatTitle.setText(" 该用户已被管理员删除");
                        clearChat();
                        appendSystem("该用户已被管理员删除");
                        active = null;
                    }
                    contactList.repaint();
                    return;
                }
                if (user == null) return;
                users.put(uid, user);
                if (uid.equals(Json.str(me.get("uid")))) {
                    me.put("nickname", user.get("nickname"));
                    me.put("avatar", user.get("avatar"));
                    setTitle("佳佳聊天 - " + Json.str(me.get("realName")));
                }
                Contact c = findContact("u:" + uid);
                if (c == null && !uid.equals(Json.str(me.get("uid")))) {
                    refreshContacts();
                } else if (c != null) {
                    c.title = Json.str(user.get("nickname"));
                    c.avatar = Json.str(user.get("avatar"));
                    if (active == c) {
                        chatTitle.setText(" " + c.avatar + "  " + c.title + onlineSuffix(c));
                    }
                }
                contactList.repaint();
                break;
            }
            case "force_logout": {
                javax.swing.JOptionPane.showMessageDialog(this, "账号已在其他窗口登录");
                backToLogin();
                break;
            }
            default:
        }
    }

    private Contact contactOfConvId(String convId) {
        if (convId == null) return null;
        if (convId.startsWith("grp:")) return findContact(convId);
        if (convId.startsWith("pri:")) {
            String[] parts = convId.substring(4).split("_");
            String myUid = Json.str(me.get("uid"));
            for (String p : parts) {
                if (!p.equals(myUid)) return findContact("u:" + p);
            }
        }
        return null;
    }

    private void backToLogin() {
        client.close();
        dispose();
        new LoginFrame().setVisible(true);
    }

    @Override
    public void onDown() {
        SwingUtilities.invokeLater(() -> {
            javax.swing.JOptionPane.showMessageDialog(this, "与服务器的连接已断开");
            backToLogin();
        });
    }

    // ================= 联系人渲染 =================

    private class ContactRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            Contact c = (Contact) value;
            String status = "";
            if (!c.isGroup) {
                Map<String, Object> u = users.get(c.key.substring(2));
                status = u != null && Boolean.TRUE.equals(u.get("online")) ? "  ●" : "";
            }
            String unread = c.unread > 0 ? "  🔴" + c.unread : "";
            l.setText(c.avatar + "  " + c.title + status + unread);
            // super 调用后字体会被重置，这里重新指定（Dialog 同时支持中文与 emoji）
            l.setFont(new Font("Dialog", Font.PLAIN, 14));
            l.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 6));
            l.setIconTextGap(8);
            if (selected) {
                l.setBackground(GREEN);
                l.setForeground(Color.WHITE);
            } else {
                l.setBackground(Color.WHITE);
                l.setForeground(new Color(0x33, 0x33, 0x33));
            }
            return l;
        }
    }
}
