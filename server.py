"""
Live接诊分析服务（v2）：

- 加载 output/logic_library.json 作为"刁姐逻辑库"，整库注入 system prompt（带 prompt cache）
- 同一份输入并行调两次 Claude：
    A. ours       —— 刁姐人格 + 整库注入 → 高维度分析（带 logic_id 引用 + 风险预警 + 一句话刁姐点评）
    B. competitor —— 竞品风格基线 prompt → 仅竞品维度的输出
- 通过 SSE 多路复用流式返回，前端按 channel 分别渲染
- /api/demo 提供一条样例数据，便于演示

环境变量：
    ANTHROPIC_API_KEY  必填
    MODEL              默认 claude-sonnet-4-6
    PORT               默认 5057
"""
import json
import os
import queue
import threading
from pathlib import Path

import anthropic
from flask import Flask, Response, jsonify, request, send_from_directory

HERE = Path(__file__).parent
WEB = HERE / "web"
LIB_PATH = HERE / "output" / "logic_library.json"
TAGS_PATH = HERE / "output" / "tags_summary.json"
RAW_PATH = HERE / "output" / "raw_rows.json"
PER_ROW_DIR = HERE / "output" / "per_row"

MODEL = os.environ.get("MODEL", "claude-sonnet-4-6")
PORT = int(os.environ.get("PORT", "5057"))


with open(LIB_PATH, encoding="utf-8") as f:
    LIBRARY = json.load(f)
with open(TAGS_PATH, encoding="utf-8") as f:
    TAGS_SUMMARY = json.load(f)


def format_library_text(logics):
    """把逻辑库压成行式紧凑文本，便于 LLM 检索引用，每条带 logic_id。"""
    lines = []
    for L in logics:
        sig = "/".join(L.get("trigger_signals") or [])
        anti = (L.get("anti_pattern") or "").strip()
        tags = ", ".join(L.get("secondary_tags") or [])
        line = (f"[{L['id']}|{L['stage']}|{L.get('boss_priority','medium')}|{L.get('type','')}] "
                f"tags=[{tags}] | logic={L['logic']} | signals=[{sig}]")
        if anti:
            line += f" | anti={anti}"
        lines.append(line)
    return "\n".join(lines)


LIBRARY_TEXT = format_library_text(LIBRARY)


OURS_SYSTEM = f"""你是"刁姐"——一位拥有 20 年医美 / 养护行业实战经验的资深销售总监。你以下方"分析逻辑库"（来自历次真实接诊点评的提炼）为知识基础，对销售顾问与顾客的全程接待录音转文字进行严格、有温度、洞察到位的复盘。

# 你的分析逻辑库（共 {len(LIBRARY)} 条）

每条格式：[逻辑ID|阶段|优先级|类型] tags=[二级标签] | logic=逻辑内容 | signals=[触发信号关键词] | anti=反面做法（可选）

{LIBRARY_TEXT}

# 你的工作方法

输入是销售顾问与顾客一次完整接待的三个阶段录音转文字（"进房间前 / 中途到房间里 / 从房间出来"，任一段可能为空）。请：

1. 先在心里检索：哪些 logic 的 signals 在本次对话里被触发了？哪些 anti_pattern 被踩了？
2. 引用具体的 logic_id 来组织你的分析（key_insights 数组中每条必须带 logic_id），并给出对话中的原话作为信号
3. 给出阶段评分（0-10）和综合评分（0-100）
4. 提取客户画像标签（结合对话中的真实信号，不要泛泛）
5. 判断成交可能性，并指出**真正的**促成或失败原因（不要停留在表面）
6. 提出下一步行动建议（既要"动作"，也要"具体可说的话术"）
7. 风险预警：若命中 anti_pattern，逐条点出（带 logic_id 引用 + 具体哪句话/行为踩雷 + 后果）
8. 一句话总结，用刁姐的口吻——直接、有指导、有温度，不要 AI 客套

# 输出格式（严格 JSON，仅输出 JSON 本体；不要使用 markdown 代码块包裹）

{{
  "score": <0-100 整数>,
  "score_text": "<综合评分文字 2-4 句>",
  "stage_assessments": {{
    "before_room": {{"score": <0-10>, "comment": "<进房间前阶段点评>"}},
    "mid_room":    {{"score": <0-10>, "comment": "<中途阶段点评>"}},
    "after_room":  {{"score": <0-10>, "comment": "<从房间出来阶段点评>"}}
  }},
  "customer_tags": ["<画像标签1>", "..."],
  "deal_likelihood": "high|medium|low",
  "deal_or_no_deal_analysis": "<2-4 句关于本次成交/未成交的真正原因>",
  "next_actions": [
    {{"action": "<具体动作>", "script": "<可直接说的话术>"}}
  ],
  "key_insights": [
    {{"logic_id": "<L0001>", "stage": "<阶段>", "signal_quoted": "<对话中的原话>", "boss_view": "<刁姐视角的解读>"}}
  ],
  "risk_warnings": [
    {{"logic_id": "<L0001>", "what_happened": "<对话中具体哪句/哪种行为踩了 anti_pattern>", "consequence": "<会带来的损害>"}}
  ],
  "ai_summary": "<2-3 句整体复盘>",
  "boss_summary": "<一句话，刁姐口吻，直击要害>"
}}"""


COMPETITOR_SYSTEM = """你是一个销售质量评估 AI 助手。请基于销售顾问与顾客的整个接待全过程录音转文字（三个阶段），给出量化评估和分析报告。

# 输入
三个阶段的对话文字：进房间前 / 中途到房间里 / 从房间出来。

# 输出格式（严格 JSON，仅输出 JSON 本体，不要 markdown 包裹）

{
  "score": <0-100 整数, 接诊综合质量分>,
  "score_text": "<2-4 句综合评分文字>",
  "customer_tags": ["<客户画像标签1>", "..."],
  "deal_or_no_deal_analysis": "<分析未成交/成交的关键因素，2-4 句>",
  "next_actions": ["<下一步行动建议1>", "..."],
  "ai_summary": "<整体 AI 总结，3-5 句>"
}

请客观、专业、结构化地给出分析；以销售顾问表现的"亮点 / 不足 / 改进方向"为主要观察角度。"""


def build_user_prompt(before, mid, after):
    return (
        f"进房间前的对话录音转文字：\n\"\"\"\n{(before or '（无）').strip()}\n\"\"\"\n\n"
        f"中途到房间里的对话录音转文字：\n\"\"\"\n{(mid or '（无）').strip()}\n\"\"\"\n\n"
        f"从房间出来的对话录音转文字：\n\"\"\"\n{(after or '（无）').strip()}\n\"\"\"\n\n"
        "请按 system 中的 JSON 格式输出。"
    )


def prematch_signals(before, mid, after):
    """对每条 logic 的 trigger_signals 做子串匹配，返回命中列表（用于侧边栏展示）。"""
    by_stage = {
        "进房间前": before or "",
        "中途到房间里": mid or "",
        "从房间出来": after or "",
    }
    full_text = "\n".join(by_stage.values())
    hits = []
    for L in LIBRARY:
        for sig in L.get("trigger_signals", []) or []:
            sig = (sig or "").strip()
            if not sig:
                continue
            if sig in full_text:
                where = next((stg for stg, t in by_stage.items() if sig in t), "跨阶段")
                hits.append({
                    "logic_id": L["id"],
                    "logic_stage": L["stage"],
                    "priority": L.get("boss_priority", "medium"),
                    "signal": sig,
                    "hit_in": where,
                    "logic_excerpt": (L["logic"][:80] + "…") if len(L["logic"]) > 80 else L["logic"],
                })
                break
    return hits


# --------- Flask app ---------

app = Flask(__name__, static_folder=None)


@app.get("/")
def index():
    return send_from_directory(WEB, "index.html")


@app.get("/web/<path:p>")
def web_static(p):
    return send_from_directory(WEB, p)


@app.get("/api/library/stats")
def lib_stats():
    return jsonify({
        "total": len(LIBRARY),
        "by_stage": TAGS_SUMMARY.get("by_stage", {}),
        "by_priority": TAGS_SUMMARY.get("by_priority", {}),
        "by_type": TAGS_SUMMARY.get("by_type", {}),
        "categories": list(TAGS_SUMMARY.get("secondary_categories", {}).keys()),
        "model": MODEL,
    })


def _build_demo_pools():
    """按是否有 boss_review 分两个池，每池只取三阶段齐全的样例。"""
    if not RAW_PATH.exists():
        return None, None
    rows = json.load(open(RAW_PATH, encoding="utf-8"))
    three_stage = [
        r for r in rows
        if r.get("before_room_transcript") and r.get("mid_room_transcript") and r.get("after_room_transcript")
    ]
    with_boss = [r for r in three_stage if r.get("boss_review")]
    without_boss = [r for r in three_stage if not r.get("boss_review")]
    return with_boss, without_boss


@app.get("/api/demo/pools")
def demo_pools():
    """前端初始化用：返回两个池各自的数量。"""
    with_boss, without_boss = _build_demo_pools()
    if with_boss is None:
        return jsonify({"error": "raw_rows.json not found"}), 500
    return jsonify({
        "with_boss": len(with_boss),
        "without_boss": len(without_boss),
    })


@app.get("/api/demo")
def demo():
    """从指定池里挑一条样例。pool=with_boss|without_boss（默认 with_boss）。"""
    with_boss, without_boss = _build_demo_pools()
    if with_boss is None:
        return jsonify({"error": "raw_rows.json not found, run extract_data.py first"}), 500

    pool_name = request.args.get("pool", "with_boss")
    if pool_name == "without_boss":
        pool = without_boss
    elif pool_name == "with_boss":
        pool = with_boss
    else:
        return jsonify({"error": f"unknown pool: {pool_name}"}), 400

    if not pool:
        return jsonify({"error": f"pool '{pool_name}' is empty"}), 404

    try:
        idx = int(request.args.get("idx", "0"))
    except ValueError:
        idx = 0
    idx = idx % len(pool)
    r = pool[idx]
    return jsonify({
        "pool": pool_name,
        "card_no": r.get("card_no"),
        "member_name": r.get("member_name"),
        "is_new": r.get("is_new"),
        "deal": r.get("deal"),
        "deal_amount": r.get("deal_amount"),
        "store": r.get("store"),
        "staff_name": r.get("staff_name"),
        "before": r.get("before_room_transcript") or "",
        "mid": r.get("mid_room_transcript") or "",
        "after": r.get("after_room_transcript") or "",
        "boss_review_actual": r.get("boss_review") or "",
        "competitor_actual": {
            "score": r.get("comp_score"),
            "score_text": r.get("comp_score_text"),
            "score_text_combined": r.get("comp_score_text_combined"),
            "customer_tags": r.get("comp_customer_tags"),
            "no_deal_analysis": r.get("comp_no_deal_analysis"),
            "deal_analysis": r.get("comp_deal_analysis"),
            "next_action": r.get("comp_next_action"),
            "ai_summary": r.get("comp_ai_summary"),
        },
        "total": len(pool),
        "idx": idx,
    })


def _stream_one(channel, system_prompt, user_prompt, q, *, cache, max_tokens):
    """在线程里跑一次 streaming claude 调用，把 deltas 推入队列。"""
    try:
        client = anthropic.Anthropic()
        sys_blocks = [{"type": "text", "text": system_prompt}]
        if cache:
            sys_blocks[0]["cache_control"] = {"type": "ephemeral"}
        kwargs = dict(
            model=MODEL,
            max_tokens=max_tokens,
            system=sys_blocks,
            messages=[{"role": "user", "content": user_prompt}],
            thinking={"type": "disabled"},
        )
        full_chunks = []
        usage = None
        with client.messages.stream(**kwargs) as stream:
            for chunk in stream.text_stream:
                if chunk:
                    full_chunks.append(chunk)
                    q.put((channel, "delta", {"text": chunk}))
            final = stream.get_final_message()
            try:
                usage = {
                    "input_tokens": getattr(final.usage, "input_tokens", None),
                    "output_tokens": getattr(final.usage, "output_tokens", None),
                    "cache_read_input_tokens": getattr(final.usage, "cache_read_input_tokens", None),
                    "cache_creation_input_tokens": getattr(final.usage, "cache_creation_input_tokens", None),
                }
            except Exception:
                usage = None
        q.put((channel, "done", {"full_text": "".join(full_chunks), "usage": usage}))
    except Exception as e:
        import traceback
        q.put((channel, "error", {"error": f"{type(e).__name__}: {e}", "trace": traceback.format_exc()[-1500:]}))


@app.post("/api/analyze")
def analyze():
    body = request.get_json(force=True, silent=True) or {}
    before = (body.get("before") or "").strip()
    mid = (body.get("mid") or "").strip()
    after = (body.get("after") or "").strip()
    if not (before or mid or after):
        return jsonify({"error": "请提供至少一个阶段的对话文字"}), 400

    user_prompt = build_user_prompt(before, mid, after)
    matched = prematch_signals(before, mid, after)

    def gen():
        def sse(event, data):
            return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

        # 先把元信息和命中结果丢出去
        yield sse("meta", {
            "model": MODEL,
            "library_size": len(LIBRARY),
            "matched_count": len(matched),
            "matched_logics": matched,
        })

        q = queue.Queue()
        t1 = threading.Thread(target=_stream_one, kwargs={
            "channel": "ours",
            "system_prompt": OURS_SYSTEM,
            "user_prompt": user_prompt,
            "q": q,
            "cache": True,
            "max_tokens": 8000,
        }, daemon=True)
        t2 = threading.Thread(target=_stream_one, kwargs={
            "channel": "comp",
            "system_prompt": COMPETITOR_SYSTEM,
            "user_prompt": user_prompt,
            "q": q,
            "cache": False,
            "max_tokens": 4000,
        }, daemon=True)
        t1.start()
        t2.start()

        finished = 0
        while finished < 2:
            channel, kind, payload = q.get()
            if kind == "delta":
                yield sse(f"{channel}_delta", payload)
            elif kind == "done":
                yield sse(f"{channel}_done", payload)
                finished += 1
            elif kind == "error":
                yield sse(f"{channel}_error", payload)
                finished += 1

        yield sse("done", {})

    return Response(
        gen(),
        mimetype="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


if __name__ == "__main__":
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("⚠️  ANTHROPIC_API_KEY 未设置，/api/analyze 会失败；其它接口可用")
    print(f"➡️  http://127.0.0.1:{PORT}/  (model={MODEL}, library={len(LIBRARY)} logics)")
    app.run(host="127.0.0.1", port=PORT, threaded=True, debug=False)
