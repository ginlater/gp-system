(function () {
  'use strict';

  function toast(msg, type) {
    const c = document.getElementById('toastContainer');
    const el = document.createElement('div');
    el.className = 'toast' + (type ? ' toast-' + type : '');
    el.textContent = msg;
    c.appendChild(el);
    setTimeout(() => el.remove(), 3500);
  }

  async function api(path, opts) {
    const resp = await fetch(path, opts);
    if (resp.status === 401) { window.location.href = '/web/login.html'; return null; }
    if (resp.status === 403) { toast('权限不足', 'error'); return null; }
    return resp;
  }

  // Logout
  document.getElementById('logoutBtn').addEventListener('click', async () => {
    await api('/api/auth/logout', { method: 'POST' });
    window.location.href = '/web/login.html';
  });

  // Load users
  async function loadUsers() {
    const resp = await api('/api/admin/users');
    if (!resp) return;
    const users = await resp.json();
    const tbody = document.getElementById('usersTbody');
    tbody.innerHTML = users.map(u => `<tr>
      <td>${u.username}</td>
      <td>${u.display_name || ''}</td>
      <td><span class="badge ${u.role === 'admin' ? 'badge-info' : 'badge-success'} role-badge">${u.role}</span></td>
      <td>${u.store || '-'}</td>
      <td>
        <button class="btn btn-sm btn-outline edit-btn" data-username="${u.username}">编辑</button>
        <button class="delete-btn" data-username="${u.username}">删除</button>
      </td>
    </tr>`).join('');
    tbody.querySelectorAll('.edit-btn').forEach(btn => btn.addEventListener('click', () => openEdit(btn.dataset.username, users)));
    tbody.querySelectorAll('.delete-btn').forEach(btn => btn.addEventListener('click', () => deleteUser(btn.dataset.username)));
  }

  // Modal
  const modal = document.getElementById('userModal');
  const form = document.getElementById('userForm');
  const usernameInput = document.getElementById('formUsername');

  function openCreate() {
    document.getElementById('modalTitle').textContent = '新建用户';
    document.getElementById('editUsername').value = '';
    document.getElementById('pwdHint').textContent = '';
    usernameInput.value = '';
    usernameInput.disabled = false;
    document.getElementById('formPassword').value = '';
    document.getElementById('formPassword').required = true;
    document.getElementById('formDisplayName').value = '';
    document.getElementById('formRole').value = 'user';
    document.getElementById('formStore').value = '';
    document.getElementById('formSubmitBtn').textContent = '创建';
    modal.classList.add('active');
  }

  function openEdit(username, users) {
    const u = users.find(x => x.username === username);
    if (!u) return;
    document.getElementById('modalTitle').textContent = '编辑用户';
    document.getElementById('editUsername').value = username;
    document.getElementById('pwdHint').textContent = '（留空不修改）';
    usernameInput.value = username;
    usernameInput.disabled = true;
    document.getElementById('formPassword').value = '';
    document.getElementById('formPassword').required = false;
    document.getElementById('formDisplayName').value = u.display_name || '';
    document.getElementById('formRole').value = u.role;
    document.getElementById('formStore').value = u.store || '';
    document.getElementById('formSubmitBtn').textContent = '保存';
    modal.classList.add('active');
  }

  document.getElementById('addUserBtn').addEventListener('click', openCreate);
  document.getElementById('modalClose').addEventListener('click', () => modal.classList.remove('active'));
  modal.addEventListener('click', (e) => { if (e.target === modal) modal.classList.remove('active'); });

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const editUser = document.getElementById('editUsername').value;
    const body = {
      display_name: document.getElementById('formDisplayName').value.trim(),
      role: document.getElementById('formRole').value,
      store: document.getElementById('formStore').value.trim(),
    };
    const pwd = document.getElementById('formPassword').value;

    if (editUser) {
      if (pwd) body.password = pwd;
      const resp = await api(`/api/admin/users/${editUser}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!resp) return;
      const data = await resp.json();
      if (resp.ok) { toast('已更新', 'success'); modal.classList.remove('active'); loadUsers(); }
      else toast(data.error || '更新失败', 'error');
    } else {
      body.username = usernameInput.value.trim();
      body.password = pwd;
      if (!body.username || !body.password || !body.display_name) { toast('请填写完整', 'error'); return; }
      const resp = await api('/api/admin/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!resp) return;
      const data = await resp.json();
      if (resp.ok) { toast('创建成功', 'success'); modal.classList.remove('active'); loadUsers(); }
      else toast(data.error || '创建失败', 'error');
    }
  });

  async function deleteUser(username) {
    if (!confirm(`确定删除用户「${username}」？`)) return;
    const resp = await api(`/api/admin/users/${username}`, { method: 'DELETE' });
    if (!resp) return;
    const data = await resp.json();
    if (resp.ok) { toast('已删除', 'success'); loadUsers(); }
    else toast(data.error || '删除失败', 'error');
  }

  // Init
  loadUsers();
})();