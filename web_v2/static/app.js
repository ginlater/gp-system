/* ====== 工牌接诊分析系统 v2 (session-centric) ====== */

const PREFIX = (window.URL_PREFIX || "").replace(/\/$/, "");
function U(path) { return PREFIX + path; }

function escapeHtml(str) {
  if (str == null) return "";
  return String(str)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

const STATUS_LABEL = {
  pending: { cls: "",     label: "待处理" },
  running: { cls: "run",  label: "进行中…" },
  done:    { cls: "ok",   label: "已完成" },
  failed:  { cls: "fail", label: "失败" },
};
function chip(status) {
  const c = STATUS_LABEL[status] || STATUS_LABEL.pending;
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
let _allSessions = [];

async function loadSessions() {
  const r = await api("/api/sessions");
  const data = await r.json();
  _allSessions = data.sessions || [];
  document.getElementById("sess-count").textContent = _allSessions.length;
  renderSessions();
}

function renderSessions() {
  const list = document.getElementById("sess-list");
  const q = (document.getElementById("filter-input").value || "").trim().toLowerCase();
  const filtered = _allSessions.filter(s => {
    if (!q) return true;
    return [s.advisor, s.customer].some(x => x && x.toLowerCase().includes(q));
  });
  if (!filtered.length) {
    list.innerHTML = `<div class="empty">${_allSessions.length ? "没有匹配的接诊" : "暂无接诊。点击右上角「上传录音」开始。"}</div>`;
    return;
  }
  list.innerHTML = filtered.map(s => {
    let scoreHtml = "";
    if (s.analysis_scores) {
      try {
        const sc = typeof s.analysis_scores === "string" ? JSON.parse(s.analysis_scores) : s.analysis_scores;
        const ov = sc.overall || 0;
        scoreHtml = `<span class="score-badge" style="background:${scoreColor(ov)}">${ov.toFixed(1)}</span>`;
      } catch(e) {}
    }
    const asrLabel = `${s.asr_done_count || 0}/${s.recording_count || 0} 已转录` +
      (s.asr_running_count ? `（${s.asr_running_count} 进行中）` : "") +
      (s.asr_failed_count ? `（${s.asr_failed_count} 失败）` : "");
    return `
      <div class="rec-card">
        <a class="rec-main" href="${U('/session/' + s.id)}">
          <div class="rec-line1">
            <span class="advisor">${escapeHtml(s.advisor || "未填顾问")}</span>
            <span class="vs">←</span>
            <span class="customer">${escapeHtml(s.customer || "未填顾客")}</span>
          </div>
          <div class="rec-line2">
            <span>📅 ${escapeHtml(s.service_date || "日期未知")}</span>
            <span>🎵 ${s.recording_count || 0} 段录音</span>
            <span>🎙️ ${asrLabel}</span>
          </div>
        </a>
        <div class="rec-tags">
          ${scoreHtml}
          <span class="chip-label">AI 分析</span> ${chip(s.analysis_status)}
          <span class="chip-label">点评</span>
          ${s.has_evaluation ? `<span class="chip has-eval">✓ 已点评</span>` : `<span class="chip">未点评</span>`}
        </div>
      </div>`;
  }).join("");
}

function initIndexPage() {
  loadSessions();
  setInterval(loadSessions, 10000);  // 每 10s 自动刷新（后台流水线进度）

  document.getElementById("filter-input").addEventListener("input", renderSessions);

  document.getElementById("btn-scan").addEventListener("click", async (e) => {
    e.target.disabled = true; e.target.textContent = "同步中…";
    try {
      const r = await api("/api/scan", { method: "POST" });
      const d = await r.json();
      alert(`同步完成，新增 ${d.added} 条录音\n（后台正在自动转录与分析，稍后刷新即可看到）`);
      await loadSessions();
    } catch (err) { alert("同步失败：" + err.message); }
    finally { e.target.disabled = false; e.target.textContent = "🔄 同步云端"; }
  });

  const panel = document.getElementById("upload-panel");
  document.getElementById("btn-show-upload").addEventListener("click", () => panel.classList.toggle("hidden"));
  document.getElementById("btn-cancel-upload").addEventListener("click", () => panel.classList.add("hidden"));

  // ── 文件 / 文件夹选择 ──
  const AUDIO_EXT_RE = /\.(mp3|wav|m4a|flac|aac|ogg|opus)$/i;
  let _pickedFiles = [];

  const pickFiles = document.getElementById("pick-files");
  const pickDir   = document.getElementById("pick-dir");
  const clearBtn  = document.getElementById("btn-clear-files");
  const pickInfo  = document.getElementById("picked-info");
  const pickList  = document.getElementById("picked-list");

  function fmtSize(b) {
    if (b >= 1024 * 1024 * 1024) return (b / 1024 / 1024 / 1024).toFixed(2) + " GB";
    if (b >= 1024 * 1024) return (b / 1024 / 1024).toFixed(1) + " MB";
    if (b >= 1024) return (b / 1024).toFixed(0) + " KB";
    return b + " B";
  }

  function refreshPicked(skipped) {
    if (!_pickedFiles.length) {
      pickInfo.style.display = "none";
      pickList.style.display = "none";
      clearBtn.style.display = "none";
      return;
    }
    const total = _pickedFiles.reduce((a, f) => a + f.size, 0);
    const overLimit = total > 1024 * 1024 * 1024;
    pickInfo.style.display = "";
    pickInfo.innerHTML = `已选 <b>${_pickedFiles.length}</b> 个音频，共 <b>${fmtSize(total)}</b>` +
      (skipped ? `（已跳过 ${skipped} 个非音频文件）` : "") +
      (overLimit ? ` <span style="color:#c63838">⚠️ 超过 1GB 上限，请分批</span>` : "");
    clearBtn.style.display = "";
    // 只展示前 30 行预览，太多了折叠
    pickList.style.display = "";
    const show = _pickedFiles.slice(0, 30);
    pickList.innerHTML = show.map(f =>
      `<li>${escapeHtml(f.name)} <span class="muted">· ${fmtSize(f.size)}</span></li>`
    ).join("") + (_pickedFiles.length > show.length
      ? `<li class="muted">…还有 ${_pickedFiles.length - show.length} 个文件</li>` : "");
  }

  function setPickedFromInput(input) {
    const all = Array.from(input.files || []);
    const audio = all.filter(f => AUDIO_EXT_RE.test(f.name));
    const skipped = all.length - audio.length;
    _pickedFiles = audio;
    refreshPicked(skipped);
  }

  pickFiles.addEventListener("change", () => { setPickedFromInput(pickFiles); pickDir.value = ""; });
  pickDir.addEventListener("change",   () => { setPickedFromInput(pickDir);   pickFiles.value = ""; });
  clearBtn.addEventListener("click", () => {
    _pickedFiles = []; pickFiles.value = ""; pickDir.value = ""; refreshPicked();
  });

  document.getElementById("upload-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const form = e.target;
    const msg = document.getElementById("upload-msg");
    if (!_pickedFiles.length) {
      msg.className = "form-msg err"; msg.textContent = "请先选择音频文件或文件夹";
      return;
    }
    const total = _pickedFiles.reduce((a, f) => a + f.size, 0);
    if (total > 1024 * 1024 * 1024) {
      msg.className = "form-msg err";
      msg.textContent = `选中总大小 ${fmtSize(total)} 超过 1GB 上限，请分批上传`;
      return;
    }
    const fd = new FormData();
    _pickedFiles.forEach(f => fd.append("files", f, f.name));
    // 透传可选元数据字段
    ["advisor", "customer", "recorded_at", "duration_label"].forEach(k => {
      const el = form.elements[k];
      if (el && el.value) fd.append(k, el.value);
    });
    msg.className = "form-msg";
    msg.textContent = `上传中（${_pickedFiles.length} 个文件 · ${fmtSize(total)}），请耐心等待…`;
    const submitBtn = form.querySelector("button[type=submit]");
    submitBtn.disabled = true;
    try {
      const r = await fetch(U("/api/upload"), { method: "POST", body: fd });
      if (r.status === 401) { window.location = U("/login"); return; }
      if (r.status === 413) { throw new Error("超出服务器单次上传上限 1GB，请分批"); }
      if (!r.ok) {
        const d = await r.json().catch(() => ({}));
        throw new Error(d.error || `HTTP ${r.status}`);
      }
      const d = await r.json();
      const sids = [...new Set((d.created || []).map(x => x.session_id))];
      msg.className = "form-msg ok";
      msg.textContent = `上传成功，共 ${d.created.length} 段，归入 ${sids.length} 次接诊。后台已自动开始转录…`;
      form.reset();
      _pickedFiles = []; refreshPicked();
      await loadSessions();
      if (sids.length === 1) {
        setTimeout(() => { window.location = U(`/session/${sids[0]}`); }, 1200);
      }
    } catch (err) {
      msg.className = "form-msg err"; msg.textContent = "上传失败：" + err.message;
    } finally { submitBtn.disabled = false; }
  });
}

/* ============ 详情页（Sales Workbench）============ */

// ─── 全局播放状态 ────────────────────────────────────────────────────
let _sid = null;
let _recordings = [];       // 按段序排序后的录音数组
let _currentSegIdx = 0;     // 当前段下标（0-based）
let _modelInfo = null;      // { models: [...], default: "..." }
let _currentAnalysisStatus = null;
let _currentSessionModel = null;

function scoreColor(v) {
  if (v >= 8) return "#2e9a55";
  if (v >= 6) return "#d39d00";
  if (v >= 4) return "#e07020";
  return "#c63838";
}

// ─── 时间戳 Tag HTML ─────────────────────────────────────────────────
function tsTag(ts) {
  const fmtSec = s => {
    const m = Math.floor(s / 60), sec = Math.floor(s % 60);
    return m > 0 ? `${m}′${String(sec).padStart(2,'0')}″` : `${Math.floor(s)}s`;
  };
  return `<span class="ts-tag" data-seg="${ts.segment}" data-start="${ts.startSec}"
    title="点击跳转播放">🕐 段${ts.segment} · ${fmtSec(ts.startSec)}–${fmtSec(ts.endSec)}
    <span class="ts-play-icon">▶</span></span>`;
}

// ─── 跳转播放 ────────────────────────────────────────────────────────
function jumpToTimestamp(segment, startSec) {
  const idx = segment - 1;  // 段编号从 1 开始
  const audio = document.getElementById("main-audio");
  if (!audio) return;

  if (idx !== _currentSegIdx && _recordings[idx]) {
    // 切换段
    _currentSegIdx = idx;
    audio.src = _recordings[idx].audio_url;
    const sel = document.getElementById("seg-select");
    if (sel) sel.value = idx;
    updateSegmentInfo(idx);
    audio.addEventListener("loadedmetadata", () => {
      audio.currentTime = startSec;
      audio.play().catch(() => {});
    }, { once: true });
  } else {
    audio.currentTime = startSec;
    audio.play().catch(() => {});
  }

  // 高亮短暂闪烁
  document.querySelectorAll(".ts-tag").forEach(t => {
    if (Number(t.dataset.seg) === segment && Math.abs(Number(t.dataset.start) - startSec) < 2) {
      t.classList.add("ts-flash");
      setTimeout(() => t.classList.remove("ts-flash"), 1500);
    }
  });
}

function updateSegmentInfo(idx) {
  const rec = _recordings[idx];
  if (!rec) return;
  const dur = document.getElementById("seg-duration");
  if (dur) dur.textContent = rec.duration_label ? `⏱ ${rec.duration_label}` : "";
  const txEl = document.getElementById("transcript-text");
  if (txEl) txEl.textContent = rec.asr_transcript || "(暂无转录)";
}

// ─── 引用到老板点评 ──────────────────────────────────────────────────
function quoteToReview(text, taId) {
  const ta = document.getElementById(taId || "ta-wrong");
  if (!ta) return;
  const trimmed = text.trim();
  const quote = ta.value ? `\n\n> ${trimmed}` : `> ${trimmed}`;
  ta.value += quote;
  ta.focus();
  // 滚到点评区
  document.getElementById("review-card").scrollIntoView({ behavior: "smooth", block: "start" });
}

// ─── 模型选择器 ──────────────────────────────────────────────────────
async function loadModels() {
  try {
    const r = await api("/api/models");
    _modelInfo = await r.json();
  } catch (e) {
    _modelInfo = { models: [], default: "" };
  }
  renderModelSelect();
}

function renderModelSelect() {
  const sel = document.getElementById("model-select");
  if (!sel || !_modelInfo) return;
  const cur = _currentSessionModel || _modelInfo.default;
  const opts = (_modelInfo.models || []).map(m => {
    const sel = m.id === cur ? "selected" : "";
    const dis = m.disabled ? "disabled" : "";
    const tail = m.disabled ? "（未配置）" : "";
    return `<option value="${escapeHtml(m.id)}" ${sel} ${dis}>${escapeHtml(m.label)}${tail}</option>`;
  });
  if (!opts.length) opts.push(`<option>无可用模型</option>`);
  sel.innerHTML = opts.join("");
  sel.disabled = false;
  updateReanalyzeBtn();
}

function updateReanalyzeBtn() {
  const btn = document.getElementById("btn-reanalyze");
  if (!btn) return;
  // 分析进行中时禁用
  const running = _currentAnalysisStatus === "running";
  btn.disabled = running;
  btn.textContent = running ? "分析中…" : "重新分析";
}

// ─── 主加载函数 ──────────────────────────────────────────────────────
async function loadSession() {
  const r = await api(`/api/session/${_sid}`);
  const s = await r.json();

  _currentAnalysisStatus = s.analysis_status;
  _currentSessionModel = s.analysis_model || null;
  // 同步下拉框选中（不覆盖用户手动改动：仅在用户尚未操作过时跟随）
  if (_modelInfo && _currentSessionModel) {
    const sel = document.getElementById("model-select");
    if (sel && !sel.dataset.touched) sel.value = _currentSessionModel;
  }
  updateReanalyzeBtn();

  const recs = s.recordings || [];
  _recordings = recs;

  // ── 折叠信息面板 ──
  const infoContent = document.getElementById("info-content");
  if (infoContent) {
    const done = recs.filter(r => r.asr_status === "done").length;
    const modelLabel = (_modelInfo && _modelInfo.models || [])
      .find(m => m.id === s.analysis_model);
    const modelTxt = s.analysis_model
      ? `🤖 ${modelLabel ? modelLabel.label : s.analysis_model}`
      : `🤖 默认模型`;
    infoContent.innerHTML = `
      <span>🎵 ${recs.length} 段录音 · ${done}/${recs.length} 已转录</span>
      <span>AI 状态：${chip(s.analysis_status)}</span>
      <span>${modelTxt}</span>`;
  }

  // ── 播放器段选择器 ──
  const sel = document.getElementById("seg-select");
  if (sel && recs.length) {
    const prevIdx = _currentSegIdx;
    sel.innerHTML = recs.map((r, i) => {
      const label = `第 ${i + 1} 段${r.duration_label ? " · " + r.duration_label : ""}${r.recorded_at ? " · " + r.recorded_at.slice(11, 16) : ""}`;
      return `<option value="${i}" ${i === prevIdx ? "selected" : ""}>${escapeHtml(label)}</option>`;
    }).join("");
    const audio = document.getElementById("main-audio");
    if (audio && !audio.src && recs[_currentSegIdx]) {
      audio.src = recs[_currentSegIdx].audio_url;
    }
    updateSegmentInfo(_currentSegIdx);
  }

  // ── 评分卡 ──
  renderWorkbenchScore(s.analysis_scores);

  // ── AI 复盘卡片 ──
  const aiSection = document.getElementById("ai-section");
  if (s.analysis_status === "done" && s.parsed_analysis) {
    aiSection.style.display = "";
    renderParsedAnalysis(s.parsed_analysis);
  } else if (s.analysis_status === "running") {
    aiSection.style.display = "";
    document.getElementById("overall-text").textContent = "AI 复盘正在生成，预计 1-3 分钟…";
  } else if (s.analysis_status === "failed") {
    aiSection.style.display = "";
    document.getElementById("overall-text").textContent = "分析失败：" + (s.analysis_error || "未知");
  } else {
    aiSection.style.display = "none";
  }

  // ── 老板点评 ──
  const ev = s.evaluation;
  const rc = document.getElementById("review-chip");
  if (ev) {
    rc.className = "chip has-eval review-chip";
    rc.textContent = "✓ 已填写";
    document.getElementById("ta-wrong").value = ev.wrong_points || "";
    document.getElementById("ta-improve").value = ev.improvement || "";
    document.getElementById("ta-correct").value = ev.correct_practice || "";
  } else {
    rc.className = "chip review-chip"; rc.textContent = "未填写";
  }

  // ── 轮询 ──
  const polling = s.analysis_status === "running" ||
    recs.some(r => ["running", "pending"].includes(r.asr_status));
  if (polling) setTimeout(loadSession, 6000);
}

// ─── 评分卡（工作台版）────────────────────────────────────────────────
const DIM_DEFINITIONS = {
  "档案掌握与破冰":     "接诊前对顾客历史数据的掌握程度，及开场破冰的有效性",
  "情感连接与共情":     "对顾客当下情绪与生活状态的捕捉，和共情回应的深度",
  "今日方案铺垫":       "进房前对今日项目的必要性和价值做了多少预先铺垫",
  "需求洞察与挖掘":     "对顾客显性与隐性需求主动挖掘的深度和有效性",
  "专业讲解与检测解读": "皮测/身体检测解读的专业度和顾客的理解接受程度",
  "节奏与情绪维护":     "全程节奏掌控、专业内容与闲聊的比例管理",
  "效果确认与价值放大": "出房时对本次效果的主动确认，及对既往投入价值的放大",
  "成交引导":           "方案推进、报价时机和成交动作的质量",
  "异议处理":           "对顾客价格异议、犹豫、拒绝的处理方式",
  "客情收尾与售后交代": "成交后交代完整性、下次预约、客情维护动作",
};
const STAGE_DIMS_MAP = {
  "进房前": { key: "pre_room",  dims: ["档案掌握与破冰", "情感连接与共情", "今日方案铺垫"] },
  "房中":   { key: "in_room",   dims: ["需求洞察与挖掘", "专业讲解与检测解读", "节奏与情绪维护"] },
  "房后":   { key: "post_room", dims: ["效果确认与价值放大", "成交引导", "异议处理", "客情收尾与售后交代"] },
};

function renderWorkbenchScore(scoresJson) {
  const sec = document.getElementById("score-section");
  if (!scoresJson) { sec.style.display = "none"; return; }
  let sc;
  try { sc = typeof scoresJson === "string" ? JSON.parse(scoresJson) : scoresJson; } catch { sec.style.display = "none"; return; }
  sec.style.display = "";

  const ov = sc.overall || 0;
  const circle = document.getElementById("score-big-circle");
  circle.textContent = ov.toFixed(1);
  circle.style.background = scoreColor(ov);
  document.getElementById("sc-highlights").textContent = sc.highlights || "";
  document.getElementById("sc-weaknesses").textContent = sc.weaknesses || "";

  const dims = sc.dimensions || {};
  const stages = sc.stages || {};
  const stagesEl = document.getElementById("score-stages-new");
  stagesEl.innerHTML = Object.entries(STAGE_DIMS_MAP).map(([name, { key, dims: stageDims }]) => {
    const stageScore = stages[key] || 0;
    const rows = stageDims.map((dim, i) => {
      const dv = dims[dim];
      const val = (dv && typeof dv === "object") ? dv.score : (dv || 0);
      const uid = `wdim-${key}-${i}`;
      const hasDetail = dv && typeof dv === "object";
      const def = DIM_DEFINITIONS[dim] || "";
      return `
        <div class="wdim-row ${hasDetail ? "wdim-clickable" : ""}" onclick="${hasDetail ? `wToggle('${uid}')` : ''}">
          <div class="wdim-left">
            <span class="wdim-name">${escapeHtml(dim)}</span>
            ${hasDetail ? `<span class="wdim-arrow" id="${uid}-arrow">▶</span>` : ""}
          </div>
          <div class="wdim-bar-wrap">
            <div class="wdim-bar-bg"><div class="wdim-bar-fill" style="width:${val*10}%;background:${scoreColor(val)}"></div></div>
            <span class="wdim-val" style="color:${scoreColor(val)}">${val}</span>
          </div>
        </div>
        ${hasDetail ? `<div class="wdim-panel" id="${uid}" style="display:none">
          ${def ? `<div class="wdim-detail"><b>📌 定义</b> ${escapeHtml(def)}</div>` : ""}
          ${dv.evaluation ? `<div class="wdim-detail"><b>📊 本次</b> ${escapeHtml(dv.evaluation)}</div>` : ""}
          ${dv.improvement ? `<div class="wdim-detail"><b>💡 改进</b> ${escapeHtml(dv.improvement)}</div>` : ""}
        </div>` : ""}`;
    }).join("");
    return `<div class="wstage-block">
      <div class="wstage-head">
        <span class="wstage-name">${name}</span>
        <span class="wstage-score" style="color:${scoreColor(stageScore)}">${stageScore.toFixed(1)}</span>
      </div>${rows}</div>`;
  }).join("");
}

function wToggle(uid) {
  const p = document.getElementById(uid);
  const a = document.getElementById(uid + "-arrow");
  if (!p) return;
  const open = p.style.display !== "none";
  p.style.display = open ? "none" : "block";
  if (a) a.textContent = open ? "▶" : "▼";
}

// ─── AI 复盘卡片渲染 ─────────────────────────────────────────────────

function renderParsedAnalysis(parsed) {
  // 总体评价
  const overallEl = document.getElementById("overall-text");
  if (overallEl) overallEl.textContent = parsed.overall || "";

  // 做得好
  const sl = document.getElementById("strengths-list");
  const sc = document.getElementById("strengths-count");
  if (sl && parsed.strengths) {
    if (sc) sc.textContent = `${parsed.strengths.length} 条`;
    sl.innerHTML = parsed.strengths.map((item, idx) => `
      <div class="ai-card ai-card-good" id="sc-${idx}">
        <div class="aic-head">
          <span class="aic-icon">✅</span>
          <span class="aic-title">${escapeHtml(item.title)}</span>
          <div class="aic-ts">${(item.timestamps || []).map(ts => tsTag(ts)).join(" ")}</div>
        </div>
        <div class="aic-body">${escapeHtml(item.content)}</div>
        <div class="aic-foot">
          <button class="quote-btn"
            data-quote="${escapeHtml(item.title + '：' + item.content)}"
            data-review-target="ta-wrong">📌 引用</button>
        </div>
      </div>`).join("");
  }

  // 做错/遗漏
  const wl = document.getElementById("weaknesses-list");
  const wc = document.getElementById("weaknesses-count");
  if (wl && parsed.weaknesses) {
    if (wc) wc.textContent = `${parsed.weaknesses.length} 条`;
    wl.innerHTML = parsed.weaknesses.map((item, idx) => `
      <div class="ai-card ai-card-bad" id="wk-${idx}">
        <div class="aic-head">
          <span class="aic-icon">❌</span>
          <span class="aic-title">${escapeHtml(item.title)}</span>
          <div class="aic-ts">${(item.timestamps || []).map(ts => tsTag(ts)).join(" ")}</div>
        </div>
        ${item.problem ? `<div class="aic-field"><span class="aic-field-label">问题</span><span>${escapeHtml(item.problem)}</span></div>` : ""}
        ${item.risk    ? `<div class="aic-field"><span class="aic-field-label risk">风险</span><span>${escapeHtml(item.risk)}</span></div>` : ""}
        ${item.correctAction ? `<div class="aic-field"><span class="aic-field-label fix">✓ 做法</span><span>${escapeHtml(item.correctAction)}</span></div>` : ""}
        <div class="aic-foot">
          <button class="quote-btn"
            data-quote="${escapeHtml('❌ ' + item.title + '\n问题：' + item.problem + '\n正确做法：' + item.correctAction)}"
            data-review-target="ta-wrong">📌 引用到错的点</button>
        </div>
      </div>`).join("");
  }

  // 阶段检查
  const sg = document.getElementById("stage-grid");
  if (sg && parsed.stageCheck) {
    const stages = [
      { key: "before", label: "进房前" },
      { key: "during", label: "房中" },
      { key: "after",  label: "房后" },
    ];
    sg.innerHTML = stages.map(({ key, label }) => {
      const items = parsed.stageCheck[key] || [];
      const rows = items.map(it => `
        <div class="stage-item stage-item-${it.type}">
          <span class="stage-dot ${it.type}"></span>
          <span>${escapeHtml(it.text)}</span>
        </div>`).join("");
      return `<div class="stage-col"><div class="stage-col-head">${label}</div>${rows}</div>`;
    }).join("");
  }

  // 关键建议
  const sugl = document.getElementById("suggestions-list");
  if (sugl && parsed.keySuggestions) {
    sugl.innerHTML = parsed.keySuggestions.map((item, idx) => `
      <div class="ai-card ai-card-suggest" id="sg-${idx}">
        <div class="aic-head">
          <span class="aic-icon">💡</span>
          <span class="aic-title">${escapeHtml(item.title)}</span>
          <div class="aic-ts">${(item.timestamps || []).map(ts => tsTag(ts)).join(" ")}</div>
        </div>
        <div class="aic-body">${escapeHtml(item.body)}</div>
        <div class="aic-foot">
          <button class="quote-btn"
            data-quote="${escapeHtml('💡 ' + item.title + '\n' + item.body)}"
            data-review-target="ta-improve">📌 引用到建议</button>
        </div>
      </div>`).join("");
  }

  // 时间戳点击事件委托
  document.querySelectorAll(".ts-tag").forEach(tag => {
    tag.addEventListener("click", () => {
      jumpToTimestamp(Number(tag.dataset.seg), Number(tag.dataset.start));
    });
  });
}

// ─── initSessionPage ────────────────────────────────────────────────
function initSessionPage() {
  _sid = document.querySelector(".workbench").dataset.sid;
  // 模型列表先于 session 加载，确保 session 拿到时下拉已就绪
  loadModels().then(loadSession);

  // 模型下拉：用户改动后标记 touched，loadSession 不再覆盖
  const modelSel = document.getElementById("model-select");
  if (modelSel) {
    modelSel.addEventListener("change", () => { modelSel.dataset.touched = "1"; });
  }

  // 重新分析
  const reanalyzeBtn = document.getElementById("btn-reanalyze");
  if (reanalyzeBtn) {
    reanalyzeBtn.addEventListener("click", async () => {
      const sel = document.getElementById("model-select");
      const model = sel ? sel.value : "";
      if (!model) return;
      if (!confirm(`确认用「${sel.selectedOptions[0]?.text || model}」重新分析这次接诊？\n（约 1-3 分钟）`)) return;
      reanalyzeBtn.disabled = true;
      reanalyzeBtn.textContent = "提交中…";
      try {
        const r = await api(`/api/session/${_sid}/analyze`, {
          method: "POST", body: JSON.stringify({ model }),
        });
        if (!r.ok) {
          const d = await r.json().catch(() => ({}));
          throw new Error(d.error || `HTTP ${r.status}`);
        }
        _currentAnalysisStatus = "running";
        updateReanalyzeBtn();
        // 立即触发一次刷新与轮询
        setTimeout(loadSession, 600);
      } catch (err) {
        alert("启动失败：" + err.message);
        reanalyzeBtn.disabled = false;
        reanalyzeBtn.textContent = "重新分析";
      }
    });
  }

  // ⓘ 按钮切换详情
  const infoPnl = document.getElementById("info-panel");
  document.getElementById("btn-info").addEventListener("click", () => {
    infoPnl.classList.toggle("hidden");
  });

  // 段切换
  document.getElementById("seg-select").addEventListener("change", e => {
    const idx = Number(e.target.value);
    _currentSegIdx = idx;
    const audio = document.getElementById("main-audio");
    if (audio && _recordings[idx]) {
      const t = audio.currentTime;
      audio.src = _recordings[idx].audio_url;
      audio.load();
      // 不自动播放，保持暂停
    }
    updateSegmentInfo(idx);
  });

  // 语音输入
  if (window.SpeechRecognition || window.webkitSpeechRecognition) {
    document.querySelectorAll(".voice-btn").forEach(btn => {
      btn.addEventListener("click", () => {
        const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
        const rec = new SR();
        rec.lang = "zh-CN";
        rec.continuous = false;
        rec.interimResults = false;
        const ta = document.getElementById(btn.dataset.target);
        btn.textContent = "🔴";
        rec.onresult = e => { ta.value += e.results[0][0].transcript; };
        rec.onend = () => { btn.textContent = "🎙️"; };
        rec.onerror = () => { btn.textContent = "🎙️"; };
        rec.start();
      });
    });
  }

  // 📌 引用按钮 — 事件委托，处理所有 .quote-btn
  document.querySelector(".workbench").addEventListener("click", (e) => {
    const btn = e.target.closest(".quote-btn");
    if (!btn) return;

    let text = "";
    if (btn.dataset.quoteFrom) {
      // data-quote-from="element-id" → 读该元素的文字
      const src = document.getElementById(btn.dataset.quoteFrom);
      text = src ? (src.textContent || src.value || "") : "";
    } else if (btn.dataset.quote) {
      // data-quote="已经转义的文字"
      text = btn.dataset.quote;
    }
    if (!text.trim()) return;

    const taId = btn.dataset.reviewTarget || "ta-wrong";
    quoteToReview(text, taId);
  });

  // 评价提交
  document.getElementById("eval-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const msg = document.getElementById("eval-msg");
    const payload = {
      wrong_points: document.getElementById("ta-wrong").value.trim(),
      improvement:  document.getElementById("ta-improve").value.trim(),
      correct_practice: document.getElementById("ta-correct").value.trim(),
    };
    msg.className = "form-msg"; msg.textContent = "提交中…";
    try {
      const r = await api(`/api/session/${_sid}/evaluate`, {
        method: "POST", body: JSON.stringify(payload),
      });
      if (!r.ok) { const d = await r.json().catch(() => ({})); throw new Error(d.error || `HTTP ${r.status}`); }
      msg.className = "form-msg ok"; msg.textContent = "✓ 已保存";
      setTimeout(loadSession, 400);
    } catch (err) {
      msg.className = "form-msg err"; msg.textContent = "保存失败：" + err.message;
    }
  });
}
