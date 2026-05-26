# GP 系统最高优先级优化分析

## 当前系统健康度

| 状态 | 数量 | 占比 |
|------|------|------|
| done | 32 | 23% |
| failed | 58 | 41% |
| pending（ASR 已完成但未分析） | 51 | 36% |

**系统实际完成率只有 23%。大量 session 要么卡死要么从未触发。**

---

## 优化 1（P0）：拆分 Chunk [T1, T2]，消除"结果为空"级联雪崩

### 问题

当前 `CALL_CHUNKS[1] = [["T1", "T2"], ["T3", "T4"]]`，T1（顾客画像）和 T2（可攻破痛点）合在一个 LLM 调用里输出。

**实际失败数据**：
- T1 失败 7 次，其中 5 次是"结果为空"（71%）
- T2 失败 8 次，其中 6 次是"结果为空"（75%）
- 对比同 Call 的 T3、T4（独立 chunk）：各只失败 1 次

### 根因

`_call_llm_with_retry` 只在**整个响应为空 dict** 时重试。但实际情况是：DeepSeek V4 返回了非空 dict（包含 `pain_points` 但缺 `persona`，或反过来），通过了 retry 层的检查。然后在 `validate_task_output` 逐 task 检查时，缺少的那个 key 对应的 task 被标记"结果为空"。

**T1+T2 组合输出量最大**（画像 5+ signals + summary + 痛点 2-4 个 × 4 步话术 × 30-60 字），DeepSeek V4 tool_call 的字符串化 JSON 在这种大输出下偶发只产出一部分 key。

### 级联影响

T1 或 T2 失败 → T9（总览，依赖 T1-T7）失败 → T11（下一步，依赖 T1+T2+T4）失败 → session 整体标 failed。

数据：T9 的 10 次失败全部是"依赖未完成"级联，T11 的 5 次中大部分也是。

### 修改方案

```python
# webapp.py 第 2186-2189 行
CALL_CHUNKS = {
    1: [["T1"], ["T2"], ["T3", "T4"]],   # ← T1、T2 拆开
    2: [["T5"], ["T6"], ["T7", "T8"]],
    3: [["T9"], ["T10"], ["T11"]],
}
```

### 自验证

- **有效性**：T1 和 T2 互不依赖（`depends_on: []`），拆开后各自独立调用，输出 schema 减半，DeepSeek 只需产出一个顶层 key，不会再出现"返回了 A 但缺 B"的问题。
- **性能影响**：Call 1 从 2 并行 chunk 变成 3 并行 chunk（仍在同一个 ThreadPoolExecutor 里并行），总耗时由最慢的 chunk 决定，实际不会变慢。
- **成本影响**：多一次 LLM 调用，但每次 shared preflight 底稿是复用的（只跑一次），额外成本仅为多一次 8000 token 输出预算的小调用。相比每次失败后整体重跑，反而省钱。
- **风险**：极低，只改一行配置，不动任何逻辑代码。

---

## 优化 2（P0）：启动时自动恢复"进程退出导致的失败" session

### 问题

58 个 failed session 中：
- **44 个**的错误是 `cannot schedule new futures after interpreter shutdown`（进程被杀时线程池已关闭）
- 这 44 个 session 的 ASR 全部完成，task_status 为空（**一次 LLM 调用都没发出就失败了**）
- 另外 51 个 pending session 的 ASR 也全部完成，但从未触发分析

当前 `startup_kick()` 明确不自动补跑（注释："避免意外烧钱"），只把 running/queued 标记为 failed。

### 根因

进程重启后，这些 session 永远卡在 failed/pending 状态，除非用户手动点"重跑"。但：
1. 44 个 interpreter_shutdown session **零成本失败**——根本没调过 LLM，重跑不是"重复花钱"
2. 51 个 pending session 的 ASR 已完成，理应自动触发分析，但 startup_kick 的保守策略阻止了这一步

### 修改方案

在 `startup_kick()` 中增加一个安全的自动恢复逻辑：

```python
def startup_kick():
    # ... 现有的 pending ASR 恢复逻辑 ...
    
    # 把 running/queued 标 failed（保持原有逻辑）
    db_write("""UPDATE sessions SET analysis_status='failed', ...""")
    
    # 新增：自动恢复"零成本失败"的 session（从未发过 LLM 调用）
    zero_cost_failed = db_fetchall("""
        SELECT id FROM sessions
        WHERE analysis_status = 'failed'
          AND (task_status IS NULL OR task_status = '{}')
          AND analysis_error LIKE '%interpreter shutdown%'
    """)
    for s in zero_cost_failed:
        maybe_trigger_session_analysis(s["id"])
    
    # 新增：触发 pending 且 ASR 全完成的 session
    pending_ready = db_fetchall("""
        SELECT s.id FROM sessions s
        WHERE (s.analysis_status = 'pending' OR s.analysis_status IS NULL)
          AND NOT EXISTS (
            SELECT 1 FROM recordings r 
            WHERE r.session_id = s.id AND r.asr_status != 'done'
          )
          AND EXISTS (
            SELECT 1 FROM recordings r WHERE r.session_id = s.id
          )
    """)
    for s in pending_ready:
        maybe_trigger_session_analysis(s["id"])
```

### 自验证

- **有效性**：目标明确——只恢复 task_status 为空（零 LLM 成本）且错误是 interpreter_shutdown 的 session。这类 session 100% 可以安全重跑。
- **成本控制**：`maybe_trigger_session_analysis` 内部有并发控制（`ANALYSIS_MAX_CONCURRENCY=4`），不会一下子涌入 95 个 session。多余的会排队，不会撑爆内存。
- **防重复**：`maybe_trigger_session_analysis` 内部已有 `analysis_status in ('running','queued') → return` 守卫，不会重复触发。
- **对"真正花过钱失败"的 session**：仍然不自动重跑（它们 task_status 非空），保持手动策略。
- **风险**：启动后会有一波 LLM 调用涌入，但受 ANALYSIS_MAX_CONCURRENCY=4 限流，实际是 4 并发逐个消化，可控。

---

## 优化 3（P1）：接诊页"一键分析"应跳过已成功的 task

### 问题

接诊页点击"确认并开始分析"调用 `/api/consultant/session/start_analysis`，内部直接 `submit_analysis(sess["id"], sig)` 且 `only_tasks=None`，**无条件重跑全部 11 个 task**，不检查哪些已经成功。

**调用链路**：
```
前端 startAnalysisPkg()
  → POST /api/consultant/session/start_analysis
    → submit_analysis(sid, sig)        # only_tasks=None
      → run_session_analysis(...)
        → target_tasks = list(TASK_REGISTRY.keys())  # T1-T11 全跑
```

### 实际影响

1. **白花钱**：最常见场景是"首次跑了部分失败，顾问再点一次"。8 个已成功的 task 被无谓重跑，浪费 8 次 LLM 调用费用。
2. **退化风险**：DeepSeek V4 有偶发"结果为空"，已成功的 task 重跑后反而可能变成失败——越跑越差。
3. **耗时翻倍**：本来只需补 2-3 个 task（<1 分钟），现在要等全套跑完（4-8 分钟），顾问干等。

### 正确逻辑

| 条件 | 行为 |
|------|------|
| signature 没变（录音没变） | 等同 fill-missing：只跑 status != done 的 task |
| signature 变了（新增/删除了录音） | 全量重跑：清 shared_context + 跑全部 task |

### 修改方案

```python
# api_consultant_session_start_analysis 中，替换现有的 submit_analysis 调用

sig = compute_session_signature(sess["id"])
sess_detail = db_fetchone(
    "SELECT analysis_signature, analysis_status FROM sessions WHERE id=?",
    (sess["id"],),
)

if (sess_detail
    and sess_detail["analysis_signature"] == sig
    and sess_detail["analysis_status"] in ("done", "failed")):
    # signature 没变 → 只补跑失败/缺失的 task
    missing = get_missing_tasks(sess["id"])
    if not missing:
        return jsonify({"ok": True, "session_id": sess["id"],
                        "msg": "所有任务已完成，无需重跑"})
    targets = expand_to_call_chunk(missing)
    for tid in targets:
        set_task_status(sess["id"], tid, "running")
    submit_analysis(sess["id"], sig, only_tasks=targets)
else:
    # signature 变了或首次分析 → 全量跑
    submit_analysis(sess["id"], sig)
```

### 自验证

- **首次分析**：`analysis_signature` 为 NULL，走 else 分支 → 全量跑，行为不变。
- **录音没变 + 部分失败**：signature 相同 → 只跑 missing tasks，省钱省时间。
- **新增录音后再分析**：signature 变了 → 全量跑 + 自动刷新 shared_context（因为 `_run_session_analysis_impl` 内部检测到 `prev_sig != signature` 会清旧底稿）。
- **全部已成功再点**：`missing` 为空 → 直接返回"无需重跑"，零成本。
- **风险**：低。`get_missing_tasks` 和 `expand_to_call_chunk` 都是已有函数，逻辑验证过。

---

## 优化 4（P1）：约束顾问端重复触发分析，防止滥用烧 token

### 现状梳理

顾问端触发分析有 2 个入口：

| 入口 | 路径 | 保护机制 |
|------|------|----------|
| 接诊包页"确认并开始分析" | `/api/consultant/session/start_analysis` | session locked 后前端隐藏按钮（但后端未校验 locked） |
| 旧版顾问分析（按顾客+日期） | `/api/consultant/analyze` | **无任何限制**，可反复调用 |

**当前 lock 机制**：
- `start_analysis` 内部先 `SET locked=1`，之后前端判断 `locked` 隐藏按钮
- 但后端接口本身 **没检查 locked 状态**，如果前端被绕过（或旧版页面），仍可重复触发
- `/api/consultant/analyze` 完全没有 lock/signature 检查，顾问可无限调用

### 应该允许重新分析的场景

| 场景 | 触发条件 | 原因 |
|------|----------|------|
| 新绑定了录音到接诊包 | signature 变化（录音集变了） | 新录音导致转录内容变化，旧分析不完整 |
| 从接诊包移除了录音 | signature 变化 | 同上，输入变了 |
| 说话人确认操作（confirm_speakers） | signature 变化（之前被阻塞的 session 现在可分析了） | 之前因说话人 warning 未触发分析 |
| 管理员解锁后顾问重新调整 | admin 解锁 → 顾问改完 → 重新锁定分析 | 管理员主动允许 |

### 不应该允许的场景

- 分析已完成（done），录音没变 → 纯粹重复消费
- 分析正在跑（running/queued）→ 重复提交
- 顾问手动反复点击试图"刷新结果" → 浪费 token

### 修改方案

**原则：只有 signature 变化（输入确实变了）才允许顾问触发重新分析。**

```python
# /api/consultant/session/start_analysis 修改

@app.route("/api/consultant/session/start_analysis", methods=["POST"])
@login_required
def api_consultant_session_start_analysis():
    # ... 现有 customer/session 查找逻辑 ...
    
    # ① 正在跑的不允许重复提交
    sess_detail = db_fetchone(
        "SELECT locked, analysis_status, analysis_signature FROM sessions WHERE id=?",
        (sess["id"],),
    )
    if sess_detail["analysis_status"] in ("running", "queued"):
        return jsonify({"error": "分析正在进行中，请等待完成"}), 409
    
    sig = compute_session_signature(sess["id"])
    
    # ② 已锁定且 signature 没变 → 不允许重跑（输入没变，无需浪费）
    if (sess_detail["locked"]
        and sess_detail["analysis_signature"] == sig
        and sess_detail["analysis_status"] == "done"):
        return jsonify({"error": "分析已完成，录音未变化，无需重新分析"}), 409
    
    # ③ 已锁定但 signature 变了（管理员解锁后顾问改了录音又锁回来）→ 允许
    # ④ 未锁定首次分析 → 允许
    
    db_write("UPDATE sessions SET locked=1 WHERE id=?", (sess["id"],))
    
    # 智能决定跑全量还是补跑（结合优化3）
    if (sess_detail["analysis_signature"] == sig
        and sess_detail["analysis_status"] in ("done", "failed")):
        missing = get_missing_tasks(sess["id"])
        if not missing:
            return jsonify({"ok": True, "session_id": sess["id"],
                            "msg": "所有任务已完成"})
        targets = expand_to_call_chunk(missing)
        for tid in targets:
            set_task_status(sess["id"], tid, "running")
        submit_analysis(sess["id"], sig, only_tasks=targets)
    else:
        submit_analysis(sess["id"], sig)
    
    return jsonify({"ok": True, "session_id": sess["id"]})


# /api/consultant/analyze 同样加约束
@app.route("/api/consultant/analyze", methods=["POST"])
@login_required
def api_consultant_analyze():
    # ... 现有逻辑 ...
    for r in rows:
        sid = r["id"]
        sess_detail = db_fetchone(
            "SELECT analysis_status, analysis_signature FROM sessions WHERE id=?",
            (sid,),
        )
        # 只对 signature 变化的 session 触发分析
        sig = compute_session_signature(sid)
        if (sess_detail["analysis_status"] == "done"
            and sess_detail["analysis_signature"] == sig):
            continue  # 跳过：输入没变，无需重跑
        if sess_detail["analysis_status"] in ("running", "queued"):
            continue  # 跳过：已在跑
        submit_analysis(sid, sig)
        triggered.append(sid)
    # ...
```

### 自验证

- **首次分析**：signature 为 NULL，不等于新 sig → 正常触发全量分析。
- **分析完成后顾问再点**：locked=True + signature 没变 + status=done → 返回 409"无需重新分析"，零成本。
- **新绑了录音后再点**：signature 变了 → 允许重跑全量，因为输入确实变了。
- **分析部分失败后再点**：signature 没变 + status=failed → 进入"补跑 missing"路径，只花失败 task 的钱。
- **并发点击**：running/queued 时返回 409，不重复提交。
- **管理员解锁 → 顾问调整录音 → 重新分析**：解锁后录音变化导致 signature 变 → 允许。

### 补充思考：还有哪些场景会改变 signature？

`compute_session_signature` 的计算逻辑：

```python
def compute_session_signature(session_id):
    """session 当前所有 recording_id 的指纹；用于分析结果失效检测"""
```

signature 基于 session 下所有 recording_id 集合，以下操作会改变它：
1. **绑定新录音**（`/api/consultant/recordings/<rid>/bind`）→ recording 加入 session
2. **移除录音**（`/api/consultant/session/preview/remove`）→ recording 从 session 移除
3. **管理员删除录音**（`/api/recording/<rid>/delete`）→ recording 被删
4. **说话人确认后自动触发**（`confirm_speakers` + `maybe_trigger_session_analysis`）→ 虽然 signature 可能不变，但之前被 speaker_warning 阻塞的现在放行了

这些场景全部覆盖了你提到的"重新绑定录音"和"录音段删除"，加上"说话人确认"这个你可能没想到的场景。

---

## 优化 5（P0）：修复 session 状态卡死 bug（余额不足/异常退出后状态不回写）

### 问题

当 DeepSeek API 返回 402（余额不足）或其他未预期异常时，session 状态会**永久卡在 `pending`/`running`/`queued`**，既不显示为失败，也无法重新触发。

**实际数据**：当前有 5 个 session 卡在 `pending` + `排队中…`，其中 2 个实际任务全部 done（11/11），3 个部分失败——但从前端列表看全是"待分析"状态，用户根本不知道出了什么事。

### 根因

状态翻转存在一个"缝隙"：

```
maybe_trigger_session_analysis()
  → db_write("SET analysis_status='queued'")     ← 标了 queued
  → submit_analysis()                             ← 丢进线程池

     _runner() 被线程池拉起:
       → db_write("SET analysis_status='running'")  ← 翻成 running
       → run_session_analysis()
           内部 try/except 会标 failed              ← 正常路径 OK
       → except Exception as e:
           print(...)                               ← ⚠ 只打日志，不改状态！
```

失败路径：
1. `_runner` 内部 `run_session_analysis` 的 try/except（第 3339 行）正常能兜底标 `failed`
2. 但如果异常**逃逸了这层 try**（比如 `_run_session_analysis_impl` 还没进入就因为 lock.acquire 超时、或者线程池 shutdown），就会被外层第 587 行的 `except` 接住——**这里只 print，不改 `analysis_status`**
3. `task_health_check_loop` 只扫 `analysis_status='done'` 的 session，不覆盖 `pending`/`running`/`queued`
4. `startup_kick` 只把 `running`/`queued` 标 `failed`，不处理 `pending`（因为正常的新 session 也是 pending）

结果：402 余额不足 → shared_preflight 抛错 → `run_call` 内部标了 task_status=failed → 但 session 总状态可能没翻到 `failed`（取决于异常路径），或者被 startup_kick 标了 failed 后又被其他逻辑覆盖为 pending。

### 修改方案

**三层兜底**：

```python
# ① submit_analysis 的 _runner 必须保证状态回写
def _runner():
    global _analysis_inflight
    try:
        db_write(
            """UPDATE sessions SET analysis_status='running',
               analysis_started_at=datetime('now','localtime'),
               analysis_progress='分析正在进行中…'
               WHERE id=? AND analysis_status IN ('queued','running','pending')""",
            (session_id,),
        )
    except Exception as e:
        print(f"[submit_analysis] session={session_id} 翻转 running 失败: {e}")
    try:
        return run_session_analysis(session_id, signature, *args, **kwargs)
    except Exception as e:
        print(f"[submit_analysis] session={session_id} 异常: {e}")
        # ⚠ 新增：兜底标 failed，防止状态卡死
        try:
            db_write(
                """UPDATE sessions SET analysis_status='failed',
                   analysis_error=?,
                   analysis_progress=NULL,
                   analysis_finished_at=datetime('now','localtime')
                   WHERE id=? AND analysis_status IN ('running','queued','pending')""",
                (f"未预期异常: {str(e)[:300]}", session_id),
            )
        except Exception:
            pass
    finally:
        with _analysis_inflight_lock:
            _analysis_inflight -= 1


# ② task_health_check_loop 扩展：覆盖 session 级别的卡死
def task_health_check_loop():
    while True:
        try:
            # 原有逻辑：扫 task 级别 running 超时 ...
            
            # 新增：扫 session 级别卡死
            # analysis_status 仍为 running/queued 但 analysis_started_at 超过 30 分钟
            stale = db_fetchall("""
                SELECT id FROM sessions
                WHERE analysis_status IN ('running', 'queued')
                  AND analysis_started_at IS NOT NULL
                  AND (julianday('now','localtime') - julianday(analysis_started_at)) * 1440 > 30
            """)
            for s in stale:
                db_write(
                    """UPDATE sessions SET analysis_status='failed',
                       analysis_error='卡在运行状态超过 30 分钟，自动标记失败',
                       analysis_progress=NULL
                       WHERE id=?""",
                    (s["id"],),
                )
            
            # 新增：修正"task 全 done 但 session 状态不是 done"的不一致
            inconsistent = db_fetchall("""
                SELECT id, task_status FROM sessions
                WHERE analysis_status != 'done'
                  AND task_status IS NOT NULL AND task_status != '{}'
            """)
            for s in inconsistent:
                try:
                    ts = json.loads(s["task_status"])
                    all_ids = list(TASK_REGISTRY.keys())
                    done_cnt = sum(1 for tid in all_ids
                                   if ts.get(tid, {}).get("status") == "done")
                    if done_cnt == len(all_ids):
                        db_write(
                            """UPDATE sessions SET analysis_status='done',
                               analysis_progress=? WHERE id=?""",
                            (f"完成 {done_cnt}/{len(all_ids)} 任务（状态修复）", s["id"]),
                        )
                except (json.JSONDecodeError, TypeError):
                    pass
        except Exception as e:
            print(f"[task_health_check] {e}")
        sleep(900)


# ③ startup_kick 修正"pending+排队中"的异常状态
# 在现有逻辑之后加：
stale_pending = db_fetchall("""
    SELECT id FROM sessions
    WHERE analysis_status = 'pending'
      AND analysis_progress = '排队中…'
      AND analysis_started_at IS NOT NULL
""")
for s in stale_pending:
    # 检查 task_status 判断实际状态
    ts_row = db_fetchone("SELECT task_status FROM sessions WHERE id=?", (s["id"],))
    ts = json.loads(ts_row["task_status"]) if (ts_row and ts_row["task_status"]) else {}
    all_ids = list(TASK_REGISTRY.keys())
    done_cnt = sum(1 for tid in all_ids if ts.get(tid, {}).get("status") == "done")
    if done_cnt == len(all_ids):
        db_write("UPDATE sessions SET analysis_status='done', "
                 "analysis_progress=? WHERE id=?",
                 (f"完成 {done_cnt}/{len(all_ids)} 任务（启动修复）", s["id"]))
    else:
        db_write("UPDATE sessions SET analysis_status='failed', "
                 "analysis_progress=NULL, "
                 "analysis_error='启动时发现状态异常，请手动重跑' WHERE id=?",
                 (s["id"],))
```

### 自验证

- **402 余额不足**：`_call_llm_with_retry` 抛 RuntimeError → `_run_shared_preflight` 抛 → `run_call` catch 住标 task failed → `_run_session_analysis_impl` 正常走完标 session failed。但如果异常逃逸到 `_runner`，现在有兜底 db_write 标 failed。
- **线程池 shutdown**：`_runner` 的 finally 不会执行（线程被杀），但 startup_kick 重启时会把 running/queued 标 failed，加上新增的 stale_pending 修复。
- **task 全 done 但 session 卡 pending**：health_check 每 15 分钟扫一次修正。
- **风险**：兜底逻辑都是幂等的，多跑不会出错。

---

## 五者结合的预期效果

| 指标 | 优化前 | 优化后（预期） |
|------|--------|---------------|
| 首次分析完成率 | ~60%（T1+T2 经常丢 key） | ~90%+（拆 chunk 后单 key 输出极少失败） |
| session 最终完成率 | 23%（32/141） | 80%+（95 个卡死 session 被恢复） |
| 用户需手动干预的 session | ~77% | <15%（仅真正的 schema/质量问题） |
| 顾问重试时的 LLM 成本 | 全量 11 task（~¥2-3/次） | 仅失败 task（~¥0.3-0.5/次） |
| 顾问重试等待时间 | 4-8 分钟 | <1 分钟（仅补 1-3 个 task） |
| 顾问滥用风险 | 无约束，可无限触发 | 仅 signature 变化时允许 |
| 状态卡死 session | 6 个永久卡住 | 自动修复，0 卡死 |
| 修改量 | — | ~100 行代码 |