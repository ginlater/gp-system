"""
Step 2: per-row analysis with Claude.

Reads output/raw_rows.json, for each row calls Claude to compare
boss review vs competitor analysis vs raw transcripts, and writes
output/per_row/{card_no}.json.

Resumable: skips files already produced. Concurrent via ThreadPoolExecutor.
Uses prompt caching on the system prompt (shared across all 67 calls).

Env:
    ANTHROPIC_API_KEY   required
    MODEL               default: claude-opus-4-7
    MAX_WORKERS         default: 6
    EFFORT              default: high  (low|medium|high|max)
    THINKING            default: adaptive  (adaptive|disabled)
"""
import json
import os
import sys
import time
import traceback
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import anthropic

HERE = Path(__file__).parent
RAW = HERE / "output" / "raw_rows.json"
PER_ROW_DIR = HERE / "output" / "per_row"
ERROR_LOG = HERE / "output" / "errors.log"

MODEL = os.environ.get("MODEL", "claude-sonnet-4-6")
MAX_WORKERS = int(os.environ.get("MAX_WORKERS", "6"))
EFFORT = os.environ.get("EFFORT", "high")
THINKING = os.environ.get("THINKING", "adaptive")


SYSTEM_PROMPT = """你是一位资深的美业（医美/SPA/身体管理）销售培训与咨询专家。

【背景】
我们正在搭建一个 AI 销售接诊分析系统，用来回放和评估销售顾问对顾客的整个接待流程，给出诊断和改进建议。

我们目前有：
1. 一份"竞品 AI 系统"对每次接诊的分析输出（包含评分、客户画像标签、未成交分析、成交分析、下一次行动建议、AI 总结）。
2. 我们老板"刁姐"对同一次接诊的人工点评。她是非常资深的销售总监，她的点评经常能命中竞品 AI 漏掉或者搞错的关键问题。
3. 接待全过程的录音转文字（按阶段切分：进房间前、中途到房间里提供情绪价值、从房间出来）。

【你的任务】
对一条具体的接诊数据，做三件事：

A. 对比分析：找出
   1) competitor_missing_logic：刁姐发现了、但竞品 AI 完全没提的分析视角/逻辑。要具体到"竞品没看到 X 这个信号 / 没诊断 Y 这个问题"。
   2) competitor_wrong_logic：竞品 AI 有提，但分析方向/归因/优先级错误的。要具体说明错在哪、刁姐的判断是什么。
   3) boss_strengths：刁姐这次点评里表现得最有价值、最可作为分析模板的视角（即使竞品也提到了相关方向，但刁姐看得更深更准）。

B. 抽取分析逻辑（最重要）：
   把刁姐这条点评中体现出的、可复用的分析逻辑/经验/规则，结构化提炼成 extracted_logics 列表。
   每条逻辑要满足：
   - 抽象程度足够，能用到未来其他相似的接诊上（不要写死本次顾客的姓名/项目名）；
   - 同时保留必要的具象触发信号（trigger_signals），让 RAG 检索能命中；
   - 可直接作为一段"指令"注入到分析 prompt 里，让 AI 模仿刁姐的视角去看新数据。

C. 给每条逻辑打两层标签：
   - stage（一级标签，必填，仅取以下值之一）：
       "进房间前" | "中途到房间里" | "从房间出来" | "跨阶段"
   - secondary_tags（二级标签，2-5 个，"类别:具体值"格式）：
     建议使用以下类别（也可以新增，但保持类别名一致）：
       顾客类型 / 顾客性格 / 顾客状态 / 进店渠道 / 客人新老
       接诊环节 / 销售动作 / 话术
       需求类型 / 顾客痛点 / 顾客画像
       心理与情绪 / 信任建立 / 异议处理
       专业能力 / 风险与误区 / 成交引导 / 追单逻辑
     例: "顾客性格:慢热" "接诊环节:破冰" "话术:赞美" "风险与误区:过度推销"

【输出格式：严格 JSON，无任何前后说明文字】
{
  "row_summary": "<一句话总结这次接诊和刁姐的核心点评>",
  "competitor_missing_logic": [
    { "point": "<具体问题点>", "boss_signal_quote": "<刁姐原话片段>" }
  ],
  "competitor_wrong_logic": [
    { "competitor_said": "<竞品的判断概述>", "boss_correction": "<刁姐的更正>", "why_boss_is_right": "<为什么刁姐对>" }
  ],
  "boss_strengths": [
    { "angle": "<视角名称>", "explanation": "<为什么这个视角关键>" }
  ],
  "extracted_logics": [
    {
      "stage": "进房间前 | 中途到房间里 | 从房间出来 | 跨阶段",
      "secondary_tags": ["类别:具体值", "..."],
      "logic": "<可复用的分析逻辑/规则/经验，1-3 句，可作为 prompt 片段直接注入>",
      "type": "诊断逻辑 | 话术逻辑 | 流程逻辑 | 心理洞察 | 风险预警 | 追单逻辑 | 顾客画像逻辑",
      "trigger_signals": ["<对话/录音中能触发该逻辑的信号或关键词>", "..."],
      "boss_priority": "high | medium | low",
      "anti_pattern": "<可选：这条逻辑要避免/反对的错误做法，没有就空字符串>"
    }
  ]
}

【硬性要求】
- 仅输出 JSON，不要 markdown 代码块包裹，不要任何额外说明。
- 如果某个字段没有内容，用空数组而不是省略字段。
- extracted_logics 至少 3 条；如果点评内容丰富，可以到 10 条左右。
- 不要把竞品 AI 已经做得不错的内容当作刁姐的独有逻辑写进 extracted_logics。重点是刁姐补足或纠正的部分。
- logic 字段写抽象规则，不要写"本次李某的肩颈问题"这类具象描述。
"""


def build_user_prompt(row: dict) -> str:
    def section(title, content):
        if not content:
            return f"== {title} ==\n（空）\n"
        return f"== {title} ==\n{content}\n"

    base = f"""【顾客信息】
会员卡号：{row.get("card_no")}
姓名：{row.get("member_name")}
新/老客：{row.get("is_new")}
进店渠道：{row.get("channel")}
是否成交：{row.get("deal")}（1=成交，0=未成交）
成交金额：{row.get("deal_amount")}
门店：{row.get("store")}
顾问：{row.get("staff_name")}（工号 {row.get("staff_no")}）
服务日期：{row.get("service_date")} {row.get("start_time") or ""} - {row.get("end_time") or ""}

【刁姐评价（老板点评，最重要的输入）】
{row.get("boss_review") or "（本次无刁姐点评）"}

【竞品 AI 分析结果】
- 接诊质量评分：{row.get("comp_score")}
- 评分文字：
{row.get("comp_score_text") or "（空）"}
- 综合评分文字：
{row.get("comp_score_text_combined") or "（空）"}
- 客户画像标签：
{row.get("comp_customer_tags") or "（空）"}
- 未成交分析：
{row.get("comp_no_deal_analysis") or "（空）"}
- 成交分析：
{row.get("comp_deal_analysis") or "（空）"}
- 下一步行动建议：
{row.get("comp_next_action") or "（空）"}
- AI 总结：
{row.get("comp_ai_summary") or "（空）"}

【接待全过程语音转文字】
"""
    base += section("进房间前", row.get("before_room_transcript"))
    base += section("中途到房间里提供情绪价值", row.get("mid_room_transcript"))
    base += section("从房间出来", row.get("after_room_transcript"))
    base += "\n请按 system 中要求的 JSON 格式输出。"
    return base


def repair_inline_quotes(text: str) -> str:
    """Escape ASCII `"` characters that appear inside string values without
    proper escaping. The Chinese-language model often uses bare `"` to wrap
    inner terms (e.g. `"point": "竞品没识别出"顾客眼部需求"这个信号"`),
    which prematurely closes the JSON string. Heuristic: a `"` is a real
    closing quote only when the next non-whitespace char is one of `,}]:`
    or end-of-input. Otherwise escape it.
    """
    out = []
    in_string = False
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if not in_string:
            out.append(ch)
            if ch == '"':
                in_string = True
            i += 1
            continue
        if ch == "\\":
            out.append(ch)
            if i + 1 < n:
                out.append(text[i + 1])
                i += 2
            else:
                i += 1
            continue
        if ch == '"':
            j = i + 1
            while j < n and text[j] in " \t\n\r":
                j += 1
            if j >= n or text[j] in ",}]:":
                out.append(ch)
                in_string = False
                i += 1
            else:
                out.append('\\"')
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def parse_json_strict(text: str) -> dict:
    """Tolerant: strip ```json fences, extract outermost {...} block,
    then try strict parse; on failure, run quote-repair and retry."""
    s = text.strip()
    if s.startswith("```"):
        s = s.split("\n", 1)[1] if "\n" in s else s
        if s.endswith("```"):
            s = s[: -3]
        s = s.strip()
    start = s.find("{")
    end = s.rfind("}")
    if start != -1 and end != -1 and end > start:
        s = s[start : end + 1]
    try:
        return json.loads(s)
    except json.JSONDecodeError:
        return json.loads(repair_inline_quotes(s))


MAX_TOKENS = int(os.environ.get("MAX_TOKENS", "32000"))
DEBUG_DIR = HERE / "output" / "debug"

# Structured-outputs schema — eliminates malformed-JSON failures (the model
# was unescaping inner ASCII " characters when quoting Chinese terms).
RESPONSE_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "row_summary",
        "competitor_missing_logic",
        "competitor_wrong_logic",
        "boss_strengths",
        "extracted_logics",
    ],
    "properties": {
        "row_summary": {"type": "string"},
        "competitor_missing_logic": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["point", "boss_signal_quote"],
                "properties": {
                    "point": {"type": "string"},
                    "boss_signal_quote": {"type": "string"},
                },
            },
        },
        "competitor_wrong_logic": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["competitor_said", "boss_correction", "why_boss_is_right"],
                "properties": {
                    "competitor_said": {"type": "string"},
                    "boss_correction": {"type": "string"},
                    "why_boss_is_right": {"type": "string"},
                },
            },
        },
        "boss_strengths": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["angle", "explanation"],
                "properties": {
                    "angle": {"type": "string"},
                    "explanation": {"type": "string"},
                },
            },
        },
        "extracted_logics": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": [
                    "stage",
                    "secondary_tags",
                    "logic",
                    "type",
                    "trigger_signals",
                    "boss_priority",
                    "anti_pattern",
                ],
                "properties": {
                    "stage": {
                        "type": "string",
                        "enum": ["进房间前", "中途到房间里", "从房间出来", "跨阶段"],
                    },
                    "secondary_tags": {"type": "array", "items": {"type": "string"}},
                    "logic": {"type": "string"},
                    "type": {"type": "string"},
                    "trigger_signals": {"type": "array", "items": {"type": "string"}},
                    "boss_priority": {
                        "type": "string",
                        "enum": ["high", "medium", "low"],
                    },
                    "anti_pattern": {"type": "string"},
                },
            },
        },
    },
}


def call_one(client: anthropic.Anthropic, row: dict) -> dict:
    user_prompt = build_user_prompt(row)

    output_config = {"format": {"type": "json_schema", "schema": RESPONSE_SCHEMA}}
    if EFFORT in ("low", "medium", "high", "max"):
        output_config["effort"] = EFFORT

    kwargs = dict(
        model=MODEL,
        max_tokens=MAX_TOKENS,
        system=[
            {
                "type": "text",
                "text": SYSTEM_PROMPT,
                "cache_control": {"type": "ephemeral"},
            }
        ],
        messages=[{"role": "user", "content": user_prompt}],
        output_config=output_config,
    )
    if THINKING == "adaptive":
        kwargs["thinking"] = {"type": "adaptive"}

    # Stream — adaptive thinking + large max_tokens can take a while
    with client.messages.stream(**kwargs) as stream:
        msg = stream.get_final_message()

    text = ""
    for block in msg.content:
        if block.type == "text":
            text += block.text

    if not text:
        # Dump the response so we can see what came back (likely all thinking blocks
        # because max_tokens ran out before the model produced text)
        DEBUG_DIR.mkdir(parents=True, exist_ok=True)
        dump = DEBUG_DIR / f"{row.get('card_no', 'unknown')}_no_text.json"
        with open(dump, "w", encoding="utf-8") as f:
            json.dump(
                {
                    "stop_reason": msg.stop_reason,
                    "usage": msg.usage.model_dump() if hasattr(msg.usage, "model_dump") else None,
                    "block_types": [b.type for b in msg.content],
                },
                f,
                ensure_ascii=False,
                indent=2,
            )
        raise RuntimeError(
            f"no text block (stop_reason={msg.stop_reason}, blocks={[b.type for b in msg.content]}); "
            f"likely max_tokens too small — bump MAX_TOKENS or set THINKING=disabled"
        )
    try:
        return parse_json_strict(text)
    except json.JSONDecodeError as e:
        DEBUG_DIR.mkdir(parents=True, exist_ok=True)
        dump = DEBUG_DIR / f"{row.get('card_no', 'unknown')}_bad_json.txt"
        dump.write_text(text, encoding="utf-8")
        raise RuntimeError(
            f"JSON parse failed at {e}; raw response saved to {dump} "
            f"(stop_reason={msg.stop_reason})"
        )


def output_path_for(row: dict) -> Path:
    card = row.get("card_no") or "unknown"
    safe = "".join(c for c in str(card) if c.isalnum() or c in "_-")
    return PER_ROW_DIR / f"{safe}.json"


def process_row(client: anthropic.Anthropic, row: dict) -> tuple[str, str]:
    out = output_path_for(row)
    if out.exists():
        return (row["card_no"], "skip-exists")

    if not (row.get("boss_review") or "").strip():
        # No boss review -> nothing to learn from. Skip.
        return (row["card_no"], "skip-no-boss-review")

    try:
        result = call_one(client, row)
    except Exception as e:
        with open(ERROR_LOG, "a", encoding="utf-8") as f:
            f.write(f"\n=== {row.get('card_no')} ===\n")
            f.write(traceback.format_exc())
        return (row["card_no"], f"error: {e}")

    payload = {
        "card_no": row.get("card_no"),
        "member_name": row.get("member_name"),
        "deal": row.get("deal"),
        "is_new": row.get("is_new"),
        "channel": row.get("channel"),
        "store": row.get("store"),
        "staff_name": row.get("staff_name"),
        "model": MODEL,
        "analysis": result,
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    return (row["card_no"], "ok")


def main():
    if not RAW.exists():
        print(f"raw rows not found: {RAW}\nrun: python extract_data.py first", file=sys.stderr)
        sys.exit(1)
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("ANTHROPIC_API_KEY not set", file=sys.stderr)
        sys.exit(1)

    PER_ROW_DIR.mkdir(parents=True, exist_ok=True)

    with open(RAW, encoding="utf-8") as f:
        rows = json.load(f)

    client = anthropic.Anthropic()

    print(f"model={MODEL} workers={MAX_WORKERS} effort={EFFORT} thinking={THINKING}")
    print(f"input rows: {len(rows)}")

    t0 = time.time()
    results = {"ok": 0, "skip-exists": 0, "skip-no-boss-review": 0, "error": 0}
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as ex:
        futures = {ex.submit(process_row, client, row): row for row in rows}
        for i, fut in enumerate(as_completed(futures), start=1):
            card_no, status = fut.result()
            bucket = "error" if status.startswith("error") else status
            results[bucket] = results.get(bucket, 0) + 1
            print(f"[{i}/{len(rows)}] {card_no}: {status}")

    dt = time.time() - t0
    print(f"\ndone in {dt:.1f}s — {results}")
    if results.get("error"):
        print(f"see {ERROR_LOG}")


if __name__ == "__main__":
    main()
