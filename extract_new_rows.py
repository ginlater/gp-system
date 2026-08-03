"""
Extract new rows from 工牌数据汇总（天级更新）.xlsx that have boss_review
but haven't been analyzed yet (no per_row/*.json file).

Writes output/raw_rows_new.json — feed this into analyze_new.py.

Column mapping for the new xlsx (2 extra cols vs original data.xlsx):
  col 9  = 刁姐评价
  col 10 = 总经理评价 (new)
  col 11 = 是否是正样本 (new)
  col 12 = 服务日期  (was 10)
  col 13 = 开始时间  (was 11)
  col 14 = 结束时间  (was 12)
  before_room text: 15,17,19  (was 13,15,17)
  mid_room text:    21,23,25,27,29  (was 19,21,23,25,27)
  after_room text:  31,33  (was 29,31)
  comp_score: 37, score_text: 38, combined: 39, tags: 40,
  no_deal: 41, deal: 42, next_action: 43, ai_summary: 44
"""
import json
import re
from pathlib import Path

import openpyxl

HERE = Path(__file__).parent
XLSX = HERE / "工牌数据汇总（天级更新）.xlsx"
PER_ROW_DIR = HERE / "output" / "per_row"
OUT = HERE / "output" / "raw_rows_new.json"

BEFORE_ROOM_TEXT_COLS = [15, 17, 19]
MID_ROOM_TEXT_COLS = [21, 23, 25, 27, 29]
AFTER_ROOM_TEXT_COLS = [31, 33]


def clean_transcript(text: str) -> str:
    if not text:
        return ""
    text = str(text)
    text = re.sub(r"^null：", "", text, flags=re.MULTILINE)
    return text.strip()


def join_stage(row, cols):
    parts = []
    for i, col in enumerate(cols, start=1):
        if col >= len(row):
            continue
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
    card_no = row[0]
    if card_no is None:
        return None

    def get(i):
        return row[i] if i < len(row) else None

    v = get(4)
    deal = v if isinstance(v, (int, float)) else (empty_to_none(v))

    v = get(5)
    deal_amount = v if isinstance(v, (int, float)) else (empty_to_none(v))

    v = get(12)
    service_date = v.isoformat() if hasattr(v, "isoformat") else empty_to_none(v)

    v = get(13)
    start_time = v.isoformat() if hasattr(v, "isoformat") else empty_to_none(v)

    v = get(14)
    end_time = v.isoformat() if hasattr(v, "isoformat") else empty_to_none(v)

    return {
        "card_no": empty_to_none(card_no),
        "member_name": empty_to_none(get(1)),
        "is_new": empty_to_none(get(2)),
        "channel": empty_to_none(get(3)),
        "deal": deal,
        "deal_amount": deal_amount,
        "store": empty_to_none(get(6)),
        "staff_no": empty_to_none(get(7)),
        "staff_name": empty_to_none(get(8)),
        "boss_review": empty_to_none(get(9)),
        "service_date": service_date,
        "start_time": start_time,
        "end_time": end_time,
        "comp_score": empty_to_none(get(37)),
        "comp_score_text": empty_to_none(get(38)),
        "comp_score_text_combined": empty_to_none(get(39)),
        "comp_customer_tags": empty_to_none(get(40)),
        "comp_no_deal_analysis": empty_to_none(get(41)),
        "comp_deal_analysis": empty_to_none(get(42)),
        "comp_next_action": empty_to_none(get(43)),
        "comp_ai_summary": empty_to_none(get(44)),
        "before_room_transcript": join_stage(row, BEFORE_ROOM_TEXT_COLS),
        "mid_room_transcript": join_stage(row, MID_ROOM_TEXT_COLS),
        "after_room_transcript": join_stage(row, AFTER_ROOM_TEXT_COLS),
    }


def main():
    already_processed = {p.stem for p in PER_ROW_DIR.glob("*.json")}
    print(f"already processed: {len(already_processed)} cards")

    wb = openpyxl.load_workbook(XLSX, data_only=True)
    ws = wb["工牌数据"]

    new_rows = []
    for row in ws.iter_rows(min_row=2, values_only=True):
        item = extract_row(row)
        if item is None:
            continue
        if not item.get("boss_review"):
            continue
        card = str(item["card_no"]).strip()
        safe = "".join(c for c in card if c.isalnum() or c in "_-")
        if safe in already_processed:
            continue
        new_rows.append(item)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(new_rows, f, ensure_ascii=False, indent=2)

    print(f"wrote {len(new_rows)} new rows -> {OUT}")
    for r in new_rows:
        print(f"  {r['card_no']}: {(r.get('boss_review') or '')[:40]}")


if __name__ == "__main__":
    main()
