"""
Step 1: read data.xlsx -> output/raw_rows.json

Deterministic extraction. No LLM calls.
"""
import json
import os
import re
from pathlib import Path

import openpyxl

HERE = Path(__file__).parent
XLSX = HERE / "data.xlsx"
OUT = HERE / "output" / "raw_rows.json"

COL_MAP = {
    "card_no":            0,   # 会员卡号
    "member_name":        1,   # 会员姓名
    "is_new":             2,   # 客人（新/老）
    "channel":            3,   # 进店渠道
    "deal":               4,   # 是否成交（1/0）
    "deal_amount":        5,   # 成交金额
    "store":              6,   # 门店
    "staff_no":           7,   # 员工编号
    "staff_name":         8,   # 员工姓名
    "boss_review":        9,   # 刁姐评价
    "service_date":      10,
    "start_time":        11,
    "end_time":          12,
    # 进房间前 文字 col index: 13,15,17
    # 中途           文字 col index: 19,21,23,25,27
    # 从房间出来 文字 col index: 29,31
    "comp_score":        33,
    "comp_score_text":   34,
    "comp_score_text_combined": 35,
    "comp_customer_tags": 36,
    "comp_no_deal_analysis": 37,
    "comp_deal_analysis": 38,
    "comp_next_action":  39,
    "comp_ai_summary":   40,
}

BEFORE_ROOM_TEXT_COLS = [13, 15, 17]
MID_ROOM_TEXT_COLS = [19, 21, 23, 25, 27]
AFTER_ROOM_TEXT_COLS = [29, 31]


def clean_transcript(text: str) -> str:
    """Strip 'null：' speaker prefix, keep timestamps and content."""
    if not text:
        return ""
    text = str(text)
    # 'null：' is the placeholder when speaker is unknown; the time blocks are useful, keep them
    text = re.sub(r"^null：", "", text, flags=re.MULTILINE)
    return text.strip()


def join_stage(row, cols):
    """Concatenate non-empty transcripts from the same stage with separator."""
    parts = []
    for i, col in enumerate(cols, start=1):
        val = row[col]
        if val is None:
            continue
        s = clean_transcript(val)
        if not s:
            continue
        parts.append(f"--- 段{i} ---\n{s}")
    return "\n\n".join(parts)


def empty_to_none(v):
    if v is None:
        return None
    s = str(v).strip()
    return s if s else None


def extract_row(row):
    if row[0] is None:
        return None
    out = {}
    for key, idx in COL_MAP.items():
        v = row[idx]
        if hasattr(v, "isoformat"):
            v = v.isoformat()
        out[key] = empty_to_none(v) if not isinstance(v, (int, float)) else v
    out["before_room_transcript"] = join_stage(row, BEFORE_ROOM_TEXT_COLS)
    out["mid_room_transcript"] = join_stage(row, MID_ROOM_TEXT_COLS)
    out["after_room_transcript"] = join_stage(row, AFTER_ROOM_TEXT_COLS)
    return out


def main():
    wb = openpyxl.load_workbook(XLSX, data_only=True)
    ws = wb["工牌数据"]
    rows = []
    for row in ws.iter_rows(min_row=2, values_only=True):
        item = extract_row(row)
        if item is None:
            continue
        rows.append(item)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)

    print(f"wrote {len(rows)} rows -> {OUT}")
    has_boss = sum(1 for r in rows if r.get("boss_review"))
    has_comp = sum(1 for r in rows if r.get("comp_ai_summary") or r.get("comp_score_text"))
    print(f"  with boss_review:        {has_boss}")
    print(f"  with competitor analysis: {has_comp}")


if __name__ == "__main__":
    main()
