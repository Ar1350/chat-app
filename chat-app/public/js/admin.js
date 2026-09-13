/**
 * 佳佳聊天 · 管理后台逻辑
 * 登录 → 拉取用户列表（5 秒轮询在线状态）→ 查看 / 重置密码
 */
const $ = s => document.querySelector(s);
const TOKEN_KEY = 'jj_admin_token';
const AVATARS = ['😀', '🐱', '🦊', '🐼', '🐨', '🦁', '🐸', '🐵', '🦄', '🐷', '🐧', '🐰', '🦉', '🐙', '🦋', '🌟'];

let token = sessionStorage.getItem(TOKEN_KEY) || '';
let pollTimer = null;
let resetTarget = null; // 待重置密码的用户 { uid, realName }
let infoTarget = null;  // 待修改资料的用户 { uid }
let delTarget = null;   // 待删除的用户 { uid, realName }
let lastUsers = [];     // 最近一次拉取的用户列表（弹窗回填用）

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}

async function api(path, opts = {}) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: 'Bearer ' + token } : {});
  const r = await fetch(path, Object.assign({ headers }, opts));
  if (r.status === 401) { showLogin(); throw new Error('未授权'); }
  return r.json();
}

// ---------------- 视图切换 ----------------

function showLogin() {
  clearInterval(pollTimer);
  pollTimer = null;
  token = '';
  sessionStorage.removeItem(TOKEN_KEY);
  $('#admin-app').classList.add('hidden');
  $('#admin-login').classList.remove('hidden');
}

function showApp() {
  $('#admin-login').classList.add('hidden');
  $('#admin-app').classList.remove('hidden');
  loadUsers();
  clearInterval(pollTimer);
  pollTimer = setInterval(loadUsers, 5000); // 5 秒刷新在线状态
}

// ---------------- 用户列表 ----------------

function fmtTime(ts) {
  if (!ts) return '—';
  const d = new Date(ts);
  const p = n => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

async function loadUsers() {
  try {
    const d = await api('/api/admin/users');
    if (!d.ok) return;
    render(d.users);
  } catch { /* 未授权时已在 api() 中处理 */ }
}

function render(users) {
  lastUsers = users;
  const onlineCount = users.filter(u => u.online).length;
  $('#stat-total').textContent = `总用户 ${users.length}`;
  $('#stat-online').textContent = `在线 ${onlineCount}`;

  $('#user-rows').innerHTML = users.map(u => `
    <tr class="${u.online ? 'on' : ''}">
      <td><span class="ava">${esc(u.avatar) || '👤'}</span></td>
      <td>${esc(u.username)}</td>
      <td>${esc(u.realName)}</td>
      <td class="pwd-cell ${u.password ? '' : 'none'}">${u.password ? esc(u.password) : '未设置'}</td>
      <td><span class="pill ${u.online ? 'on' : 'off'}">${u.online ? '● 在线' : '○ 离线'}</span></td>
      <td>${esc(u.uid)}</td>
      <td>${fmtTime(u.createdAt)}</td>
      <td>${fmtTime(u.lastLoginAt)}</td>
      <td class="ops">
        <button class="act" data-act="pwd" data-uid="${esc(u.uid)}" data-name="${esc(u.realName)}">修改密码</button>
        <button class="act" data-act="info" data-uid="${esc(u.uid)}" data-name="${esc(u.realName)}">修改信息</button>
        <button class="act danger" data-act="del" data-uid="${esc(u.uid)}" data-name="${esc(u.realName)}">删除</button>
      </td>
    </tr>`).join('') || '<tr><td colspan="9" class="empty">还没有注册用户，去聊天页注册一个吧</td></tr>';
}

// ---------------- 修改密码弹窗 ----------------

function openPwdModal(uid, name) {
  resetTarget = { uid, name };
  $('#pwd-for').textContent = `为「${name}」（佳佳号 ${uid}）设置新密码`;
  $('#new-pwd').value = '';
  $('#pwd-error').textContent = '';
  $('#pwd-modal').classList.remove('hidden');
  $('#new-pwd').focus();
}

function closePwdModal() {
  resetTarget = null;
  $('#pwd-modal').classList.add('hidden');
}

async function submitPwd() {
  if (!resetTarget) return;
  const pwd = $('#new-pwd').value;
  if (pwd.length < 6 || pwd.length > 20) {
    $('#pwd-error').textContent = '密码需为 6-20 位';
    return;
  }
  const btn = $('#pwd-ok');
  btn.disabled = true;
  btn.textContent = '保 存 中...';
  try {
    const d = await api(`/api/admin/users/${resetTarget.uid}/password`, {
      method: 'POST',
      body: JSON.stringify({ password: pwd })
    });
    if (!d.ok) { $('#pwd-error').textContent = d.error || '保存失败'; return; }
    closePwdModal();
    loadUsers();
  } catch {
    $('#pwd-error').textContent = '网络错误，请重试';
  } finally {
    btn.disabled = false;
    btn.textContent = '保 存';
  }
}

// ---------------- 修改信息弹窗 ----------------

let infoAvatar = AVATARS[0];

function renderInfoAvatarPicker() {
  $('#info-avatar-picker').innerHTML = AVATARS
    .map(a => `<div class="pick${a === infoAvatar ? ' selected' : ''}" role="button" tabindex="0" data-emoji="${a}">${a}</div>`).join('');
}

function openInfoModal(uid, name) {
  const u = lastUsers.find(x => x.uid === uid);
  if (!u) return;
  infoTarget = { uid };
  infoAvatar = u.avatar || AVATARS[0];
  renderInfoAvatarPicker();
  $('#info-for').textContent = `修改「${name}」（佳佳号 ${uid}）的资料`;
  $('#info-username').value = u.username;
  $('#info-realname').value = u.realName;
  $('#info-error').textContent = '';
  $('#info-modal').classList.remove('hidden');
}

async function submitInfo() {
  if (!infoTarget) return;
  const username = $('#info-username').value.trim();
  const realName = $('#info-realname').value.trim();
  if (!username || !realName) { $('#info-error').textContent = '请填写完整'; return; }
  const btn = $('#info-ok');
  btn.disabled = true;
  btn.textContent = '保 存 中...';
  try {
    const d = await api(`/api/admin/users/${infoTarget.uid}/profile`, {
      method: 'POST',
      body: JSON.stringify({ username, realName, avatar: infoAvatar })
    });
    if (!d.ok) { $('#info-error').textContent = d.error || '保存失败'; return; }
    $('#info-modal').classList.add('hidden');
    infoTarget = null;
    loadUsers();
  } catch {
    $('#info-error').textContent = '网络错误，请重试';
  } finally {
    btn.disabled = false;
    btn.textContent = '保 存';
  }
}

// ---------------- 删除用户弹窗 ----------------

function openDelModal(uid, name) {
  delTarget = { uid, name };
  $('#del-for').textContent = `确定删除「${name}」（佳佳号 ${uid}）吗？`;
  $('#del-modal').classList.remove('hidden');
}

async function submitDel() {
  if (!delTarget) return;
  const btn = $('#del-ok');
  btn.disabled = true;
  btn.textContent = '删除中...';
  try {
    const d = await api(`/api/admin/users/${delTarget.uid}/delete`, { method: 'POST' });
    if (!d.ok) return;
    $('#del-modal').classList.add('hidden');
    delTarget = null;
    loadUsers();
  } catch { /* 未授权已在 api() 处理 */ }
  finally {
    btn.disabled = false;
    btn.textContent = '确认删除';
  }
}

// ---------------- 事件绑定 ----------------

$('#adm-login-btn').addEventListener('click', async () => {
  const username = $('#adm-user').value.trim();
  const password = $('#adm-pass').value;
  const err = $('#adm-error');
  err.textContent = '';
  if (!username || !password) { err.textContent = '请输入账号和密码'; return; }
  const btn = $('#adm-login-btn');
  btn.disabled = true;
  btn.textContent = '登 录 中...';
  try {
    const d = await fetch('/api/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    }).then(r => r.json());
    if (!d.ok) { err.textContent = d.error || '登录失败'; return; }
    token = d.token;
    sessionStorage.setItem(TOKEN_KEY, token);
    showApp();
  } catch {
    err.textContent = '网络错误，请重试';
  } finally {
    btn.disabled = false;
    btn.textContent = '登 录';
  }
});

$('#adm-pass').addEventListener('keydown', e => { if (e.key === 'Enter') $('#adm-login-btn').click(); });
$('#adm-user').addEventListener('keydown', e => { if (e.key === 'Enter') $('#adm-pass').focus(); });
$('#adm-logout').addEventListener('click', showLogin);

$('#user-rows').addEventListener('click', e => {
  const el = e.target.closest('.act');
  if (!el) return;
  const { act, uid, name } = el.dataset;
  if (act === 'pwd') openPwdModal(uid, name);
  else if (act === 'info') openInfoModal(uid, name);
  else if (act === 'del') openDelModal(uid, name);
});

// 修改信息：头像选择
$('#info-avatar-picker').addEventListener('click', e => {
  const el = e.target.closest('.pick');
  if (!el) return;
  infoAvatar = el.dataset.emoji;
  renderInfoAvatarPicker();
});

$('#pwd-cancel').addEventListener('click', closePwdModal);
$('#pwd-ok').addEventListener('click', submitPwd);
$('#new-pwd').addEventListener('keydown', e => { if (e.key === 'Enter') submitPwd(); });
$('#pwd-modal').addEventListener('click', e => { if (e.target === $('#pwd-modal')) closePwdModal(); });

$('#info-cancel').addEventListener('click', () => { $('#info-modal').classList.add('hidden'); infoTarget = null; });
$('#info-ok').addEventListener('click', submitInfo);
$('#info-modal').addEventListener('click', e => { if (e.target === $('#info-modal')) { $('#info-modal').classList.add('hidden'); infoTarget = null; } });

$('#del-cancel').addEventListener('click', () => { $('#del-modal').classList.add('hidden'); delTarget = null; });
$('#del-ok').addEventListener('click', submitDel);
$('#del-modal').addEventListener('click', e => { if (e.target === $('#del-modal')) { $('#del-modal').classList.add('hidden'); delTarget = null; } });

// ---------------- 启动 ----------------

// 已有令牌则直接进入（服务重启后令牌失效会自动回到登录页）
if (token) {
  api('/api/admin/users').then(d => { if (d.ok) { render(d.users); showApp(); } else showLogin(); }).catch(() => {});
} else {
  showLogin();
}
