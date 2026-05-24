"""
Run analysis on raw_rows_new.json (new rows extracted from updated xlsx).
Same logic as analyze.py but reads output/raw_rows_new.json instead.
"""
import json
import os
import sys
import time
import traceback
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

# Patch the RAW path before importing analyze internals
import analyze as _a

HERE = Path(__file__).parent
RAW_NEW = HERE / "output" / "raw_rows_new.json"

# Borrow everything from analyze
call_one = _a.call_one
output_path_for = _a.output_path_for
process_row = _a.process_row
PER_ROW_DIR = _a.PER_ROW_DIR
ERROR_LOG = _a.ERROR_LOG
MODEL = _a.MODEL
MAX_WORKERS = _a.MAX_WORKERS

import anthropic


def main():
    if not RAW_NEW.exists():
        print(f"not found: {RAW_NEW}\nrun: python extract_new_rows.py first", file=sys.stderr)
        sys.exit(1)
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("ANTHROPIC_API_KEY not set", file=sys.stderr)
        sys.exit(1)

    PER_ROW_DIR.mkdir(parents=True, exist_ok=True)

    with open(RAW_NEW, encoding="utf-8") as f:
        rows = json.load(f)

    client = anthropic.Anthropic()

    todo = [r for r in rows if not output_path_for(r).exists()
            and (r.get("boss_review") or "").strip()]
    skipped = len(rows) - len(todo)
    print(f"{len(rows)} rows total, {skipped} already done/no-review, {len(todo)} to process")
    print(f"model={MODEL}  workers={MAX_WORKERS}")

    results = {"ok": [], "skip": [], "error": []}
    t0 = time.time()

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as pool:
        futures = {pool.submit(process_row, client, row): row for row in todo}
        for i, fut in enumerate(as_completed(futures), 1):
            row = futures[fut]
            card, status = fut.result()
            bucket = "ok" if status == "ok" else ("skip" if status.startswith("skip") else "error")
            results[bucket].append(card)
            elapsed = time.time() - t0
            print(f"[{i}/{len(todo)}] {card}: {status}  ({elapsed:.0f}s)")

    print(f"\ndone — ok:{len(results['ok'])}  skip:{len(results['skip'])}  error:{len(results['error'])}")
    if results["error"]:
        print("errors:", results["error"])
        print(f"see {ERROR_LOG}")


if __name__ == "__main__":
    main()
