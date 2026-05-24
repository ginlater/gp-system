(function () {
  'use strict';

  // ===== Utils =====
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
    return resp;
  }

  // ===== Auth =====
  async function loadUser() {
    const resp = await api('/api/auth/me');
    if (!resp) return;
    const data = await resp.json();
    document.getElementById('displayName').textContent = data.display_name || data.username;
  }

  document.getElementById('logoutBtn').addEventListener('click', async () => {
    await api('/api/auth/logout', { method: 'POST' });
    window.location.href = '/web/login.html';
  });

  // ===== Tabs =====
  document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
      document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
      tab.classList.add('active');
      document.getElementById('panel-' + tab.dataset.tab).classList.add('active');
      if (tab.dataset.tab === 'recordings') loadRecordings();
      if (tab.dataset.tab === 'sessions') loadSessions();
    });
  });

  // ===== Tab 1: Recording =====
  let mediaRecorder = null;
  let audioChunks = [];
  let timerInterval = null;
  let startTime = 0;
  let audioCtx = null;
  let analyser = null;
  let animFrame = null;

  const recordBtn = document.getElementById('recordBtn');
  const timerEl = document.getElementById('timer');
  const statusEl = document.getElementById('recordStatus');
  const canvas = document.getElementById('waveCanvas');
  const uploadEl = document.getElementById('uploadProgress');

  function formatTime(ms) {
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    return String(m).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
  }

  function drawWave() {
    if (!analyser) return;
    const ctx = canvas.getContext('2d');
    const bufLen = analyser.frequencyBinCount;
    const data = new Uint8Array(bufLen);
    analyser.getByteTimeDomainData(data);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.lineWidth = 2;
    ctx.strokeStyle = '#4f46e5';
    ctx.beginPath();
    const slice = canvas.width / bufLen;
    let x = 0;
    for (let i = 0; i < bufLen; i++) {
      const v = data[i] / 128.0;
      const y = (v * canvas.height) / 2;
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      x += slice;
    }
    ctx.lineTo(canvas.width, canvas.height / 2);
    ctx.stroke();
    animFrame = requestAnimationFrame(drawWave);
  }

  recordBtn.addEventListener('click', async () => {
    if (mediaRecorder && mediaRecorder.state === 'recording') {
      mediaRecorder.stop();
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      audioChunks = [];
      const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus' : 'audio/webm';
      mediaRecorder = new MediaRecorder(stream, { mimeType });

      audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const source = audioCtx.createMediaStreamSource(stream);
      analyser = audioCtx.createAnalyser();
      analyser.fftSize = 2048;
      source.connect(analyser);
      canvas.width = canvas.offsetWidth;
      canvas.height = 60;
      canvas.classList.add('active');
      drawWave();

      mediaRecorder.ondataavailable = (e) => { if (e.data.size > 0) audioChunks.push(e.data); };
      mediaRecorder.onstop = async () => {
        clearInterval(timerInterval);
        cancelAnimationFrame(animFrame);
        canvas.classList.remove('active');
        stream.getTracks().forEach(t => t.stop());
        if (audioCtx) audioCtx.close();
        recordBtn.classList.remove('recording');
        recordBtn.textContent = '开始录音';
        statusEl.textContent = '录音完成，正在上传...';
        uploadEl.classList.add('active');

        const blob = new Blob(audioChunks, { type: mimeType });
        const fd = new FormData();
        fd.append('file', blob, 'recording.webm');
        try {
          const resp = await api('/api/consultant/recordings', { method: 'POST', body: fd });
          if (!resp) return;
          const result = await resp.json();
          if (resp.ok) {
            toast('上传成功，转录中...', 'success');
            statusEl.textContent = '上传成功！点击按钮继续录音';
          } else {
            toast(result.error || '上传失败', 'error');
            statusEl.textContent = '上传失败';
          }
        } catch (err) {
          toast('网络错误', 'error');
          statusEl.textContent = '上传失败';
        }
        uploadEl.classList.remove('active');
      };

      mediaRecorder.start(1000);
      startTime = Date.now();
      timerInterval = setInterval(() => { timerEl.textContent = formatTime(Date.now() - startTime); }, 200);
      recordBtn.classList.add('recording');
      recordBtn.textContent = '停止';
      statusEl.textContent = '正在录音...';
    } catch (err) {
      toast('无法访问麦克风: ' + err.message, 'error');
    }
  });

  // ===== Tab 2: Recordings List =====
  async function loadRecordings() {
    const resp = await api('/api/consultant/recordings');
    if (!resp) return;
    const list = await resp.json();
    const container = document.getElementById('recordingsList');
    if (!list.length) { container.innerHTML = '<p class="text-muted text-center">暂无录音</p>'; return; }
    container.innerHTML = list.map(r => {
      const time = r.recorded_at || r.created_at || '';
      const asrBadge = r.asr_status === 'done' ? '<span class="badge badge-success">已转录</span>'
        : r.asr_status === 'failed' ? '<span class="badge badge-danger">转录失败</span>'
        : '<span class="badge badge-warning">转录中</span>';
      const bindInfo = r.session_id
        ? `<span class="badge badge-info">${r.customer || ''} · ${r.stage || ''}</span>`
        : `<button class="btn btn-sm btn-outline bind-btn" data-id="${r.id}">绑定</button>`;
      const audioBtn = r.audio_url
        ? `<audio controls preload="none" style="height:28px;max-width:160px;"><source src="${r.audio_url}"></audio>`
        : '';
      return `<div class="rec-item">
        <div class="rec-info">
          <div class="rec-time">${time}</div>
          <div class="rec-meta">${asrBadge} ${bindInfo}</div>
        </div>
        <div class="rec-actions">${audioBtn}</div>
      </div>`;
    }).join('');
    container.querySelectorAll('.bind-btn').forEach(btn => {
      btn.addEventListener('click', () => openBindModal(btn.dataset.id));
    });
  }
  document.getElementById('refreshRecBtn').addEventListener('click', loadRecordings);

  // ===== Bind Modal =====
  const bindModal = document.getElementById('bindModal');
  const bindForm = document.getElementById('bindForm');
  const customerInput = document.getElementById('bindCustomer');
  const suggEl = document.getElementById('customerSugg');
  let suggTimeout = null;

  function openBindModal(recId) {
    document.getElementById('bindRecId').value = recId;
    customerInput.value = '';
    document.getElementById('bindStage').value = '';
    suggEl.classList.remove('show');
    bindModal.classList.add('active');
  }
  document.getElementById('bindModalClose').addEventListener('click', () => bindModal.classList.remove('active'));
  bindModal.addEventListener('click', (e) => { if (e.target === bindModal) bindModal.classList.remove('active'); });

  customerInput.addEventListener('input', () => {
    clearTimeout(suggTimeout);
    const q = customerInput.value.trim();
    if (!q) { suggEl.classList.remove('show'); return; }
    suggTimeout = setTimeout(async () => {
      const resp = await api('/api/consultant/customers?q=' + encodeURIComponent(q));
      if (!resp) return;
      const data = await resp.json();
      if (data.customers && data.customers.length) {
        suggEl.innerHTML = data.customers.map(c => `<div>${c}</div>`).join('');
        suggEl.classList.add('show');
        suggEl.querySelectorAll('div').forEach(div => {
          div.addEventListener('click', () => { customerInput.value = div.textContent; suggEl.classList.remove('show'); });
        });
      } else {
        suggEl.classList.remove('show');
      }
    }, 300);
  });

  bindForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const rid = document.getElementById('bindRecId').value;
    const customer = customerInput.value.trim();
    const stage = document.getElementById('bindStage').value;
    if (!customer || !stage) { toast('请填写完整', 'error'); return; }
    const resp = await api(`/api/consultant/recordings/${rid}/bind`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ customer, stage }),
    });
    if (!resp) return;
    const data = await resp.json();
    if (resp.ok) {
      toast('绑定成功', 'success');
      bindModal.classList.remove('active');
      loadRecordings();
    } else {
      toast(data.error || '绑定失败', 'error');
    }
  });

  // ===== Tab 3: Sessions =====
  async function loadSessions() {
    const resp = await api('/api/consultant/sessions');
    if (!resp) return;
    const list = await resp.json();
    const container = document.getElementById('sessionsList');
    if (!list.length) { container.innerHTML = '<p class="text-muted text-center">暂无接诊记录</p>'; return; }
    container.innerHTML = list.map(s => {
      const statusBadge = s.analysis_status === 'done' ? '<span class="badge badge-success">已完成</span>'
        : s.analysis_status === 'running' ? '<span class="badge badge-warning">分析中</span>'
        : '<span class="badge badge-info">待分析</span>';
      let scores = '';
      if (s.analysis_scores) {
        try { const sc = JSON.parse(s.analysis_scores); scores = `总分: ${sc.total || '-'}`; } catch(e) {}
      }
      const actionBtn = s.analysis_status === 'pending' || s.analysis_status === 'failed'
        ? `<button class="btn btn-sm btn-primary analyze-btn" data-id="${s.id}">开始分析</button>`
        : s.analysis_status === 'done'
        ? `<button class="btn btn-sm btn-outline report-btn" data-id="${s.id}">查看报告</button>`
        : '';
      return `<div class="session-item" data-id="${s.id}">
        <div class="session-info">
          <h4>${s.customer || '(未绑定)'}</h4>
          <div class="session-meta">${s.service_date || ''} · ${s.recording_count}段录音 ${scores}</div>
        </div>
        <div class="session-right">
          ${statusBadge}
          <div class="mt-2">${actionBtn}</div>
        </div>
      </div>`;
    }).join('');
    container.querySelectorAll('.analyze-btn').forEach(btn => {
      btn.addEventListener('click', (e) => { e.stopPropagation(); triggerAnalysis(btn.dataset.id, btn); });
    });
    container.querySelectorAll('.report-btn').forEach(btn => {
      btn.addEventListener('click', (e) => { e.stopPropagation(); viewReport(btn.dataset.id); });
    });
  }
  document.getElementById('refreshSessBtn').addEventListener('click', loadSessions);

  async function triggerAnalysis(sid, btn) {
    btn.disabled = true;
    btn.textContent = '提交中...';
    const resp = await api(`/api/consultant/sessions/${sid}/analyze`, { method: 'POST' });
    if (!resp) return;
    const data = await resp.json();
    if (resp.ok) {
      toast('分析已启动', 'success');
      pollSession(sid);
    } else {
      toast(data.error || '启动失败', 'error');
      btn.disabled = false;
      btn.textContent = '开始分析';
    }
  }

  function pollSession(sid) {
    const iv = setInterval(async () => {
      const resp = await api(`/api/consultant/sessions/${sid}`);
      if (!resp) { clearInterval(iv); return; }
      const data = await resp.json();
      const status = data.session.analysis_status;
      if (status === 'done' || status === 'failed') {
        clearInterval(iv);
        loadSessions();
        if (status === 'done') toast('分析完成', 'success');
        else toast('分析失败', 'error');
      }
    }, 5000);
  }

  async function viewReport(sid) {
    const resp = await api(`/api/consultant/sessions/${sid}`);
    if (!resp) return;
    const data = await resp.json();
    const s = data.session;
    document.getElementById('reportTitle').textContent = `${s.customer || ''} - ${s.service_date || ''}`;
    document.getElementById('reportContent').textContent = s.analysis_result || '暂无分析结果';
    document.getElementById('reportModal').classList.add('active');
  }
  document.getElementById('reportModalClose').addEventListener('click', () => document.getElementById('reportModal').classList.remove('active'));
  document.getElementById('reportModal').addEventListener('click', (e) => { if (e.target.id === 'reportModal') e.target.classList.remove('active'); });

  // ===== Init =====
  loadUser();
})();

