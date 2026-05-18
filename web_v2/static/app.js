/* ====== 工牌接诊分析系统 前端 ====== */

const PREFIX = (window.URL_PREFIX || "").replace(/\/$/, "");
function U(path) { return PREFIX + path; }

function escapeHtml(str) {
  if (str == null) return "";
  return String(str)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

function statusChip(status) {
  const map = {
    pending: { cls: "", label: "待开始" },
    running: { cls: "run", label: "进行中…" },
    done:    { cls: "ok",  label: "已完成" },
    failed:  { cls: "fail",label: "失败" },
  };
  const c = map[status] || map.pending;
  return `<span class="chip ${c.cls}">${c.label}</span>`;
}

function formatSize(bytes) {
  if (!bytes) return "";
  const mb = bytes / 1024 / 1024;
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${(bytes / 1024).toFixed(0)} KB`;
}

async function api(path, opts = {}) {
  opts.headers = Object.assign({ "Content-Type": "application/json" }, opts.headers || {});
  const r = await fetch(U(path), opts);
  if (r.status === 401) { window.location = U("/login"); throw new Error("unauthorized"); }
  return r;
}

/* ============ 列表页 ============ */
let _allRecs = [];

async function loadRecordings() {
  const r = await api("/api/recordings");
  const data = await r.json();
  _allRecs = data.recordings || [];
  document.getElementById("rec-count").textContent = _allRecs.length;
  renderRecordings();
}

function renderRecordings() {
  const list = document.getElementById("rec-list");
  const q = (document.getElementById("filter-input").value || "").trim().toLowerCase();
  const filtered = _allRecs.filter(r => {
    if (!q) return true;
    return [r.advisor, r.customer, r.oss_key].some(s => s && s.toLowerCase().includes(q));
  });
  if (!filtered.length) {
    list.innerHTML = `<div class="empty">${_allRecs.length ? "没有匹配的录音" : "暂无录音，请点击右上角「上传录音」或「同步云端」。"}</div>`;
    return;
  }
  list.innerHTML = filtered.map(r => `
    <div class="rec-card">
      <a class="rec-main" href="${U('/recording/' + r.id)}">
        <div class="rec-line1">
          <span class="advisor">${escapeHtml(r.advisor || "未填顾问")}</span>
          <span class="vs">→</span>
          <span class="customer">${escapeHtml(r.customer || "未填顾客")}</span>
        </div>
        <div class="rec-line2">
          <span>🕐 ${escapeHtml(r.recorded_at || "时间未知")}</span>
          ${r.duration_label ? `<span>⏱️ ${escapeHtml(r.duration_label)}</span>` : ""}
          ${r.size_bytes ? `<span>📦 ${formatSize(r.size_bytes)}</span>` : ""}
          <span>📁 <code>${escapeHtml(r.oss_key)}</code></span>
        </div>
      </a>
      <div class="rec-tags">
        ${statusChip(r.asr_status)}
        ${statusChip(r.analysis_status)}
        ${r.has_evaluation ? `<span class="chip has-eval">✓ 已点评</span>` : `<span class="chip">未点评</span>`}
      </div>
    </div>
  `).join("");
}

function initIndexPage() {
  loadRecordings();

  document.getElementById("filter-input").addEventListener("input", renderRecordings);

  document.getElementById("btn-scan").addEventListener("click", async (e) => {
    e.target.disabled = true; e.target.textContent = "同步中…";
    try {
      const r = await api("/api/scan", { method: "POST" });
      const d = await r.json();
      alert(`同步完成，新增 ${d.added} 条录音`);
      await loadRecordings();
    } catch (err) { alert("同步失败：" + err.message); }
    finally { e.target.disabled = false; e.target.textContent = "🔄 同步云端"; }
  });

  const panel = document.getElementById("upload-panel");
  document.getElementById("btn-show-upload").addEventListener("click", () => panel.classList.toggle("hidden"));
  document.getElementById("btn-cancel-upload").addEventListener("click", () => panel.classList.add("hidden"));

  document.getElementById("upload-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const form = e.target;
    const msg = document.getElementById("upload-msg");
    const fd = new FormData(form);
    msg.className = "form-msg"; msg.textContent = "上传中，请耐心等待…";
    const submitBtn = form.querySelector("button[type=submit]");
    submitBtn.disabled = true;
    try {
      const r = await fetch(U("/api/upload"), { method: "POST", body: fd });
      if (r.status === 401) { window.location = U("/login"); return; }
      if (!r.ok) {
        const d = await r.json().catch(() => ({}));
        throw new Error(d.error || `HTTP ${r.status}`);
      }
      const d = await r.json();
      msg.className = "form-msg ok"; msg.textContent = "上传成功！正在跳转…";
      setTimeout(() => { window.location = U(`/recording/${d.id}`); }, 800);
    } catch (err) {
      msg.className = "form-msg err"; msg.textContent = "上传失败：" + err.message;
    } finally { submitBtn.disabled = false; }
  });
}

/* ============ 详情页 ============ */
let _rid = null;
let _pollTimer = null;

async function loadDetail() {
  const r = await api(`/api/recording/${_rid}`);
  const d = await r.json();

  document.getElementById("meta-advisor").textContent = d.advisor || "—";
  document.getElementById("meta-customer").textContent = d.customer || "—";
  document.getElementById("meta-time").textContent = d.recorded_at || "—";
  document.getElementById("meta-duration").textContent = d.duration_label || "—";
  document.getElementById("meta-key").textContent = d.oss_key;

  const audio = document.getElementById("audio");
  if (audio.dataset.url !== d.audio_url) {
    audio.src = d.audio_url;
    audio.dataset.url = d.audio_url;
  }

  // ASR
  const asrChip = document.getElementById("asr-status-chip");
  asrChip.outerHTML = statusChip(d.asr_status).replace('class="chip', 'id="asr-status-chip" class="chip');
  const asrContent = document.getElementById("asr-content");
  if (d.asr_status === "done") {
    asrContent.textContent = d.asr_transcript || "(转录结果为空)";
  } else if (d.asr_status === "running") {
    asrContent.textContent = "正在转录中，请稍候…（一段 10 分钟的录音通常 30-90 秒内完成）";
  } else if (d.asr_status === "failed") {
    asrContent.textContent = "转录失败：" + (d.asr_error || "未知错误");
  } else {
    asrContent.textContent = '尚未转录，点击右上角「开始转录」。';
  }

  // 分析
  const anaChip = document.getElementById("ana-status-chip");
  anaChip.outerHTML = statusChip(d.analysis_status).replace('class="chip', 'id="ana-status-chip" class="chip');
  const anaContent = document.getElementById("ana-content");
  if (d.analysis_status === "done") {
    anaContent.innerHTML = renderMarkdown(d.analysis_result || "");
  } else if (d.analysis_status === "running") {
    anaContent.textContent = "正在调用 Claude 进行复盘点评，预计 30-120 秒…";
  } else if (d.analysis_status === "failed") {
    anaContent.textContent = "分析失败：" + (d.analysis_error || "未知错误");
  } else {
    anaContent.textContent = d.asr_status === "done"
      ? '已就绪。点击右上角「开始分析」生成 AI 复盘点评。'
      : '需要先完成 ASR 转录。';
  }

  // 评价
  const ev = d.evaluation;
  const evChip = document.getElementById("eval-chip");
  if (ev) {
    evChip.className = "chip has-eval";
    evChip.textContent = "✓ 已填写于 " + (ev.updated_at || ev.created_at);
    document.querySelector("textarea[name=wrong_points]").value = ev.wrong_points || "";
    document.querySelector("textarea[name=improvement]").value = ev.improvement || "";
    document.querySelector("textarea[name=correct_practice]").value = ev.correct_practice || "";
  } else {
    evChip.className = "chip";
    evChip.textContent = "未填写";
  }

  // 控制按钮
  const btnAsr = document.getElementById("btn-asr");
  const btnAna = document.getElementById("btn-analyze");
  btnAsr.disabled = d.asr_status === "running";
  btnAsr.textContent = d.asr_status === "running" ? "转录中…" :
                       d.asr_status === "done" ? "重新转录" : "开始转录";
  btnAna.disabled = d.analysis_status === "running" || d.asr_status !== "done";
  btnAna.textContent = d.analysis_status === "running" ? "分析中…" :
                       d.analysis_status === "done" ? "重新分析" : "开始分析";

  // 轮询
  const polling = d.asr_status === "running" || d.analysis_status === "running";
  if (polling && !_pollTimer) {
    _pollTimer = setInterval(loadDetail, 3500);
  } else if (!polling && _pollTimer) {
    clearInterval(_pollTimer); _pollTimer = null;
  }
}

// 极简 Markdown 渲染（够用于 Claude 输出的标题/列表/粗体/行内代码）
function renderMarkdown(md) {
  let html = escapeHtml(md);
  // 行内代码
  html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
  // 粗体
  html = html.replace(/\*\*([^\*]+)\*\*/g, "<strong>$1</strong>");
  // 标题
  html = html.replace(/^###\s+(.+)$/gm, "<h3>$1</h3>");
  html = html.replace(/^##\s+(.+)$/gm, "<h2>$1</h2>");
  html = html.replace(/^#\s+(.+)$/gm, "<h2>$1</h2>");
  // 列表 - 简单按行处理
  const lines = html.split("\n");
  const out = [];
  let inUl = false, inOl = false;
  for (let line of lines) {
    const ul = line.match(/^[\-\*]\s+(.*)$/);
    const ol = line.match(/^(\d+)\.\s+(.*)$/);
    if (ul) {
      if (!inUl) { out.push("<ul>"); inUl = true; }
      if (inOl) { out.push("</ol>"); inOl = false; }
      out.push(`<li>${ul[1]}</li>`);
    } else if (ol) {
      if (!inOl) { out.push("<ol>"); inOl = true; }
      if (inUl) { out.push("</ul>"); inUl = false; }
      out.push(`<li>${ol[2]}</li>`);
    } else {
      if (inUl) { out.push("</ul>"); inUl = false; }
      if (inOl) { out.push("</ol>"); inOl = false; }
      if (line.trim() === "") out.push("");
      else if (/^<h\d>/.test(line)) out.push(line);
      else out.push(`<p>${line}</p>`);
    }
  }
  if (inUl) out.push("</ul>");
  if (inOl) out.push("</ol>");
  return out.join("\n");
}

function initDetailPage() {
  _rid = document.querySelector(".detail-page").dataset.rid;
  loadDetail();

  // 播放器键盘
  const audio = document.getElementById("audio");
  document.addEventListener("keydown", (e) => {
    if (e.target.tagName === "TEXTAREA" || e.target.tagName === "INPUT") return;
    if (e.code === "Space") { e.preventDefault(); audio.paused ? audio.play() : audio.pause(); }
    if (e.code === "ArrowRight") audio.currentTime = Math.min((audio.duration || 0), audio.currentTime + 5);
    if (e.code === "ArrowLeft") audio.currentTime = Math.max(0, audio.currentTime - 5);
  });

  // 倍速按钮
  document.querySelectorAll(".speed-btn").forEach(btn => {
    btn.addEventListener("click", () => {
      const s = parseFloat(btn.dataset.speed);
      audio.playbackRate = s;
      document.querySelectorAll(".speed-btn").forEach(b => b.classList.remove("active"));
      btn.classList.add("active");
    });
  });

  // ASR 触发
  document.getElementById("btn-asr").addEventListener("click", async () => {
    if (!confirm("开始 ASR 转录？\n（注意：会消耗 DashScope 配额，重新转录将覆盖之前结果）")) return;
    await api(`/api/recording/${_rid}/asr`, { method: "POST" });
    setTimeout(loadDetail, 600);
  });

  // 分析触发
  document.getElementById("btn-analyze").addEventListener("click", async () => {
    if (!confirm("开始 Claude 知识库分析？\n（每次约消耗 0.1-0.3 美元的 token 成本）")) return;
    await api(`/api/recording/${_rid}/analyze`, { method: "POST" });
    setTimeout(loadDetail, 600);
  });

  // 评价提交
  document.getElementById("eval-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const msg = document.getElementById("eval-msg");
    const payload = {
      wrong_points: e.target.wrong_points.value.trim(),
      improvement: e.target.improvement.value.trim(),
      correct_practice: e.target.correct_practice.value.trim(),
    };
    msg.className = "form-msg"; msg.textContent = "提交中…";
    try {
      const r = await api(`/api/recording/${_rid}/evaluate`, {
        method: "POST", body: JSON.stringify(payload),
      });
      if (!r.ok) {
        const d = await r.json().catch(() => ({}));
        throw new Error(d.error || `HTTP ${r.status}`);
      }
      msg.className = "form-msg ok"; msg.textContent = "✓ 已保存";
      setTimeout(loadDetail, 400);
    } catch (err) {
      msg.className = "form-msg err"; msg.textContent = "保存失败：" + err.message;
    }
  });
}
