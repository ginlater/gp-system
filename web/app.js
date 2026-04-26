// 身美 接诊分析对比 · 前端

const $ = (id) => document.getElementById(id);
const els = {
  before: $('ta-before'),
  mid: $('ta-mid'),
  after: $('ta-after'),
  oursRaw: $('ours-raw'),
  oursRendered: $('ours-rendered'),
  oursStatus: $('ours-status'),
  compRaw: $('comp-raw'),
  compRendered: $('comp-rendered'),
  compStatus: $('comp-status'),
  metaRow: $('metaRow'),
  libStats: $('libStats'),
  demoMeta: $('demoMeta'),
  refSection: $('referenceSection'),
  refBoss: $('refBoss'),
  refComp: $('refComp'),
  analyzeBtn: $('analyzeBtn'),
  loadDemoBtn: $('loadDemoBtn'),
  prevDemoBtn: $('prevDemoBtn'),
  nextDemoBtn: $('nextDemoBtn'),
  clearBtn: $('clearBtn'),
  poolToggle: $('poolToggle'),
  cntWith: $('cnt-with'),
  cntWithout: $('cnt-without'),
};

let currentPool = 'with_boss';   // 'with_boss' | 'without_boss'
let currentDemoIdx = 0;
let demoTotal = 0;
let poolSizes = { with_boss: 0, without_boss: 0 };

// ---- helpers ----
const escHtml = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const formatScore = (s) => (s == null ? '-' : s);

function tryParseJSON(text) {
  if (!text) return null;
  let t = text.trim();
  if (t.startsWith('```')) {
    t = t.replace(/^```(?:json)?\s*\n?/, '').replace(/\n?```\s*$/, '');
  }
  // 取出第一个 { 到最后一个 } 之间的子串，宽松一点
  const i = t.indexOf('{');
  const j = t.lastIndexOf('}');
  if (i >= 0 && j > i) t = t.slice(i, j + 1);
  try { return JSON.parse(t); } catch { return null; }
}

function setStatus(channel, text, cls) {
  const el = channel === 'ours' ? els.oursStatus : els.compStatus;
  el.textContent = text;
  el.className = 'status ' + (cls || '');
}

// ---- init ----
(async () => {
  try {
    const r = await fetch('/api/library/stats').then(r => r.json());
    const stages = r.by_stage || {};
    const pri = r.by_priority || {};
    els.libStats.innerHTML = `逻辑库 <b>${r.total}</b> 条 · 进房间前 ${stages['进房间前']||0} · 中途 ${stages['中途到房间里']||0} · 从房间出来 ${stages['从房间出来']||0} · 跨阶段 ${stages['跨阶段']||0} · <span style="color:#b91c1c">high ${pri.high||0}</span> / <span style="color:#92400e">med ${pri.medium||0}</span> · <span style="color:#374151">${r.model||''}</span>`;
  } catch (e) {
    els.libStats.textContent = '逻辑库加载失败：' + e;
  }
})();

// ---- demo ----
els.loadDemoBtn.onclick = () => loadDemo(currentDemoIdx);
els.prevDemoBtn.onclick = () => loadDemo(currentDemoIdx - 1);
els.nextDemoBtn.onclick = () => loadDemo(currentDemoIdx + 1);

// 池切换
els.poolToggle.querySelectorAll('.pool-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const pool = btn.dataset.pool;
    if (pool === currentPool) return;
    if (poolSizes[pool] === 0) return;
    currentPool = pool;
    currentDemoIdx = 0;
    els.poolToggle.querySelectorAll('.pool-btn').forEach(b => b.classList.toggle('active', b.dataset.pool === pool));
    loadDemo(0);
  });
});

async function loadPoolSizes() {
  try {
    const r = await fetch('/api/demo/pools').then(r => r.json());
    poolSizes = r;
    els.cntWith.textContent = r.with_boss;
    els.cntWithout.textContent = r.without_boss;
    els.poolToggle.querySelector('[data-pool="with_boss"]').disabled = !r.with_boss;
    els.poolToggle.querySelector('[data-pool="without_boss"]').disabled = !r.without_boss;
  } catch (e) {
    // ignore
  }
}
loadPoolSizes();

async function loadDemo(idx) {
  if (poolSizes[currentPool] === 0) {
    els.demoMeta.textContent = '此池没有样例';
    return;
  }
  // 让 idx 循环（负数也允许）
  const total = poolSizes[currentPool];
  const wrapped = ((idx % total) + total) % total;
  els.demoMeta.textContent = '加载中…';
  try {
    const r = await fetch(`/api/demo?pool=${currentPool}&idx=${wrapped}`).then(r => r.json());
    if (r.error) { els.demoMeta.textContent = r.error; return; }
    currentDemoIdx = r.idx;
    demoTotal = r.total;
    els.before.value = r.before;
    els.mid.value = r.mid;
    els.after.value = r.after;
    const poolLabel = r.pool === 'without_boss' ? '⚡无刁姐点评' : '✓有刁姐点评';
    els.demoMeta.textContent = `${poolLabel} · ${r.idx + 1}/${r.total} · ${r.member_name||'-'} · ${r.is_new||''}客 · ${r.deal === 1 ? '已成交' : '未成交'}${r.deal_amount ? ' ' + r.deal_amount : ''} · ${r.staff_name||''}@${r.store||''}`;
    renderReferences(r);
  } catch (e) {
    els.demoMeta.textContent = '加载失败：' + e;
  }
}

function renderReferences(r) {
  if (r.boss_review_actual) {
    els.refBoss.innerHTML = escHtml(r.boss_review_actual);
  } else {
    els.refBoss.innerHTML = '<i style="color:#9ca3af">该条数据无刁姐人工点评</i>';
  }
  const c = r.competitor_actual || {};
  let html = '';
  const fields = [
    ['接诊质量评分', c.score],
    ['评分文字', c.score_text],
    ['综合评分文字', c.score_text_combined],
    ['客户画像标签', c.customer_tags],
    ['未成交分析', c.no_deal_analysis],
    ['成交分析', c.deal_analysis],
    ['下一步行动建议', c.next_action],
    ['AI 总结', c.ai_summary],
  ];
  for (const [k, v] of fields) {
    if (v === null || v === undefined || v === '') continue;
    html += `<div class="field"><b>${escHtml(k)}</b>${escHtml(v)}</div>`;
  }
  els.refComp.innerHTML = html || '<i style="color:#9ca3af">无</i>';
  els.refSection.hidden = false;
}

els.clearBtn.onclick = () => {
  els.before.value = ''; els.mid.value = ''; els.after.value = '';
  els.demoMeta.textContent = '';
  els.refSection.hidden = true;
};

// ---- analyze ----
els.analyzeBtn.onclick = analyze;

let oursBuf = '', compBuf = '';

async function analyze() {
  const before = els.before.value.trim();
  const mid = els.mid.value.trim();
  const after = els.after.value.trim();
  if (!before && !mid && !after) {
    alert('请至少粘贴一个阶段的对话文字');
    return;
  }

  // 重置
  oursBuf = ''; compBuf = '';
  els.oursRaw.textContent = '';
  els.compRaw.textContent = '';
  els.oursRendered.innerHTML = '<div class="placeholder">分析中…</div>';
  els.compRendered.innerHTML = '<div class="placeholder">分析中…</div>';
  setStatus('ours', '分析中', 'running');
  setStatus('comp', '分析中', 'running');
  els.metaRow.hidden = true;
  els.metaRow.innerHTML = '';
  els.analyzeBtn.disabled = true;
  els.analyzeBtn.textContent = '分析中…';

  try {
    const resp = await fetch('/api/analyze', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ before, mid, after }),
    });
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({error: 'http ' + resp.status}));
      throw new Error(err.error || ('http ' + resp.status));
    }
    await consumeSSE(resp);
  } catch (e) {
    setStatus('ours', '错误：' + e.message, 'error');
    setStatus('comp', '错误：' + e.message, 'error');
  } finally {
    els.analyzeBtn.disabled = false;
    els.analyzeBtn.textContent = '开始分析 ▶';
  }
}

async function consumeSSE(resp) {
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      handleEventBlock(block);
    }
  }
  if (buf.trim()) handleEventBlock(buf);
}

function handleEventBlock(block) {
  if (!block.trim()) return;
  let event = '', dataLines = [];
  for (const line of block.split('\n')) {
    if (line.startsWith('event: ')) event = line.slice(7);
    else if (line.startsWith('data: ')) dataLines.push(line.slice(6));
  }
  let payload = {};
  try { payload = JSON.parse(dataLines.join('\n') || '{}'); } catch {}

  if (event === 'meta') {
    renderMeta(payload);
  } else if (event === 'ours_delta') {
    oursBuf += payload.text || '';
    els.oursRaw.textContent = oursBuf;
    els.oursRaw.scrollTop = els.oursRaw.scrollHeight;
    // 增量尝试渲染
    tryProgressiveRender('ours', oursBuf);
  } else if (event === 'comp_delta') {
    compBuf += payload.text || '';
    els.compRaw.textContent = compBuf;
    els.compRaw.scrollTop = els.compRaw.scrollHeight;
    tryProgressiveRender('comp', compBuf);
  } else if (event === 'ours_done') {
    finalizeChannel('ours', payload.full_text || oursBuf, payload.usage);
  } else if (event === 'comp_done') {
    finalizeChannel('comp', payload.full_text || compBuf, payload.usage);
  } else if (event === 'ours_error') {
    setStatus('ours', '错误', 'error');
    els.oursRendered.innerHTML = `<div class="placeholder" style="color:#b91c1c">${escHtml(payload.error || '未知错误')}</div>`;
  } else if (event === 'comp_error') {
    setStatus('comp', '错误', 'error');
    els.compRendered.innerHTML = `<div class="placeholder" style="color:#b91c1c">${escHtml(payload.error || '未知错误')}</div>`;
  }
}

function tryProgressiveRender(channel, buf) {
  // 流式过程中，每隔一段时间尝试渲染部分 JSON。如果解析不出来，保持原 placeholder。
  const obj = tryParseJSON(buf);
  if (obj) renderResult(channel, obj, /*partial*/ true);
}

function finalizeChannel(channel, fullText, usage) {
  const obj = tryParseJSON(fullText);
  if (obj) {
    renderResult(channel, obj, false);
    let label = '完成';
    if (usage) {
      const cached = usage.cache_read_input_tokens || 0;
      const ipt = usage.input_tokens || 0;
      const opt = usage.output_tokens || 0;
      label = `完成 · in ${ipt}${cached ? `(+缓存${cached})` : ''} · out ${opt}`;
    }
    setStatus(channel, label, 'done');
  } else {
    setStatus(channel, '完成（JSON 解析失败，见原始文本）', 'error');
    const el = channel === 'ours' ? els.oursRendered : els.compRendered;
    el.innerHTML = '<div class="placeholder" style="color:#b91c1c">模型输出未能解析为 JSON，请查看下方原始文本</div>';
  }
}

function renderMeta(p) {
  const matched = p.matched_logics || [];
  // 按命中阶段聚合
  const byStage = {};
  for (const m of matched) (byStage[m.hit_in] ||= []).push(m);

  let html = `<div class="meta-summary">🎯 信号预匹配：在你输入的对话中检测到 <b>${matched.length}</b> 条 trigger_signal 命中（库共 ${p.library_size} 条逻辑） · 模型 <code>${escHtml(p.model||'')}</code></div>`;
  if (matched.length) {
    html += '<div class="match-table"><table><thead><tr><th>逻辑ID</th><th>命中阶段</th><th>命中信号</th><th>优先级</th><th>逻辑摘要</th></tr></thead><tbody>';
    const cap = matched.slice(0, 60);
    for (const m of cap) {
      html += `<tr>
        <td><code>${escHtml(m.logic_id)}</code></td>
        <td>${escHtml(m.hit_in)}</td>
        <td><b>${escHtml(m.signal)}</b></td>
        <td><span class="pill ${m.priority||'medium'}">${escHtml(m.priority||'medium')}</span></td>
        <td>${escHtml(m.logic_excerpt||'')}</td>
      </tr>`;
    }
    if (matched.length > cap.length) html += `<tr><td colspan="5" style="text-align:center;color:#6b7280">… 还有 ${matched.length - cap.length} 条</td></tr>`;
    html += '</tbody></table></div>';
  } else {
    html += '<div style="color:#9ca3af;font-size:12px;font-style:italic">没有命中任何已知 trigger_signal —— 可能是新场景，分析将完全依赖模型自身判断 + 逻辑库的整体语义</div>';
  }
  els.metaRow.innerHTML = html;
  els.metaRow.hidden = false;
}

function renderResult(channel, obj, isPartial) {
  const target = channel === 'ours' ? els.oursRendered : els.compRendered;
  let html = '';

  // 综合评分
  if (obj.score != null || obj.score_text) {
    html += `<div class="score-block">
      <div class="score-num">${formatScore(obj.score)}</div>
      <div class="score-text">${escHtml(obj.score_text || '')}</div>
    </div>`;
  }

  // 三阶段评分（仅 ours）
  if (obj.stage_assessments) {
    const s = obj.stage_assessments;
    html += '<div class="stages-row">';
    for (const [k, label] of [['before_room','进房间前'],['mid_room','中途'],['after_room','从房间出来']]) {
      const x = s[k] || {};
      html += `<div class="stage-card">
        <div class="stage-h">${label}</div>
        <div class="stage-score">${formatScore(x.score)}<span style="font-size:11px;color:#9ca3af">/10</span></div>
        <div class="stage-comment">${escHtml(x.comment || '')}</div>
      </div>`;
    }
    html += '</div>';
  }

  // 客户画像
  if (Array.isArray(obj.customer_tags) && obj.customer_tags.length) {
    html += `<div class="block"><h3>客户画像标签</h3><div class="tags">${obj.customer_tags.map(t => `<span class="tag">${escHtml(t)}</span>`).join('')}</div></div>`;
  }

  // 成交分析
  if (obj.deal_likelihood || obj.deal_or_no_deal_analysis) {
    let dlHtml = '';
    if (obj.deal_likelihood) dlHtml = `<span class="pill ${obj.deal_likelihood}">${escHtml(obj.deal_likelihood)}</span>`;
    html += `<div class="block"><h3>成交分析 ${dlHtml}</h3><p>${escHtml(obj.deal_or_no_deal_analysis || '')}</p></div>`;
  }

  // 下一步行动
  if (Array.isArray(obj.next_actions) && obj.next_actions.length) {
    html += '<div class="block next-actions"><h3>下一步行动建议</h3><ul>';
    for (const a of obj.next_actions) {
      if (typeof a === 'string') {
        html += `<li>${escHtml(a)}</li>`;
      } else if (a && typeof a === 'object') {
        html += `<li><b>${escHtml(a.action || '')}</b>${a.script ? `<div class="script">『${escHtml(a.script)}』</div>` : ''}</li>`;
      }
    }
    html += '</ul></div>';
  }

  // 关键洞察（仅 ours）
  if (Array.isArray(obj.key_insights) && obj.key_insights.length) {
    html += '<div class="block insights"><h3>🌟 关键洞察 — 命中刁姐逻辑</h3>';
    for (const ins of obj.key_insights) {
      const lid = ins.logic_id || '';
      html += `<div class="insight">
        <div class="ins-head"><span class="lid">${escHtml(lid)}</span>${escHtml(ins.stage || '')}</div>
        ${ins.signal_quoted ? `<div class="quote">"${escHtml(ins.signal_quoted)}"</div>` : ''}
        ${ins.boss_view ? `<div class="ins-view">${escHtml(ins.boss_view)}</div>` : ''}
      </div>`;
    }
    html += '</div>';
  }

  // 风险预警（仅 ours）
  if (Array.isArray(obj.risk_warnings) && obj.risk_warnings.length) {
    html += '<div class="block warnings"><h3>⚠️ 风险预警 — 踩到 anti_pattern</h3>';
    for (const w of obj.risk_warnings) {
      if (typeof w === 'string') {
        html += `<div class="warn">${escHtml(w)}</div>`;
      } else {
        html += `<div class="warn">
          ${w.logic_id ? `<b>${escHtml(w.logic_id)}</b>：` : ''}${escHtml(w.what_happened || '')}
          ${w.consequence ? `<div class="conseq">→ ${escHtml(w.consequence)}</div>` : ''}
        </div>`;
      }
    }
    html += '</div>';
  }

  // 一句话刁姐点评（仅 ours）
  if (obj.boss_summary) {
    html += `<div class="block boss-quote"><blockquote>${escHtml(obj.boss_summary)}</blockquote></div>`;
  }
  if (obj.ai_summary) {
    html += `<div class="block"><h3>AI 总结</h3><p>${escHtml(obj.ai_summary)}</p></div>`;
  }

  if (!html) {
    html = '<div class="placeholder">解析中…</div>';
  }
  target.innerHTML = html;
}
