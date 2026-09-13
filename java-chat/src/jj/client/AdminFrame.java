package jj.client;

import jj.common.Json;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台窗口：用户全部信息展示 + 修改密码 / 修改资料 / 删除
 */
public class AdminFrame extends JFrame implements ChatClient.Listener {

    private static final Color GREEN = new Color(0x07, 0xC1, 0x60);

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    // 登录
    private final JTextField admUser = new JTextField(14);
    private final JPasswordField admPass = new JPasswordField(14);
    private final JLabel admErr = new JLabel(" ");

    // 主界面
    private final DefaultTableModel model;
    private final JTable table;
    private final JLabel statTotal = new JLabel("总用户 0");
    private final JLabel statOnline = new JLabel("在线 0");
    private final JLabel opsErr = new JLabel(" ");

    private ChatClient client;
    private String token;
    private List<Map<String, Object>> users = new ArrayList<>();
    private final Timer pollTimer;

    private static final String[] COLS = {
            "头像", "账号", "实际名字", "性别", "年龄", "手机号", "密码", "状态", "佳佳号", "注册时间", "最后登录"
    };

    public AdminFrame(JFrame parent) {
        setTitle("佳佳聊天 · 管理后台");
        setSize(1180, 640);
        setLocationRelativeTo(parent);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        setContentPane(cardPanel);

        // ---------- 登录卡片 ----------
        JPanel login = new JPanel();
        login.setLayout(new BoxLayout(login, BoxLayout.Y_AXIS));
        login.setBackground(Color.WHITE);
        login.setBorder(BorderFactory.createEmptyBorder(60, 40, 40, 40));

        JLabel logo = new JLabel("🛡️", javax.swing.SwingConstants.CENTER);
        logo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 42));
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel title = new JLabel("管理后台");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 22));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sub = new JLabel("佳佳聊天用户管理");
        sub.setForeground(Color.GRAY);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        admErr.setForeground(new Color(0xd9, 0x30, 0x30));
        admErr.setAlignmentX(Component.CENTER_ALIGNMENT);
        admUser.setMaximumSize(new Dimension(280, 34));
        admPass.setMaximumSize(new Dimension(280, 34));
        JButton loginBtn = new JButton("登 录");
        loginBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        loginBtn.setBackground(GREEN);
        loginBtn.setForeground(Color.WHITE);
        loginBtn.setFocusPainted(false);
        loginBtn.setBorderPainted(false);
        loginBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        loginBtn.setMaximumSize(new Dimension(280, 40));
        loginBtn.addActionListener(e -> doAdminLogin());
        admPass.addActionListener(e -> doAdminLogin());
        admUser.addActionListener(e -> admPass.requestFocusInWindow());

        login.add(logo);
        login.add(Box.createVerticalStrut(6));
        login.add(title);
        login.add(Box.createVerticalStrut(2));
        login.add(sub);
        login.add(Box.createVerticalStrut(24));
        login.add(admUser);
        login.add(Box.createVerticalStrut(10));
        login.add(admPass);
        login.add(Box.createVerticalStrut(16));
        login.add(loginBtn);
        login.add(Box.createVerticalStrut(8));
        login.add(admErr);

        // ---------- 主界面卡片 ----------
        model = new DefaultTableModel(COLS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(model);
        table.setRowHeight(34);
        table.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        table.getTableHeader().setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(true);
        table.setGridColor(new Color(0xed, 0xed, 0xed));
        // 所有列按比例均匀填满视口，从根本上避免横向滚动条
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        // 每列推荐宽度（作为分配比例）+ 最小宽度（防止被压得太窄）
        // 顺序：头像/账号/实际名字/性别/年龄/手机号/密码/状态/佳佳号/注册时间/最后登录
        int[] colWidths   = {50, 110, 105, 60, 55, 120, 105, 70, 80, 110, 110};
        int[] colMinWidths={40,  80,  80, 45, 40,  95,  80, 55, 65,  90,  90};
        for (int i = 0; i < colWidths.length; i++) {
            javax.swing.table.TableColumn col = table.getColumnModel().getColumn(i);
            col.setPreferredWidth(colWidths[i]);
            col.setMinWidth(colMinWidths[i]);
        }
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        int[] centerCols = {0, 3, 4, 5, 7, 8, 9, 10};
        for (int c : centerCols) table.getColumnModel().getColumn(c).setCellRenderer(center);
        // 头像列：必须在 super 渲染后设置字体，否则会被表格默认字体重置导致 emoji 变方框
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            { setHorizontalAlignment(javax.swing.SwingConstants.CENTER); }
            @Override public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                comp.setFont(new Font("Dialog", Font.PLAIN, 17));
                return comp;
            }
        });
        table.getColumnModel().getColumn(6).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                comp.setForeground(new Color(0x8a, 0x6d, 0x3b)); // 密码淡显示
                return comp;
            }
        });

        statTotal.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        statOnline.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        statOnline.setForeground(GREEN);

        JButton pwdBtn = smallBtn("修改密码");
        pwdBtn.addActionListener(e -> openPwdDialog());
        JButton infoBtn = smallBtn("修改信息");
        infoBtn.addActionListener(e -> openInfoDialog());
        JButton delBtn = smallBtn("删除用户");
        delBtn.addActionListener(e -> openDeleteDialog());
        JButton refreshBtn = smallBtn("刷新");
        refreshBtn.addActionListener(e -> requestUsers());
        opsErr.setForeground(new Color(0xd9, 0x30, 0x30));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 10));
        top.setBackground(new Color(0xf7, 0xf7, 0xf7));
        top.add(statTotal);
        top.add(statOnline);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        bottom.setBackground(Color.WHITE);
        bottom.add(pwdBtn);
        bottom.add(infoBtn);
        bottom.add(delBtn);
        bottom.add(refreshBtn);
        bottom.add(opsErr);

        JPanel main = new JPanel(new BorderLayout());
        main.add(top, BorderLayout.NORTH);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        tableScroll.getHorizontalScrollBar().setUnitIncrement(20);
        main.add(tableScroll, BorderLayout.CENTER);
        main.add(bottom, BorderLayout.SOUTH);

        cardPanel.add(login, "login");
        cardPanel.add(main, "main");
        cards.show(cardPanel, "login");

        pollTimer = new Timer(5000, e -> { if (token != null) requestUsers(); });

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (pollTimer != null) pollTimer.stop();
                if (client != null) client.close();
            }
        });
    }

    private JButton smallBtn(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        b.setFocusPainted(false);
        return b;
    }

    // ================= 动作 =================

    private void doAdminLogin() {
        String username = admUser.getText().trim();
        String password = new String(admPass.getPassword());
        if (username.isEmpty() || password.isEmpty()) {
            admErr.setText("请输入账号和密码");
            return;
        }
        admErr.setText("连接中...");
        try {
            client = new ChatClient(ChatApp.HOST, ChatApp.PORT, this);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", "admin_login");
            m.put("username", username);
            m.put("password", password);
            client.send(m);
        } catch (Exception e) {
            admErr.setText("无法连接服务器，请先启动服务端");
        }
    }

    private void requestUsers() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "admin_users");
        m.put("token", token);
        client.send(m);
    }

    private Map<String, Object> selectedUser() {
        int row = table.getSelectedRow();
        if (row < 0) {
            opsErr.setText("请先在表格中选择一个用户");
            return null;
        }
        opsErr.setText(" ");
        return users.get(row);
    }

    private void openPwdDialog() {
        Map<String, Object> u = selectedUser();
        if (u == null) return;
        JPasswordField pf = new JPasswordField(16);
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(new JLabel("为「" + Json.str(u.get("realName")) + "」（佳佳号 " + Json.str(u.get("uid"))
                + "）设置新密码："), BorderLayout.NORTH);
        p.add(pf, BorderLayout.CENTER);
        int ok = JOptionPane.showConfirmDialog(this, p, "修改密码",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        String pwd = new String(pf.getPassword());
        if (pwd.length() < 6 || pwd.length() > 20) {
            opsErr.setText("密码需为 6-20 位");
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "admin_set_password");
        m.put("token", token);
        m.put("uid", Json.str(u.get("uid")));
        m.put("password", pwd);
        client.send(m);
    }

    private void openInfoDialog() {
        Map<String, Object> u = selectedUser();
        if (u == null) return;

        JDialog dlg = new JDialog(this, "修改信息 - " + Json.str(u.get("realName")), true);
        dlg.setSize(380, 470);
        dlg.setLocationRelativeTo(this);

        JTextField fUser = new JTextField(Json.str(u.get("username")), 14);
        JTextField fReal = new JTextField(Json.str(u.get("realName")), 14);
        JComboBox<String> fGender = new JComboBox<>(new String[]{"保密", "男", "女"});
        fGender.setSelectedItem("男".equals(Json.str(u.get("gender"))) || "女".equals(Json.str(u.get("gender")))
                ? Json.str(u.get("gender")) : "保密");
        Object ageObj = u.get("age");
        JTextField fAge = new JTextField(ageObj == null ? "" : String.valueOf(ageObj), 14);
        JTextField fPhone = new JTextField(Json.str(u.get("phone")), 14);
        JLabel err = new JLabel(" ");
        err.setForeground(new Color(0xd9, 0x30, 0x30));

        final String[] picked = {Json.str(u.get("avatar"))};
        JPanel avatarPanel = new JPanel(new GridLayout(2, 8, 4, 4));
        List<JButton> btns = new ArrayList<>();
        for (String av : ChatApp.AVATARS) {
            JButton b = new JButton(av);
            b.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
            b.setContentAreaFilled(false);
            b.setFocusPainted(false);
            b.setBorder(av.equals(picked[0]) ? BorderFactory.createLineBorder(GREEN, 2, true)
                                             : BorderFactory.createEmptyBorder(2, 2, 2, 2));
            b.addActionListener(e -> {
                picked[0] = av;
                for (JButton x : btns) x.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
                b.setBorder(BorderFactory.createLineBorder(GREEN, 2, true));
            });
            btns.add(b);
            avatarPanel.add(b);
        }

        JButton save = new JButton("保 存");
        save.setBackground(GREEN);
        save.setForeground(Color.WHITE);
        save.setFocusPainted(false);
        save.setBorderPainted(false);
        save.addActionListener(e -> {
            String username = fUser.getText().trim();
            String realName = fReal.getText().trim();
            String age = fAge.getText().trim();
            String phone = fPhone.getText().trim();
            if (username.isEmpty() || realName.isEmpty()) { err.setText("账号和实际名字必填"); return; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", "admin_set_profile");
            m.put("token", token);
            m.put("uid", Json.str(u.get("uid")));
            m.put("username", username);
            m.put("realName", realName);
            m.put("gender", String.valueOf(fGender.getSelectedItem()));
            m.put("age", age.isEmpty() ? null : age);
            m.put("phone", phone);
            m.put("avatar", picked[0]);
            client.send(m);
            dlg.dispose();
        });

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        form.add(avatarPanel);
        form.add(Box.createVerticalStrut(10));
        form.add(row("账　号", fUser));
        form.add(Box.createVerticalStrut(6));
        form.add(row("实际名字", fReal));
        form.add(Box.createVerticalStrut(6));
        form.add(row("性　别", fGender));
        form.add(Box.createVerticalStrut(6));
        form.add(row("年　龄", fAge));
        form.add(Box.createVerticalStrut(6));
        form.add(row("手机号", fPhone));
        form.add(Box.createVerticalStrut(6));
        err.setAlignmentX(Component.CENTER_ALIGNMENT);
        form.add(err);
        save.setAlignmentX(Component.CENTER_ALIGNMENT);
        form.add(Box.createVerticalStrut(6));
        form.add(save);
        dlg.setContentPane(form);
        dlg.setVisible(true);
    }

    private JPanel row(String label, JComponent field) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(64, 26));
        field.setPreferredSize(new Dimension(240, 30));
        r.add(l);
        r.add(field);
        return r;
    }

    private void openDeleteDialog() {
        Map<String, Object> u = selectedUser();
        if (u == null) return;
        int ok = JOptionPane.showConfirmDialog(this,
                "确定删除「" + Json.str(u.get("realName")) + "」（佳佳号 " + Json.str(u.get("uid")) + "）吗？\n"
                        + "删除后将立即被踢下线且无法登录，此操作不可恢复！",
                "删除用户", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "admin_delete");
        m.put("token", token);
        m.put("uid", Json.str(u.get("uid")));
        client.send(m);
    }

    // ================= 事件 =================

    @Override
    public void onEvent(Map<String, Object> evt) {
        String event = Json.str(evt.get("event"));
        SwingUtilities.invokeLater(() -> {
            switch (String.valueOf(event)) {
                case "admin_ok":
                    token = Json.str(evt.get("token"));
                    admErr.setText(" ");
                    cards.show(cardPanel, "main");
                    requestUsers();
                    pollTimer.start();
                    break;
                case "admin_users":
                    users.clear();
                    List<Object> list = Json.arr(evt.get("users"));
                    int on = 0;
                    if (list != null) {
                        for (Object o : list) {
                            Map<String, Object> u = Json.obj(o);
                            if (u == null) continue;
                            users.add(u);
                            if (Boolean.TRUE.equals(u.get("online"))) on++;
                        }
                    }
                    fillTable();
                    statTotal.setText("总用户 " + users.size());
                    statOnline.setText("在线 " + on);
                    break;
                case "admin_saved":
                    opsErr.setText(" ");
                    requestUsers();
                    break;
                case "error": {
                    String forWhat = Json.str(evt.get("for"));
                    String msg = Json.str(evt.get("message"));
                    switch (String.valueOf(forWhat)) {
                        case "admin_login": admErr.setText(msg); break;
                        case "admin": // 令牌过期
                            pollTimer.stop();
                            token = null;
                            cards.show(cardPanel, "login");
                            admErr.setText(msg);
                            break;
                        default: opsErr.setText(msg);
                    }
                    break;
                }
                default:
            }
        });
    }

    private void fillTable() {
        SimpleDateFormat tf = new SimpleDateFormat("MM-dd HH:mm");
        model.setRowCount(0);
        for (Map<String, Object> u : users) {
            model.addRow(new Object[]{
                    Json.str(u.get("avatar")),
                    Json.str(u.get("username")),
                    Json.str(u.get("realName")),
                    Json.str(u.get("gender")),
                    u.get("age") == null ? "—" : String.valueOf(u.get("age")),
                    String.valueOf(u.get("phone")).isEmpty() ? "—" : Json.str(u.get("phone")),
                    Json.str(u.get("password")),
                    Boolean.TRUE.equals(u.get("online")) ? "● 在线" : "○ 离线",
                    Json.str(u.get("uid")),
                    fmtTime(u.get("createdAt"), tf),
                    fmtTime(u.get("lastLoginAt"), tf)
            });
        }
    }

    private String fmtTime(Object ts, SimpleDateFormat tf) {
        if (ts instanceof Number) {
            return tf.format(new java.util.Date(((Number) ts).longValue()));
        }
        return "—";
    }

    @Override
    public void onDown() {
        SwingUtilities.invokeLater(() -> {
            pollTimer.stop();
            token = null;
            cards.show(cardPanel, "login");
            admErr.setText("与服务器的连接已断开");
        });
    }
}
