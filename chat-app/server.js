/**
 * 佳佳聊天 - 服务端
 * Express 提供静态页面，Socket.IO 负责实时消息
 * 数据以 JSON 文件持久化（演示版轻量存储）
 */
const express = require('express');
const http = require('http');
const path = require('path');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');
const { Server } = require('socket.io');

const PORT = process.env.PORT || 3000;
const DATA_DIR = path.join(__dirname, 'data');
const UPLOAD_DIR = path.join(__dirname, 'uploads');
const DB_FILE = path.join(DATA_DIR, 'db.json');
const MAX_MSGS_PER_CONV = 300;   // 每个会话最多保存的消息条数
const TEXT_LIMIT = 2000;          // 单条文本最大长度
const IMAGE_LIMIT = 4 * 1024 * 1024; // 图片 base64 最大长度

const app = express();
const server = http.createServer(app);
const io = new Server(server, { maxHttpBufferSize: 6 * 1024 * 1024 });

app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));
app.use('/uploads', express.static(UPLOAD_DIR));

// ---------------- 数据层 ----------------

let db = loadDB();
const online = new Map(); // uid -> socket.id

function loadDB() {
  try {
    const raw = fs.readFileSync(DB_FILE, 'utf8');
    const d = JSON.parse(raw);
    if (!d.groups || !d.groups.some(g => g.gid === 'default')) {
      d.groups = d.groups || [];
      d.groups.unshift({ gid: 'default', name: '大家的群', owner: null, members: [], createdAt: Date.now() });
    }
    return { users: d.users || [], groups: d.groups, messages: d.messages || {}, admin: d.admin || null };
  } catch {
    return {
      users: [],
      groups: [{ gid: 'default', name: '大家的群', owner: null, members: [], createdAt: Date.now() }],
      messages: {},
      admin: null
    };
  }
}

let saveTimer = null;
function saveDB() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFile(DB_FILE, JSON.stringify(db), err => {
      if (err) console.error('保存数据失败:', err.message);
    });
  }, 200);
}

const genUid = () => String(Math.floor(100000 + Math.random() * 900000));
const genId = () => crypto.randomBytes(8).toString('hex');
const defaultGroup = () => db.groups.find(g => g.gid === 'default');

// ---------------- 认证辅助 ----------------

// 密码哈希（scrypt + 每用户随机盐）。另外保留 password 原文仅供管理员后台查看（演示版），
// 登录校验始终走哈希；如需公开部署，删除 u.password 字段并去掉后台密码列即可。
const scrypt = (pwd, salt) => crypto.scryptSync(String(pwd), String(salt), 32).toString('hex');

const userTokens = new Map();   // 会话令牌 token -> uid（断线重连用，内存态）
const adminTokens = new Map();  // 管理员令牌 token -> 过期时间
const newToken = () => crypto.randomBytes(24).toString('hex');
const ADMIN_TOKEN_TTL = 12 * 60 * 60 * 1000; // 管理员登录有效期 12 小时

// 管理员账号：首次启动自动创建，可在 data/db.json 中修改
if (!db.admin) {
  db.admin = { username: 'admin', password: 'admin123' };
  saveDB();
}

// 旧版昵称用户迁移：补齐 username / 密码字段（旧账号无密码，需管理员重置后才能登录）
db.users.forEach(u => {
  if (!u.username) {
    let name = u.nickname || ('user' + u.uid);
    let i = 1;
    while (db.users.some(x => x !== u && (x.username || '').toLowerCase() === name.toLowerCase())) {
      name = (u.nickname || 'user' + u.uid) + '_' + (i++);
    }
    u.username = name;
    u.realName = u.realName || u.nickname;
    u.password = u.password || '';
    u.passwordHash = u.passwordHash || null;
    u.salt = u.salt || null;
    u.lastLoginAt = u.lastLoginAt || null;
  }
});

function pubMe(u) {
  return {
    uid: u.uid, username: u.username, realName: u.realName || u.nickname,
    nickname: u.nickname, avatar: u.avatar, createdAt: u.createdAt
  };
}

function pushMessage(convId, msg) {
  const list = db.messages[convId] || (db.messages[convId] = []);
  list.push(msg);
  if (list.length > MAX_MSGS_PER_CONV) list.splice(0, list.length - MAX_MSGS_PER_CONV);
  saveDB();
}

function systemMessage(content) {
  return { id: genId(), from: null, type: 'system', content, time: Date.now() };
}

function pubUser(u) {
  return { uid: u.uid, nickname: u.nickname, avatar: u.avatar, online: online.has(u.uid) };
}

function pubGroup(g) {
  return { gid: g.gid, name: g.name, owner: g.owner, members: g.members, createdAt: g.createdAt };
}

function privateConvId(a, b) {
  return 'pri:' + [a, b].sort().join('_');
}

// 校验会话访问权限，返回房间名或 null
function resolveConversation(uid, convId) {
  if (convId.startsWith('grp:')) {
    const gid = convId.slice(4);
    const g = db.groups.find(x => x.gid === gid);
    if (g && g.members.includes(uid)) return { room: 'g:' + g.gid, kind: 'grp' };
  } else if (convId.startsWith('pri:')) {
    const parts = convId.slice(4).split('_');
    if (parts.length === 2 && parts.includes(uid)) {
      const otherUid = parts.find(x => x !== uid);
      const otherUser = db.users.find(u => u.uid === otherUid);
      if (otherUser) {
        return { room: 'u:' + uid, kind: 'pri', other: otherUid, rooms: ['u:' + uid, 'u:' + otherUid] };
      }
    }
  }
  return null;
}

// ---------------- Socket 事件 ----------------

io.on('connection', socket => {
  let uid = null;

  // 登录：支持「账号 + 密码」或「会话令牌」（断线重连 / 自动登录）
  socket.on('login', (payload = {}, ack) => {
    let user = null;
    const token = String(payload.token || '');

    if (token && userTokens.get(token)) {
      user = db.users.find(u => u.uid === userTokens.get(token));
      if (!user) userTokens.delete(token);
    }

    if (!user) {
      const username = String(payload.username || '').trim();
      const password = String(payload.password || '');
      if (!username) return ack && ack({ ok: false, error: '请输入账号' });
      user = db.users.find(u => (u.username || '').toLowerCase() === username.toLowerCase());
      if (!user) return ack && ack({ ok: false, error: '账号不存在，请先注册' });
      if (!user.passwordHash) return ack && ack({ ok: false, error: '该账号尚未设置密码，请联系管理员重置' });
      if (scrypt(password, user.salt) !== user.passwordHash) {
        return ack && ack({ ok: false, error: '账号或密码错误' });
      }
    }

    // 同一账号在别处（或旧连接重连）登录：踢掉旧连接，由新连接接管
    const oldSid = online.get(user.uid);
    if (oldSid && oldSid !== socket.id) {
      const oldSocket = io.sockets.sockets.get(oldSid);
      if (oldSocket) {
        oldSocket.emit('force_logout');
        oldSocket.disconnect(true);
      }
    }

    uid = user.uid;
    user.lastLoginAt = Date.now();
    socket.data.uid = uid;
    online.set(uid, socket.id);
    socket.join('u:' + uid);
    db.groups.forEach(g => {
      if (g.members.includes(uid)) socket.join('g:' + g.gid);
    });
    saveDB();

    // 签发会话令牌，客户端保存后可用于自动重连
    const myToken = newToken();
    userTokens.set(myToken, uid);

    // 组装登录返回数据
    const myGroups = db.groups.filter(g => g.members.includes(uid));
    const lastMessages = {};
    for (const [convId, list] of Object.entries(db.messages)) {
      if (convId === 'grp:default' || (convId.startsWith('grp:') && myGroups.some(g => 'g:' + g.gid === 'g:' + convId.slice(4)))
        || (convId.startsWith('pri:') && convId.slice(4).split('_').includes(uid))) {
        if (list.length) lastMessages[convId] = list[list.length - 1];
      }
    }

    socket.broadcast.emit('presence', { uid, online: true, user: null });
    ack && ack({
      ok: true,
      token: myToken,
      me: pubMe(user),
      users: db.users.map(pubUser),
      groups: myGroups.map(pubGroup),
      lastMessages
    });
    console.log(`[登录] ${user.nickname} (${user.username}/${uid})，当前在线 ${online.size} 人`);
  });

  // 拉取某个会话的历史消息
  socket.on('history', (convId, ack) => {
    if (!uid || typeof convId !== 'string') return ack && ack({ ok: false });
    const conv = resolveConversation(uid, convId);
    if (!conv) return ack && ack({ ok: false });
    ack({ ok: true, messages: db.messages[convId] || [] });
  });

  // 发送消息（私聊 / 群聊统一入口）
  socket.on('message', (data = {}, ack) => {
    if (!uid) return;
    const { convId, type, content } = data;
    const conv = resolveConversation(uid, convId);
    if (!conv) return ack && ack({ ok: false, error: '会话不存在' });

    if (type === 'text') {
      const text = String(content || '').trim();
      if (!text) return ack && ack({ ok: false });
      if (text.length > TEXT_LIMIT) return ack && ack({ ok: false, error: '消息太长啦' });
      return deliver(convId, conv, { type, content: text });
    }
    if (type === 'image') {
      const img = String(content || '');
      const mm = img.match(/^data:image\/(png|jpe?g|gif|webp);base64,(.+)$/i);
      if (!mm || img.length > IMAGE_LIMIT) {
        return ack && ack({ ok: false, error: '图片格式不支持或超过 3MB' });
      }
      // 图片写入磁盘，消息中只保存 URL，避免数据库文件膨胀
      const ext = mm[1].toLowerCase().replace('jpeg', 'jpg');
      const filename = genId() + '.' + ext;
      fs.mkdirSync(UPLOAD_DIR, { recursive: true });
      fs.writeFile(path.join(UPLOAD_DIR, filename), Buffer.from(mm[2], 'base64'), err => {
        if (err) return ack && ack({ ok: false, error: '图片保存失败' });
        deliver(convId, conv, { type, content: '/uploads/' + filename });
      });
      return;
    }
    ack && ack({ ok: false, error: '不支持的消息类型' });

    function deliver(cid, c, m) {
      const msg = Object.assign({ id: genId(), from: uid, time: Date.now() }, m);
      pushMessage(cid, msg);
      const rooms = c.kind === 'grp' ? ['g:' + cid.slice(4)] : c.rooms;
      rooms.forEach(r => io.to(r).emit('message', { convId: cid, message: msg }));
      ack && ack({ ok: true, msgId: msg.id, time: msg.time });
    }
  });

  // 正在输入
  socket.on('typing', convId => {
    if (!uid || typeof convId !== 'string') return;
    const conv = resolveConversation(uid, convId);
    if (!conv) return;
    const target = conv.kind === 'grp' ? 'g:' + convId.slice(4) : 'u:' + conv.other;
    io.to(target).emit('typing', { convId, uid });
  });

  // 创建群聊
  socket.on('group:create', (payload = {}, ack) => {
    if (!uid) return;
    const name = String(payload.name || '').trim().slice(0, 12) || '群聊';
    const members = [...new Set([uid, ...(Array.isArray(payload.members) ? payload.members : [])])]
      .filter(x => db.users.some(u => u.uid === x));
    if (members.length < 2) return ack && ack({ ok: false, error: '至少再选择一位成员' });

    const gid = crypto.randomBytes(5).toString('hex');
    const group = { gid, name, owner: uid, members, createdAt: Date.now() };
    db.groups.push(group);
    pushMessage('grp:' + gid, systemMessage('群聊已创建，开始聊天吧'));
    saveDB();

    members.forEach(m => {
      const sid = online.get(m);
      if (sid) io.sockets.sockets.get(sid)?.join('g:' + gid);
    });
    io.to(members.map(m => 'u:' + m)).emit('group:new', pubGroup(group));
    ack && ack({ ok: true, group: pubGroup(group) });
  });

  socket.on('disconnect', () => {
    if (uid && online.get(uid) === socket.id) {
      online.delete(uid);
      socket.broadcast.emit('presence', { uid, online: false, user: null });
      console.log(`[离线] ${uid}，当前在线 ${online.size} 人`);
    }
  });
});

// ---------------- HTTP API：注册 ----------------

// 新用户自助注册
app.post('/api/register', (req, res) => {
  const username = String((req.body || {}).username || '').trim();
  const realName = String((req.body || {}).realName || '').trim();
  const password = String((req.body || {}).password || '');
  const avatar = String((req.body || {}).avatar || '😀').slice(0, 8);

  if (!/^[\w\u4e00-\u9fa5]{2,20}$/.test(username)) {
    return res.json({ ok: false, error: '账号需为 2-20 位字母、数字、下划线或中文' });
  }
  if (!realName || realName.length > 12) {
    return res.json({ ok: false, error: '请输入实际名字（12 字以内）' });
  }
  if (password.length < 6 || password.length > 20) {
    return res.json({ ok: false, error: '密码需为 6-20 位' });
  }
  if (db.users.some(u => (u.username || '').toLowerCase() === username.toLowerCase())) {
    return res.json({ ok: false, error: '该账号已被注册' });
  }

  let uid;
  do { uid = genUid(); } while (db.users.some(u => u.uid === uid));
  const salt = crypto.randomBytes(8).toString('hex');
  const user = {
    uid, username, nickname: realName, realName, avatar,
    password, passwordHash: scrypt(password, salt), salt,
    createdAt: Date.now(), lastLoginAt: null
  };
  db.users.push(user);
  defaultGroup().members.push(uid);
  pushMessage('grp:default', systemMessage(`欢迎 “${realName}” 加入大家的群，快去打个招呼吧 ~`));
  saveDB();

  // 推送给所有在线客户端，通讯录实时出现新用户
  io.emit('presence', { uid, online: false, user: pubUser(user) });
  console.log(`[注册] ${realName} (账号:${username} / 佳佳号:${uid})`);
  res.json({ ok: true });
});

// ---------------- HTTP API：管理后台 ----------------

function requireAdmin(req, res) {
  const h = req.headers.authorization || '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : '';
  const exp = adminTokens.get(token);
  if (!exp || exp < Date.now()) {
    if (exp) adminTokens.delete(token);
    res.status(401).json({ ok: false, error: '登录已过期，请重新登录' });
    return false;
  }
  return true;
}

// 管理员登录
app.post('/api/admin/login', (req, res) => {
  const { username, password } = req.body || {};
  // 清理过期令牌
  const now = Date.now();
  for (const [t, exp] of adminTokens) if (exp < now) adminTokens.delete(t);

  if (String(username || '') === db.admin.username && String(password || '') === db.admin.password) {
    const token = newToken();
    adminTokens.set(token, now + ADMIN_TOKEN_TTL);
    return res.json({ ok: true, token });
  }
  res.json({ ok: false, error: '管理员账号或密码错误' });
});

// 用户列表（含在线状态、密码明文仅供管理员查看）
app.get('/api/admin/users', (req, res) => {
  if (!requireAdmin(req, res)) return;
  res.json({
    ok: true,
    users: db.users.map(u => ({
      uid: u.uid,
      username: u.username,
      realName: u.realName || u.nickname,
      avatar: u.avatar,
      password: u.password || '',
      online: online.has(u.uid),
      createdAt: u.createdAt,
      lastLoginAt: u.lastLoginAt
    }))
  });
});

// 重置用户密码
app.post('/api/admin/users/:uid/password', (req, res) => {
  if (!requireAdmin(req, res)) return;
  const u = db.users.find(x => x.uid === req.params.uid);
  if (!u) return res.json({ ok: false, error: '用户不存在' });
  const pwd = String((req.body || {}).password || '');
  if (pwd.length < 6 || pwd.length > 20) {
    return res.json({ ok: false, error: '密码需为 6-20 位' });
  }
  u.password = pwd;
  u.salt = crypto.randomBytes(8).toString('hex');
  u.passwordHash = scrypt(pwd, u.salt);
  saveDB();
  console.log(`[后台] 管理员重置了 ${u.nickname}(${u.username}) 的密码`);
  res.json({ ok: true });
});

// 修改用户资料（账号 / 实际名字 / 头像）
app.post('/api/admin/users/:uid/profile', (req, res) => {
  if (!requireAdmin(req, res)) return;
  const u = db.users.find(x => x.uid === req.params.uid);
  if (!u) return res.json({ ok: false, error: '用户不存在' });

  const username = String((req.body || {}).username || '').trim();
  const realName = String((req.body || {}).realName || '').trim();
  const avatar = String((req.body || {}).avatar || u.avatar || '😀').slice(0, 8);

  if (!/^[\w\u4e00-\u9fa5]{2,20}$/.test(username)) {
    return res.json({ ok: false, error: '账号需为 2-20 位字母、数字、下划线或中文' });
  }
  if (!realName || realName.length > 12) {
    return res.json({ ok: false, error: '实际名字需为 1-12 字' });
  }
  if (db.users.some(x => x.uid !== u.uid && (x.username || '').toLowerCase() === username.toLowerCase())) {
    return res.json({ ok: false, error: '该账号已被其他用户使用' });
  }

  u.username = username;
  u.realName = realName;
  u.nickname = realName; // 聊天中显示的名字
  u.avatar = avatar;
  saveDB();

  // 同步给所有在线客户端，通讯录/聊天气泡实时更新
  io.emit('presence', { uid: u.uid, online: online.has(u.uid), user: pubUser(u) });
  console.log(`[后台] 管理员修改了 ${realName}(${username}) 的资料`);
  res.json({ ok: true });
});

// 删除用户（移出所有群、在线则踢下线，全员实时同步）
app.post('/api/admin/users/:uid/delete', (req, res) => {
  if (!requireAdmin(req, res)) return;
  const uid = req.params.uid;
  const u = db.users.find(x => x.uid === uid);
  if (!u) return res.json({ ok: false, error: '用户不存在' });

  db.users = db.users.filter(x => x.uid !== uid);
  db.groups.forEach(g => { g.members = g.members.filter(m => m !== uid); });
  for (const [t, tu] of userTokens) if (tu === uid) userTokens.delete(t);
  saveDB();

  const sid = online.get(uid);
  if (sid) {
    const s = io.sockets.sockets.get(sid);
    if (s) { s.emit('force_logout'); s.disconnect(true); }
  }
  online.delete(uid);
  io.emit('user:deleted', uid);
  console.log(`[后台] 管理员删除了用户 ${u.nickname}(${u.username}/${uid})`);
  res.json({ ok: true });
});

// 后台页面
app.get('/admin', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'admin.html'));
});

// ---------------- 启动 ----------------

server.listen(PORT, () => {
  const nets = os.networkInterfaces();
  const ips = [];
  for (const name of Object.keys(nets)) {
    for (const ni of nets[name]) {
      if (ni.family === 'IPv4' && !ni.internal) ips.push(ni.address);
    }
  }
  console.log('==============================================');
  console.log('  佳佳聊天服务已启动');
  console.log(`  本机访问:   http://localhost:${PORT}`);
  ips.forEach(ip => console.log(`  局域网访问: http://${ip}:${PORT}  (手机/其他电脑用)`));
  console.log('  按 Ctrl+C 停止服务');
  console.log('==============================================');
});
