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
    return { users: d.users || [], groups: d.groups, messages: d.messages || {} };
  } catch {
    return {
      users: [],
      groups: [{ gid: 'default', name: '大家的群', owner: null, members: [], createdAt: Date.now() }],
      messages: {}
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

  // 登录 / 注册（昵称唯一，首次登录自动注册并分配 QQ 风格号码）
  socket.on('login', (payload = {}, ack) => {
    const nickname = String(payload.nickname || '').trim().slice(0, 12);
    const avatar = String(payload.avatar || '😀').slice(0, 8);
    if (!nickname) return ack && ack({ ok: false, error: '请输入昵称' });

    let user = db.users.find(u => u.nickname === nickname);
    let isNew = false;
    if (user) {
      // 同一账号在别处（或旧连接重连）登录：踢掉旧连接，由新连接接管
      const oldSid = online.get(user.uid);
      if (oldSid && oldSid !== socket.id) {
        const oldSocket = io.sockets.sockets.get(oldSid);
        if (oldSocket) {
          oldSocket.emit('force_logout');
          oldSocket.disconnect(true);
        }
      }
      user.avatar = avatar;
    } else {
      let uid;
      do { uid = genUid(); } while (db.users.some(u => u.uid === uid));
      user = { uid, nickname, avatar, createdAt: Date.now() };
      db.users.push(user);
      defaultGroup().members.push(uid);
      pushMessage('grp:default', systemMessage(`欢迎 “${nickname}” 加入大家的群，快去打个招呼吧 ~`));
      isNew = true;
    }

    uid = user.uid;
    socket.data.uid = uid;
    online.set(uid, socket.id);
    socket.join('u:' + uid);
    db.groups.forEach(g => {
      if (g.members.includes(uid)) socket.join('g:' + g.gid);
    });
    saveDB();

    // 组装登录返回数据
    const myGroups = db.groups.filter(g => g.members.includes(uid));
    const lastMessages = {};
    for (const [convId, list] of Object.entries(db.messages)) {
      if (convId === 'grp:default' || (convId.startsWith('grp:') && myGroups.some(g => 'g:' + g.gid === 'g:' + convId.slice(4)))
        || (convId.startsWith('pri:') && convId.slice(4).split('_').includes(uid))) {
        if (list.length) lastMessages[convId] = list[list.length - 1];
      }
    }

    socket.broadcast.emit('presence', { uid, online: true, user: isNew ? pubUser(user) : null });
    ack && ack({
      ok: true,
      me: user,
      users: db.users.map(pubUser),
      groups: myGroups.map(pubGroup),
      lastMessages
    });
    console.log(`[登录] ${nickname} (${uid})，当前在线 ${online.size} 人`);
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
