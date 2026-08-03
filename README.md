# 刁姐分析逻辑提取 Pipeline

把 `data.xlsx` 里 67 条接诊数据（语音转文字 + 竞品 AI 分析 + 刁姐人工点评）
提炼成可注入 prompt 的、带分层标签的"分析逻辑库"，
为后续动态构建分析 prompt 的系统提供检索素材。

## 输出物

- `output/raw_rows.json` —— xlsx 抽出的标准化结构化行数据
- `output/per_row/{会员卡号}.json` —— 每行一个 JSON：
  - 与竞品 AI 的对比分析（竞品漏掉的 / 错的，刁姐独到的视角）
  - 从刁姐点评里抽出的 `extracted_logics`（带 stage/二级标签/触发信号/优先级）
- `output/logic_library.json` —— 全部抽取出的逻辑，扁平 list，每条带 ID
- `output/tag_index.json` —— 多个倒排索引：
  - `by_stage` 按一级标签（进房间前 / 中途到房间里 / 从房间出来 / 跨阶段）
  - `by_secondary_tag` 按二级标签
  - `by_type` 按逻辑类型（诊断逻辑/话术逻辑/流程逻辑/...）
  - `by_priority` 按重要程度
  - `by_trigger_signal` 按触发关键词（最贴近"召回"用法）
- `output/tags_summary.json` —— 标签分布统计，方便人工 review L2 体系
- `output/comparison_report.json` —— 把每行的 "竞品缺/竞品错/刁姐强" 三块抽出来，整体看竞品在哪些方面系统性不足
- `output/logic_library_canonical.json` —— **可选**，再过一次 LLM 把近义逻辑合并成 canonical 条目，配套统一的标签 taxonomy

## 安装

```bash
pip install -r requirements.txt
export ANTHROPIC_API_KEY=sk-ant-...
```

## 运行

```bash
# 1. 抽取 xlsx，零成本，秒级
python extract_data.py

# 2. 调 Claude 做对比分析。默认并发 6，断点续传。
#    模型默认 claude-opus-4-7，可换 claude-sonnet-4-6 降本。
python analyze.py
# 或者：
MODEL=claude-sonnet-4-6 MAX_WORKERS=8 EFFORT=high python analyze.py

# 3. 汇总成逻辑库 + 标签索引
python aggregate.py

# 3b. 可选：再过一次 LLM 把近义逻辑合并成 canonical 条目（一次大调用，便宜）
python aggregate.py --consolidate
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `ANTHROPIC_API_KEY` | —— | 必填 |
| `MODEL` | `claude-opus-4-7` | 也可选 `claude-sonnet-4-6`、`claude-haiku-4-5` |
| `MAX_WORKERS` | `6` | analyze.py 的并发数 |
| `EFFORT` | `high` | `low/medium/high/max` |
| `THINKING` | `adaptive` | `adaptive` 或 `disabled` |

## 标签设计（说明）

- **一级（stage）**：和你说的一致 —— `进房间前 / 中途到房间里 / 从房间出来 / 跨阶段`
- **二级（secondary_tags）**：要求 `类别:具体值` 格式，建议类别（也允许新增）：
  - 顾客类型 / 顾客性格 / 顾客状态 / 进店渠道 / 客人新老
  - 接诊环节 / 销售动作 / 话术
  - 需求类型 / 顾客痛点 / 顾客画像
  - 心理与情绪 / 信任建立 / 异议处理
  - 专业能力 / 风险与误区 / 成交引导 / 追单逻辑

后续构建 prompt 时，从对话中识别出的信号 → 命中标签 → 从 `tag_index.json` 召回对应 `logic` → 拼接进系统 prompt。`trigger_signals` 字段就是为这个召回设计的。

## 续跑 / 重跑

- `analyze.py` 每行写一个独立 JSON，已存在的就跳过。删掉对应文件即可重新分析。
- 出错的行写到 `output/errors.log`，不会中断整体流程。
- 如果想强制全部重跑：`rm -rf output/per_row && python analyze.py`

## 成本预估

- 67 行 × 平均输入 5–10K token（含语音转文字）+ 输出 1–3K token
- Opus 4.7 全跑大约 $5–10；Sonnet 4.6 约 $2–4
- system prompt 用了 prompt cache，第二行起命中缓存，实际更便宜
