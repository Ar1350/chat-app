/**
 * 佳佳聊天 · 管理后台逻辑
 * 登录 → 拉取用户列表（5 秒轮询在线状态）→ 查看 / 重置密码
 */
const $ = s => document.querySelector(s);
const TOKEN_KEY = 'jj_admin_token';

let token = sessionStorage.getItem(TOKEN_KEY) || '';
let pollTimer = null;
let resetTarget = null; // 待重置密码的用户 { uid, realName }

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
      <td><button class="act" data-uid="${esc(u.uid)}" data-name="${esc(u.realName)}">修改密码</button></td>
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
  if (el) openPwdModal(el.dataset.uid, el.dataset.name);
});

$('#pwd-cancel').addEventListener('click', closePwdModal);
$('#pwd-ok').addEventListener('click', submitPwd);
$('#new-pwd').addEventListener('keydown', e => { if (e.key === 'Enter') submitPwd(); });
$('#pwd-modal').addEventListener('click', e => { if (e.target === $('#pwd-modal')) closePwdModal(); });

// ---------------- 启动 ----------------

// 已有令牌则直接进入（服务重启后令牌失效会自动回到登录页）
if (token) {
  api('/api/admin/users').then(d => { if (d.ok) { render(d.users); showApp(); } else showLogin(); }).catch(() => {});
} else {
  showLogin();
}
