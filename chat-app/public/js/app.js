/* ================= 佳佳聊天 前端逻辑 ================= */
'use strict';

const $ = sel => document.querySelector(sel);
const $$ = sel => Array.from(document.querySelectorAll(sel));

const AVATARS = ['😀', '🐱', '🦊', '🐼', '🐨', '🦁', '🐸', '🐵',
                 '🦄', '🐷', '🐧', '🐰', '🦉', '🐙', '🦋', '🌟'];
const EMOJIS = ['😀', '😄', '😂', '🤣', '😊', '😍', '😘', '😜',
                '🤔', '😎', '🤩', '🥳', '😴', '😭', '😡', '🥺',
                '😱', '👍', '👎', '👌', '🙏', '👏', '💪', '🤝',
                '❤️', '💔', '🔥', '✨', '🎉', '🎁', '🌹', '☕',
                '🍔', '🍉', '⚽', '🎮', '🚀', '💯', '❓', '❗'];
const AVATAR_COLORS = ['#fde2e2', '#fdeed3', '#e3f6d2', '#d6ecff',
                       '#e8ddff', '#d5f5ee', '#ffe0ee', '#fff3c4',
                       '#e0d9ff', '#cdeee5', '#fbe0d5', '#d3e6fb'];
const FIVE_MIN = 5 * 60 * 1000;

// ---------------- 全局状态 ----------------
const socket = io({ reconnection: true });
const state = {
  me: null,
  users: new Map(),       // uid -> user
  groups: new Map(),      // gid -> group
  convs: new Map(),       // convId -> conv
  activeId: null,
  view: 'chats',
  logging: false,
  auth: null              // { token, username, password }（password 仅存内存，用于断线静默重连）
};

// 已读游标按账号隔离（同一浏览器多开不同账号时互不干扰）
let cursors = {};
const cursorKey = () => 'jj_cursors_' + state.me.uid;
function loadCursors() {
  cursors = JSON.parse(localStorage.getItem(cursorKey()) || '{}');
}
const saveCursor = (id, t) => {
  cursors[id] = t;
  localStorage.setItem(cursorKey(), JSON.stringify(cursors));
};

// ---------------- 工具函数 ----------------
function colorOf(str) {
  let h = 0;
  for (const c of String(str)) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function avatarHTML(target, size = 'md') {
  const emoji = target.avatar || '👤';
  const key = target.uid || target.gid || emoji;
  const dot = target.online ? '<span class="online-dot"></span>' : '';
  return `<div class="avatar ${size}" style="background:${colorOf(key)}">${emoji}${dot}</div>`;
}
function esc(s) {
  return String(s).replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function pad(n) { return String(n).padStart(2, '0'); }
function fmtTime(ts, withDate = false) {
  const d = new Date(ts), now = new Date();
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  if (!withDate && d.toDateString() === now.toDateString()) return hm;
  const yest = new Date(now); yest.setDate(now.getDate() - 1);
  if (d.toDateString() === yest.toDateString()) return '昨天 ' + hm;
  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日`;
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`;
}
function convIdOf(uidA, uidB) {
  return 'pri:' + [uidA, uidB].sort().join('_');
}
let toastTimer = null;
function toast(msg) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), 2600);
}

// ---------------- 会话对象 ----------------
function convMeta(conv) {
  if (conv.kind === 'grp') {
    const g = state.groups.get(conv.gid);
    return { title: g ? g.name : '群聊', avatar: conv.gid === 'default' ? '🏠' : '👥', key: conv.gid };
  }
  const u = state.users.get(conv.peer);
  return { title: u ? u.nickname : '未知用户', avatar: u ? u.avatar : '👤', key: conv.peer };
}
function ensureGroupConv(gid) {
  const id = 'grp:' + gid;
  if (!state.convs.has(id)) {
    state.convs.set(id, { id, kind: 'grp', gid, messagesLoaded: false, messages: [], unread: 0, lastMsg: null });
  }
  return state.convs.get(id);
}
function ensurePrivateConv(peerUid) {
  const id = convIdOf(state.me.uid, peerUid);
  if (!state.convs.has(id)) {
    state.convs.set(id, { id, kind: 'pri', peer: peerUid, messagesLoaded: false, messages: [], unread: 0, lastMsg: null });
  }
  return state.convs.get(id);
}
function calcUnread(conv) {
  const cur = cursors[conv.id] || 0;
  return conv.messages.filter(m => m.from && m.from !== state.me.uid && m.time > cur).length;
}

// ---------------- 登录 / 注册 ----------------
let selectedAvatar = AVATARS[0];

function renderAvatarPicker() {
  $('#avatar-picker').innerHTML = AVATARS
    .map((a, i) => `<div class="pick${i === 0 ? ' selected' : ''}" role="button" data-emoji="${a}">${a}</div>`).join('');
  $('#avatar-picker').addEventListener('click', e => {
    const el = e.target.closest('.pick');
    if (!el) return;
    $$('.avatar-picker .pick').forEach(p => p.classList.remove('selected'));
    el.classList.add('selected');
    selectedAvatar = el.dataset.emoji;
  });
}

function setLoginBusy(busy) {
  $('#login-btn').disabled = busy;
  $('#login-btn').textContent = busy ? '登 录 中...' : '登 录';
}

function doLogin(username, password) {
  if (state.logging) return;
  if (!username || !password) { $('#login-error').textContent = '请输入账号和密码'; return; }
  state.logging = true;
  $('#login-error').textContent = '';
  setLoginBusy(true);
  socket.emit('login', { username, password }, res => {
    state.logging = false;
    setLoginBusy(false);
    if (!res || !res.ok) {
      $('#login-error').textContent = (res && res.error) || '登录失败，请重试';
      return;
    }
    // 令牌存 sessionStorage（仅当前标签页），密码只放内存用于断线静默重连
    state.auth = { token: res.token, username, password };
    sessionStorage.setItem('jj_auth', JSON.stringify({ token: res.token, username }));
    enterApp(res);
  });
}

// 注册：调用后端接口，成功后自动登录
async function doRegister() {
  const username = $('#reg-username').value.trim();
  const realName = $('#reg-realname').value.trim();
  const p1 = $('#reg-password').value;
  const p2 = $('#reg-password2').value;
  const err = $('#reg-error');
  err.textContent = '';
  if (!username || !realName || !p1 || !p2) { err.textContent = '请填写完整信息'; return; }
  if (p1 !== p2) { err.textContent = '两次输入的密码不一致'; return; }

  const btn = $('#register-btn');
  btn.disabled = true;
  btn.textContent = '注 册 中...';
  try {
    const r = await fetch('/api/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, realName, password: p1, avatar: selectedAvatar })
    });
    const d = await r.json();
    if (!d.ok) { err.textContent = d.error || '注册失败'; return; }
    toast('注册成功，自动登录中...');
    doLogin(username, p1);
  } catch {
    err.textContent = '网络错误，请重试';
  } finally {
    btn.disabled = false;
    btn.textContent = '注 册';
  }
}

// 登录 / 注册选项卡切换
function switchAuthTab(mode) {
  $$('.auth-tab').forEach(t => t.classList.toggle('active', t.dataset.mode === mode));
  $('#login-form').classList.toggle('hidden', mode !== 'login');
  $('#register-form').classList.toggle('hidden', mode !== 'register');
  $('#login-error').textContent = '';
  $('#reg-error').textContent = '';
}

function enterApp(data) {
  state.me = data.me;
  loadCursors();
  state.users = new Map(data.users.map(u => [u.uid, u]));
  state.groups = new Map(data.groups.map(g => [g.gid, g]));
  state.groups.forEach((_, gid) => {
    const conv = ensureGroupConv(gid);
    const last = data.lastMessages[conv.id];
    if (last) { conv.lastMsg = last; roughUnread(conv, last); }
  });
  Object.entries(data.lastMessages).forEach(([cid, msg]) => {
    if (cid.startsWith('pri:')) {
      const peer = cid.slice(4).split('_').find(u => u !== state.me.uid);
      if (!state.users.has(peer)) return;
      const conv = ensurePrivateConv(peer);
      conv.lastMsg = msg;
      roughUnread(conv, msg);
    }
  });

  $('#login-overlay').classList.add('hidden');
  $('#app').classList.remove('hidden');
  $('#nav-avatar').textContent = state.me.avatar;
  renderLists();
  updateTotalUnread();
}

// 无历史时用最后一条消息粗略估算未读
function roughUnread(conv, last) {
  const cur = cursors[conv.id] || 0;
  if (last && last.from && last.from !== state.me.uid && last.time > cur) conv.unread = 1;
}

// 连接建立：首次连接用 sessionStorage 令牌自动登录；断线重连用内存中的令牌/密码静默重新认证
let didAutoLogin = false;
socket.on('connect', () => {
  if (state.me && state.auth) {
    // 重连：先试令牌，失败则用内存中的账号密码重新登录
    socket.emit('login', { token: state.auth.token }, res => {
      if (res && res.ok) {
        res.users.forEach(u => state.users.set(u.uid, u));
        if (state.activeId) updateChatHeader();
        renderLists();
        return;
      }
      socket.emit('login', { username: state.auth.username, password: state.auth.password }, res2 => {
        if (res2 && res2.ok) {
          state.auth.token = res2.token;
          sessionStorage.setItem('jj_auth', JSON.stringify({ token: res2.token, username: state.auth.username }));
          res2.users.forEach(u => state.users.set(u.uid, u));
          if (state.activeId) updateChatHeader();
          renderLists();
        }
      });
    });
  } else if (!state.me && !didAutoLogin) {
    didAutoLogin = true;
    const saved = JSON.parse(sessionStorage.getItem('jj_auth') || 'null');
    if (saved) {
      // 自动登录失败（令牌过期等）则留在登录页
      socket.emit('login', { token: saved.token }, res => {
        if (res && res.ok) {
          state.auth = { token: res.token, username: saved.username, password: '' };
          enterApp(res);
        } else {
          sessionStorage.removeItem('jj_auth');
        }
      });
    }
  }
});

// 同一账号在其他标签页登录，被服务端踢下线
socket.on('force_logout', () => {
  toast('你的账号已在其他页面登录');
  sessionStorage.removeItem('jj_auth');
  setTimeout(() => location.reload(), 1500);
});

// ---------------- Socket 事件 ----------------
socket.on('presence', ({ uid, online, user }) => {
  if (user) state.users.set(user.uid, user);
  const u = state.users.get(uid);
  if (u) u.online = online;
  if (state.activeId) updateChatHeader();
  renderLists();
});

// 后台删除用户：移除联系人及相关私聊会话
socket.on('user:deleted', uid => {
  state.users.delete(uid);
  for (const [cid, c] of [...state.convs]) {
    if (c.kind === 'pri' && cid.includes(uid)) state.convs.delete(cid);
  }
  if (state.activeId && !state.convs.has(state.activeId)) {
    state.activeId = null;
    $('#chat-title').textContent = '';
    $('#chat-sub').textContent = '';
    $('#messages').innerHTML = '';
  }
  renderLists();
  updateTotalUnread();
  if (uid) toast('一位用户已被管理员移除');
});

socket.on('message', ({ convId, message }) => {
  let conv = state.convs.get(convId);
  if (!conv && convId.startsWith('pri:')) {
    const peer = convId.slice(4).split('_').find(u => u !== state.me.uid);
    if (peer && state.users.has(peer)) conv = ensurePrivateConv(peer);
  }
  if (!conv) return;
  if (!conv.messages.some(m => m.id === message.id)) {
    conv.messages.push(message);
    conv.lastMsg = message;
  }
  const isActive = state.activeId === convId;
  if (isActive) {
    appendMessageNode(message, conv);
    saveCursor(convId, message.time);
  } else if (message.from && message.from !== state.me.uid) {
    conv.unread = conv.messagesLoaded ? calcUnread(conv) : Math.min(99, conv.unread + 1);
  }
  renderLists();
  updateTotalUnread();
});

socket.on('typing', ({ convId, uid }) => {
  if (uid === state.me.uid || convId !== state.activeId) return;
  if (!typingUsers.has(convId)) typingUsers.set(convId, new Map());
  const map = typingUsers.get(convId);
  if (map.has(uid)) clearTimeout(map.get(uid));
  map.set(uid, setTimeout(() => { map.delete(uid); renderTyping(); }, 2500));
  renderTyping();
});

socket.on('group:new', group => {
  if (state.groups.has(group.gid)) return; // 自己创建的已处理
  state.groups.set(group.gid, group);
  ensureGroupConv(group.gid);
  renderLists();
  updateTotalUnread();
  toast(`你已加入群聊「${group.name}」`);
});

const typingUsers = new Map();

// ---------------- 列表渲染 ----------------
function previewOf(conv, msg) {
  if (!msg) return '';
  let body = msg.type === 'image' ? '[图片]' : msg.type === 'system' ? msg.content : msg.content;
  if (conv.kind === 'grp' && msg.from && msg.from !== state.me.uid) {
    const u = state.users.get(msg.from);
    body = `${u ? u.nickname : '有人'}: ${body}`;
  }
  return body;
}

function renderLists() {
  const kw = $('#search-input').value.trim().toLowerCase();
  // 会话列表
  const convs = [...state.convs.values()].sort((a, b) => {
    const ta = a.lastMsg ? a.lastMsg.time : 0;
    const tb = b.lastMsg ? b.lastMsg.time : 0;
    return tb - ta;
  });
  $('#conv-list').innerHTML = convs.map(c => {
    const m = convMeta(c);
    if (kw && !m.title.toLowerCase().includes(kw)) return '';
    const badge = c.unread ? `<span class="unread-badge">${c.unread > 99 ? '99+' : c.unread}</span>` : '';
    return `
      <div class="conv-item ${state.activeId === c.id ? 'active' : ''}" role="button" tabindex="0" data-id="${c.id}">
        <div class="avatar md" style="background:${colorOf(m.key)}">${m.avatar}</div>
        <div class="conv-info">
          <div class="conv-row1">
            <span class="conv-name">${esc(m.title)}</span>
            <span class="conv-time">${c.lastMsg ? fmtTime(c.lastMsg.time) : ''}</span>
          </div>
          <div class="conv-row2">
            <span class="conv-last">${esc(previewOf(c, c.lastMsg))}</span>
            ${badge}
          </div>
        </div>
      </div>`;
  }).join('');

  // 联系人列表
  const groups = [...state.groups.values()];
  const users = [...state.users.values()]
    .filter(u => u.uid !== state.me.uid)
    .sort((a, b) => (b.online - a.online) || a.nickname.localeCompare(b.nickname, 'zh'));
  const groupHTML = groups.map(g => {
    if (kw && !g.name.toLowerCase().includes(kw)) return '';
    return `
      <div class="contact-item" role="button" tabindex="0" data-group="${g.gid}">
        <div class="avatar md" style="background:${colorOf(g.gid)}">${g.gid === 'default' ? '🏠' : '👥'}</div>
        <span class="contact-name">${esc(g.name)}</span>
        <span class="contact-status">${g.members.length} 人</span>
      </div>`;
  }).join('');
  const userHTML = users.map(u => {
    if (kw && !u.nickname.toLowerCase().includes(kw)) return '';
    return `
      <div class="contact-item" role="button" tabindex="0" data-user="${u.uid}">
        ${avatarHTML(u, 'md')}
        <span class="contact-name">${esc(u.nickname)}</span>
        <span class="contact-status">${u.online ? '在线' : '离线'}</span>
      </div>`;
  }).join('');
  $('#contact-list').innerHTML = `
    <div class="contact-group-title">群聊</div>${groupHTML || '<div class="contact-item" style="color:#bbb">无匹配结果</div>'}
    <div class="contact-group-title">好友（佳佳号）</div>${userHTML || '<div class="contact-item" style="color:#bbb">无匹配结果</div>'}`;
}

function updateTotalUnread() {
  const total = [...state.convs.values()].reduce((s, c) => s + c.unread, 0);
  const badge = $('#nav-chat-badge');
  badge.textContent = total > 99 ? '99+' : total;
  badge.classList.toggle('hidden', total === 0);
}

// ---------------- 打开会话 / 消息渲染 ----------------
function openConv(conv) {
  state.activeId = conv.id;
  conv.unread = 0;
  $('#empty-state').classList.add('hidden');
  $('#chat-main').classList.remove('hidden');
  updateChatHeader();
  $('#messages').innerHTML = '<div class="sys-msg"><span>加载中...</span></div>';
  renderLists();
  updateTotalUnread();

  if (conv.messagesLoaded) {
    renderMessages(conv);
  } else {
    socket.emit('history', conv.id, res => {
      if (state.activeId !== conv.id) return;
      conv.messages = (res && res.ok && res.messages) || [];
      conv.messagesLoaded = true;
      renderMessages(conv);
      const last = conv.messages[conv.messages.length - 1];
      if (last) saveCursor(conv.id, last.time);
      conv.unread = 0; // 打开会话即视为已读
      renderLists();
      updateTotalUnread();
    });
  }
}

function updateChatHeader() {
  const conv = state.convs.get(state.activeId);
  if (!conv) return;
  const m = convMeta(conv);
  $('#chat-title').textContent = m.title;
  $('#chat-sub').textContent = conv.kind === 'grp'
    ? `共 ${state.groups.get(conv.gid).members.length} 人`
    : (state.users.get(conv.peer)?.online ? '在线' : '离线');
}

function renderMessages(conv) {
  const box = $('#messages');
  box.innerHTML = '';
  let lastTime = 0;
  conv.messages.forEach(m => {
    if (m.time - lastTime > FIVE_MIN) {
      box.insertAdjacentHTML('beforeend',
        `<div class="time-sep"><span>${fmtTime(m.time, true)} ${pad(new Date(m.time).getHours())}:${pad(new Date(m.time).getMinutes())}</span></div>`);
    }
    appendMessageNode(m, conv, box);
    lastTime = m.time;
  });
  box.scrollTop = box.scrollHeight;
}

function appendMessageNode(m, conv, boxArg) {
  const box = boxArg || $('#messages');
  if (m.type === 'system') {
    box.insertAdjacentHTML('beforeend', `<div class="sys-msg"><span>${esc(m.content)}</span></div>`);
    box.scrollTop = box.scrollHeight;
    return;
  }
  const self = m.from === state.me.uid;
  const sender = state.users.get(m.from);
  const showName = conv.kind === 'grp' && !self;
  const bubble = m.type === 'image'
    ? `<div class="bubble-wrap img-wrap"><div class="bubble img-bubble"><img class="msg-img" src="${m.content}" alt="图片"></div></div>`
    : `<div class="bubble-wrap"><div class="bubble">${esc(m.content)}</div></div>`;
  const avatarBg = colorOf(m.from || '');
  box.insertAdjacentHTML('beforeend', `
    <div class="msg-row ${self ? 'self' : ''}">
      <div class="avatar md" style="background:${avatarBg}">${sender ? sender.avatar : '👤'}</div>
      <div class="msg-body">
        ${showName ? `<div class="msg-sender">${esc(sender ? sender.nickname : '有人')}</div>` : ''}
        ${bubble}
      </div>
    </div>`);
  box.scrollTop = box.scrollHeight;
}

function renderTyping() {
  const bar = $('#typing-bar');
  const map = typingUsers.get(state.activeId);
  if (!map || map.size === 0) { bar.classList.add('hidden'); return; }
  const names = [...map.keys()].map(uid => state.users.get(uid)?.nickname || '有人').join('、');
  bar.textContent = `${names} 正在输入...`;
  bar.classList.remove('hidden');
}

// ---------------- 发送消息 ----------------
function sendText() {
  const input = $('#msg-input');
  const content = input.value.trim();
  if (!content || !state.activeId) return;
  socket.emit('message', { convId: state.activeId, type: 'text', content }, res => {
    if (!res || !res.ok) toast((res && res.error) || '发送失败');
  });
  input.value = '';
  input.style.height = '';
}

function sendImage(file) {
  if (!file || !state.activeId) return;
  if (file.size > 3 * 1024 * 1024) { toast('图片不能超过 3MB'); return; }
  const reader = new FileReader();
  reader.onload = () => {
    socket.emit('message', { convId: state.activeId, type: 'image', content: reader.result }, res => {
      if (!res || !res.ok) toast((res && res.error) || '图片发送失败');
    });
  };
  reader.readAsDataURL(file);
}

// ---------------- 创建群聊 ----------------
function openGroupModal() {
  const others = [...state.users.values()].filter(u => u.uid !== state.me.uid);
  $('#group-user-list').innerHTML = others.map(u => `
    <div class="modal-user" role="button" data-uid="${u.uid}">
      <span class="check"></span>
      ${avatarHTML(u, 'sm')}
      <span>${esc(u.nickname)}</span>
      <span style="margin-left:auto;font-size:12px;color:#bbb">${u.online ? '在线' : ''}</span>
    </div>`).join('') || '<p style="color:#bbb;text-align:center;padding:20px 0">暂时还没有其他用户</p>';
  $('#group-name-input').value = '';
  $('#group-modal').classList.remove('hidden');
}

// ---------------- 事件绑定 ----------------
function bindEvents() {
  // 导航切换
  $$('.nav-item[data-view]').forEach(btn => {
    btn.addEventListener('click', () => {
      $$('.nav-item[data-view]').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      state.view = btn.dataset.view;
      $('#list-title').textContent = state.view === 'chats' ? '聊天' : '通讯录';
      $('#conv-list').classList.toggle('hidden', state.view !== 'chats');
      $('#contact-list').classList.toggle('hidden', state.view !== 'contacts');
      $('#search-input').value = '';
      renderLists();
    });
  });

  $('#logout-btn').addEventListener('click', () => {
    sessionStorage.removeItem('jj_auth');
    location.reload();
  });
  $('#nav-avatar').addEventListener('click', () => {
    if (state.me) toast(`我是 ${state.me.realName}（账号：${state.me.username}），佳佳号：${state.me.uid}`);
  });
  $('#add-btn').addEventListener('click', openGroupModal);
  $('#search-input').addEventListener('input', renderLists);

  // 列表点击（事件委托）
  $('#conv-list').addEventListener('click', e => {
    const el = e.target.closest('.conv-item');
    if (el) { const conv = state.convs.get(el.dataset.id); if (conv) openConv(conv); }
  });
  $('#contact-list').addEventListener('click', e => {
    const uEl = e.target.closest('[data-user]');
    const gEl = e.target.closest('[data-group]');
    if (uEl) {
      const conv = ensurePrivateConv(uEl.dataset.user);
      // 切到聊天视图
      $('.nav-item[data-view="chats"]').click();
      renderLists();
      openConv(conv);
    } else if (gEl) {
      const conv = ensureGroupConv(gEl.dataset.group);
      $('.nav-item[data-view="chats"]').click();
      renderLists();
      openConv(conv);
    }
  });

  // 输入区
  const msgInput = $('#msg-input');
  msgInput.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendText(); }
  });
  msgInput.addEventListener('input', () => {
    msgInput.style.height = '';
    msgInput.style.height = Math.min(msgInput.scrollHeight, 120) + 'px';
    if (state.activeId) socket.emit('typing', state.activeId);
  });
  $('#send-btn').addEventListener('click', sendText);

  // 表情面板
  const emojiPanel = $('#emoji-panel');
  emojiPanel.innerHTML = EMOJIS.map(e => `<span role="button">${e}</span>`).join('');
  $('#emoji-btn').addEventListener('click', e => {
    e.stopPropagation();
    emojiPanel.classList.toggle('hidden');
    $('#emoji-btn').classList.toggle('active');
  });
  emojiPanel.addEventListener('click', e => {
    if (e.target.tagName === 'SPAN') {
      const start = msgInput.selectionStart ?? msgInput.value.length;
      msgInput.value = msgInput.value.slice(0, start) + e.target.textContent + msgInput.value.slice(start);
      msgInput.focus();
    }
  });
  document.addEventListener('click', e => {
    if (!emojiPanel.classList.contains('hidden') &&
        !e.target.closest('#emoji-panel') && !e.target.closest('#emoji-btn')) {
      emojiPanel.classList.add('hidden');
      $('#emoji-btn').classList.remove('active');
    }
  });

  // 图片
  $('#image-btn').addEventListener('click', () => $('#image-input').click());
  $('#image-input').addEventListener('change', e => {
    sendImage(e.target.files[0]);
    e.target.value = '';
  });

  // 消息区图片大图
  $('#messages').addEventListener('click', e => {
    if (e.target.classList.contains('msg-img')) {
      $('#lightbox-img').src = e.target.src;
      $('#lightbox').classList.remove('hidden');
    }
  });
  $('#lightbox').addEventListener('click', () => $('#lightbox').classList.add('hidden'));

  // 群聊弹层
  const selected = new Set();
  $('#group-user-list').addEventListener('click', e => {
    const el = e.target.closest('.modal-user');
    if (!el) return;
    const uid = el.dataset.uid;
    if (selected.has(uid)) { selected.delete(uid); el.classList.remove('checked'); }
    else { selected.add(uid); el.classList.add('checked'); }
  });
  $('#group-cancel').addEventListener('click', () => $('#group-modal').classList.add('hidden'));
  $('#group-ok').addEventListener('click', () => {
    if (selected.size === 0) { toast('请至少选择一位成员'); return; }
    socket.emit('group:create', {
      name: $('#group-name-input').value.trim(),
      members: [...selected]
    }, res => {
      if (!res || !res.ok) { toast((res && res.error) || '创建失败'); return; }
      state.groups.set(res.group.gid, res.group);
      const conv = ensureGroupConv(res.group.gid);
      $('#group-modal').classList.add('hidden');
      selected.clear();
      $('.nav-item[data-view="chats"]').click();
      renderLists();
      openConv(conv);
      toast('群聊创建成功');
    });
  });
}

// ---------------- 启动 ----------------
renderAvatarPicker();
bindEvents();

// 登录 / 注册表单绑定
$('#login-btn').addEventListener('click', () => {
  doLogin($('#login-username').value.trim(), $('#login-password').value);
});
$('#login-password').addEventListener('keydown', e => {
  if (e.key === 'Enter') $('#login-btn').click();
});
$('#login-username').addEventListener('keydown', e => {
  if (e.key === 'Enter') $('#login-password').focus();
});
$$('.auth-tab').forEach(t => t.addEventListener('click', () => switchAuthTab(t.dataset.mode)));
$('#register-btn').addEventListener('click', doRegister);
$('#reg-password2').addEventListener('keydown', e => {
  if (e.key === 'Enter') $('#register-btn').click();
});
