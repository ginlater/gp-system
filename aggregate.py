"""
Step 3: aggregate per_row analyses into a tagged logic library.

Reads output/per_row/*.json. Writes:
  output/logic_library.json   - flat list of all extracted logics with IDs and source row info
  output/tag_index.json       - stage tag -> logic IDs, secondary tag -> logic IDs
  output/tags_summary.json    - tag frequency overview (useful for designing the L2 taxonomy)
  output/comparison_report.json - per-row competitor_missing / competitor_wrong / boss_strengths
                                  (handy as a single-file overview of where competitor falls short)

Optional: pass --consolidate to do one LLM pass that clusters near-duplicate logics
into canonical entries. By default this script does NOT call the LLM.
"""
import argparse
import json
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).parent
PER_ROW_DIR = HERE / "output" / "per_row"
OUT_LIB = HERE / "output" / "logic_library.json"
OUT_INDEX = HERE / "output" / "tag_index.json"
OUT_TAGS = HERE / "output" / "tags_summary.json"
OUT_COMPARE = HERE / "output" / "comparison_report.json"
OUT_CANON = HERE / "output" / "logic_library_canonical.json"

L1_VALID = {"进房间前", "中途到房间里", "从房间出来", "跨阶段"}


def collect():
    if not PER_ROW_DIR.exists():
        print(f"no per_row dir: {PER_ROW_DIR} — run analyze.py first", file=sys.stderr)
        sys.exit(1)

    files = sorted(PER_ROW_DIR.glob("*.json"))
    print(f"reading {len(files)} per_row files")

    logics = []
    comparisons = []
    next_id = 1

    for fp in files:
        with open(fp, encoding="utf-8") as f:
            payload = json.load(f)
        a = payload.get("analysis", {})
        card = payload.get("card_no")

        comparisons.append({
            "card_no": card,
            "member_name": payload.get("member_name"),
            "deal": payload.get("deal"),
            "row_summary": a.get("row_summary"),
            "competitor_missing_logic": a.get("competitor_missing_logic", []),
            "competitor_wrong_logic": a.get("competitor_wrong_logic", []),
            "boss_strengths": a.get("boss_strengths", []),
        })

        for raw in a.get("extracted_logics", []) or []:
            stage = raw.get("stage") or "跨阶段"
            if stage not in L1_VALID:
                stage = "跨阶段"
            logic = {
                "id": f"L{next_id:04d}",
                "stage": stage,
                "secondary_tags": raw.get("secondary_tags", []) or [],
                "logic": raw.get("logic", "").strip(),
                "type": raw.get("type", ""),
                "trigger_signals": raw.get("trigger_signals", []) or [],
                "anti_pattern": raw.get("anti_pattern", "") or "",
                "boss_priority": raw.get("boss_priority", "medium"),
                "source_card_no": card,
                "source_file": fp.name,
            }
            if not logic["logic"]:
                continue
            logics.append(logic)
            next_id += 1

    return logics, comparisons


def build_index(logics):
    by_stage = defaultdict(list)
    by_secondary = defaultdict(list)
    by_type = defaultdict(list)
    by_priority = defaultdict(list)
    by_signal_keyword = defaultdict(list)

    for L in logics:
        by_stage[L["stage"]].append(L["id"])
        for tag in L["secondary_tags"]:
            by_secondary[tag].append(L["id"])
        if L.get("type"):
            by_type[L["type"]].append(L["id"])
        by_priority[L.get("boss_priority", "medium")].append(L["id"])
        for sig in L.get("trigger_signals", []):
            by_signal_keyword[sig].append(L["id"])

    return {
        "by_stage": dict(by_stage),
        "by_secondary_tag": dict(by_secondary),
        "by_type": dict(by_type),
        "by_priority": dict(by_priority),
        "by_trigger_signal": dict(by_signal_keyword),
    }


def build_tag_summary(logics):
    sec = Counter()
    cat_counter = defaultdict(Counter)
    types = Counter()
    stages = Counter()
    priorities = Counter()

    for L in logics:
        stages[L["stage"]] += 1
        types[L.get("type", "")] += 1
        priorities[L.get("boss_priority", "medium")] += 1
        for tag in L["secondary_tags"]:
            sec[tag] += 1
            if ":" in tag:
                cat = tag.split(":", 1)[0]
                cat_counter[cat][tag] += 1

    return {
        "total_logics": len(logics),
        "by_stage": dict(stages),
        "by_type": dict(types),
        "by_priority": dict(priorities),
        "secondary_tags_top": sec.most_common(),
        "secondary_categories": {
            cat: dict(c.most_common()) for cat, c in cat_counter.items()
        },
    }


def consolidate_with_llm(logics):
    """Optional: one LLM call to merge near-duplicates into canonical entries."""
    import anthropic

    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("ANTHROPIC_API_KEY not set; cannot consolidate", file=sys.stderr)
        sys.exit(1)

    model = os.environ.get("MODEL", "claude-opus-4-7")
    print(f"consolidating {len(logics)} logics via {model}…")

    # Trim to fields the model needs
    trimmed = [
        {
            "id": L["id"],
            "stage": L["stage"],
            "secondary_tags": L["secondary_tags"],
            "logic": L["logic"],
            "type": L.get("type", ""),
            "trigger_signals": L.get("trigger_signals", []),
            "anti_pattern": L.get("anti_pattern", ""),
            "boss_priority": L.get("boss_priority", "medium"),
        }
        for L in logics
    ]

    system = """你是一位知识库编辑专家。我会给你一个销售分析逻辑列表（来自一位资深销售总监刁姐对多次接诊的点评提炼），里面有大量语义重复或近似的条目。

请你做三件事：
1. 把语义相同/相近的逻辑合并为一条 canonical 逻辑；保留最精炼、最可注入 prompt 的措辞。
2. 合并时，把每条 canonical 逻辑覆盖到的原始条目 id 收集到 source_logic_ids 字段。
3. 给每条 canonical 逻辑分配统一的二级标签（合并和清洗各原条目的 secondary_tags），保持"类别:具体值"的格式。

【输出】严格 JSON：
{
  "tag_taxonomy": {
    "L1_stages": ["进房间前", "中途到房间里", "从房间出来", "跨阶段"],
    "L2_categories": {
      "<类别名>": ["<该类别下的具体值>", ...]
    }
  },
  "canonical_logics": [
    {
      "id": "C001",
      "stage": "...",
      "secondary_tags": ["类别:值", ...],
      "logic": "<精炼后的逻辑>",
      "type": "...",
      "trigger_signals": [...],
      "anti_pattern": "",
      "boss_priority": "high|medium|low",
      "source_logic_ids": ["L0003","L0017",...]
    }
  ]
}
仅输出 JSON。"""

    client = anthropic.Anthropic()
    user_prompt = (
        "原始逻辑列表（JSON）：\n```json\n"
        + json.dumps(trimmed, ensure_ascii=False)
        + "\n```\n请按 system 中的要求输出 canonical 化结果。"
    )

    kwargs = dict(
        model=model,
        max_tokens=64000,
        system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
        messages=[{"role": "user", "content": user_prompt}],
    )
    with client.messages.stream(**kwargs) as stream:
        msg = stream.get_final_message()
    text = "".join(b.text for b in msg.content if b.type == "text").strip()
    if not text:
        raise RuntimeError(
            f"empty response from model (stop_reason={msg.stop_reason}, "
            f"blocks={[b.type for b in msg.content]})"
        )
    if text.startswith("```"):
        text = text.split("\n", 1)[1]
        if text.endswith("```"):
            text = text[:-3]
        text = text.strip()
    return json.loads(text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--consolidate", action="store_true",
                        help="extra LLM pass to merge near-duplicates")
    args = parser.parse_args()

    logics, comparisons = collect()
    print(f"collected {len(logics)} logics from {len(comparisons)} rows")

    OUT_LIB.write_text(json.dumps(logics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {OUT_LIB}")

    index = build_index(logics)
    OUT_INDEX.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {OUT_INDEX}")

    tags = build_tag_summary(logics)
    OUT_TAGS.write_text(json.dumps(tags, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {OUT_TAGS}")

    OUT_COMPARE.write_text(json.dumps(comparisons, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {OUT_COMPARE}")

    if args.consolidate:
        canon = consolidate_with_llm(logics)
        OUT_CANON.write_text(json.dumps(canon, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"wrote {OUT_CANON}  ({len(canon.get('canonical_logics', []))} canonical logics)")


if __name__ == "__main__":
    main()
