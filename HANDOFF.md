# 项目交接文档（HANDOFF）

> 给在新对话里接手这个项目的人/AI 看的：读完这个文件就能知道我们做了啥、文件结构、设计决策、当前状态、下一步怎么走。

---

## 1. 项目目标

老板（"刁姐"）想做一个 **AI 销售接诊分析系统**，用来评估销售顾问对每位顾客的整个接待流程，给诊断和改进建议。我们已经有竞品 AI 在做这件事，但竞品经常漏掉关键问题或者归因错误。刁姐自己人工点评的视角更准。

**这一阶段（v1）的任务**：把 `data.xlsx` 里 67 条历史接诊数据 —— 每条包含语音转文字 + 竞品 AI 分析 + 刁姐人工点评 —— 通过 Claude API 提炼成一个**带分层标签的"分析逻辑库"**，作为后续真正的分析系统在动态构建 prompt 时的"召回素材"：

- 识别新接诊里的对话信号 → 命中标签 → 从逻辑库召回对应的"刁姐式分析逻辑" → 注入 system prompt → 系统就能模仿刁姐的视角分析新数据。

---

## 2. 工作目录

```
/Users/ginlater/code/persist创业/sm身美/gp/v1/
├── data.xlsx                     # 原始 67 条接诊（67 行有效）
├── extract_data.py               # ① 读 xlsx → output/raw_rows.json
├── analyze.py                    # ② 每行调 Claude → output/per_row/*.json
├── aggregate.py                  # ③ 汇总成逻辑库 + 标签索引
├── requirements.txt              # anthropic>=0.92.0, openpyxl>=3.1.0
├── README.md                     # 用户向运行说明
├── HANDOFF.md                    # 你正在看的这个文件
└── output/
    ├── raw_rows.json             # ① 的输出（已生成，67 条）
    ├── per_row/                  # ② 的输出（已生成 37 个文件）
    │   ├── {会员卡号}.json
    │   └── ...
    ├── debug/                    # ② 失败时落盘原文，用于排错
    ├── errors.log                # ② 的错误堆栈
    ├── logic_library.json        # ③ 的输出（待生成）
    ├── tag_index.json            # ③ 的输出（待生成）
    ├── tags_summary.json         # ③ 的输出（待生成）
    └── comparison_report.json    # ③ 的输出（待生成）
```

---

## 3. data.xlsx 的列（重要：作为后续 ETL 的源头）

工作表名 **`工牌数据`**，41 列，顶部 1 行表头，下面 196 行（其中 67 行有效，其余空）。关键列：

| 列名 | 含义 |
|---|---|
| 会员卡号 | 主键 |
| 客人（新/老）、进店渠道、是否成交（1/0）、成交金额、门店、员工编号/姓名 | 元数据 |
| **刁姐评价** | 老板人工点评，**最重要的输入**（67 行里只有 38 行有这列） |
| 进房间前的语音转文字 1/2/3 | 阶段一录音（cols 13/15/17，对应音频列被忽略） |
| 中途到房间里提供情绪价值的语音转文字 1-5 | 阶段二（cols 19/21/23/25/27） |
| 从房间出来的语音转文字 1-2 | 阶段三（cols 29/31） |
| 接诊质量评分（数字）、评分文字、综合评分文字、客户画像标签、未成交分析、成交分析、下一步行动建议、AI 总结 | **竞品 AI 输出**（cols 33-40） |

录音转文字格式举例（保留时间戳，剥掉无意义的 `null：` 说话人前缀）：
```
开始时间：00:00:00
之后有团购吗？对我帮你验一下了。
结束时间：00:00:06
```

---

## 4. 三阶段 Pipeline

### ① extract_data.py — 抽取（已完成）

纯 Python，零 LLM 调用。读 xlsx，把每行规范化成一个 dict，三个阶段的 transcripts 各自合并，输出 `output/raw_rows.json`。

每行 dict 字段：`card_no, member_name, is_new, channel, deal, deal_amount, store, staff_no, staff_name, boss_review, service_date, start_time, end_time, comp_score, comp_score_text, comp_score_text_combined, comp_customer_tags, comp_no_deal_analysis, comp_deal_analysis, comp_next_action, comp_ai_summary, before_room_transcript, mid_room_transcript, after_room_transcript`。

**67 条里 38 条有 `boss_review`，60 条有竞品分析。** 没有 `boss_review` 的行后续会被 analyze.py 跳过（没东西可学）。

### ② analyze.py — 每行对比 + 抽取逻辑（进行中，已完成 37/38）

对每行调 Claude API，做三件事：
- A. 对比刁姐 vs 竞品：找出 `competitor_missing_logic`（竞品漏的）/`competitor_wrong_logic`（竞品错的）/`boss_strengths`（刁姐独到的视角）
- B. 抽取可复用的分析逻辑 `extracted_logics`（重点）
- C. 给每条逻辑打一级 stage 标签 + 二级 `类别:具体值` 标签

特性：
- 并发 6（`MAX_WORKERS`）
- 断点续传：每行写一个独立 JSON 到 `output/per_row/`，已存在就跳过
- prompt cache：system prompt 上挂了 `cache_control: ephemeral`，第二行起命中
- 失败原文留在 `output/debug/`，错误堆栈在 `output/errors.log`

**当前推荐运行配置**（已经验证跑得通）：
```bash
THINKING=disabled MAX_TOKENS=8000 EFFORT=medium python analyze.py
```

环境变量：
| 变量 | 默认 | 说明 |
|---|---|---|
| `MODEL` | `claude-sonnet-4-6` | 也可 `claude-opus-4-7` |
| `MAX_WORKERS` | `6` | |
| `EFFORT` | `high` | low/medium/high/max |
| `THINKING` | `adaptive` | adaptive 或 disabled |
| `MAX_TOKENS` | `32000` | adaptive thinking 时这个值要够大 |

### ③ aggregate.py — 汇总成逻辑库（**未运行**）

读 `output/per_row/*.json`，输出：
- `logic_library.json` —— 全部 logics 扁平 list，每条带 `id` (`L0001`...) 和来源 `card_no`
- `tag_index.json` —— 多个倒排索引（见下文 §6）
- `tags_summary.json` —— 标签分布统计
- `comparison_report.json` —— 把每行的"竞品缺/竞品错/刁姐强"抽出来汇总

可选：`python aggregate.py --consolidate` —— 再调一次 LLM 把近义逻辑合并成 canonical 条目，输出 `logic_library_canonical.json`。

---

## 5. 当前状态

- ✅ extract_data.py 跑过，`output/raw_rows.json` 已生成（67 行）
- 🟡 analyze.py 跑过 2 轮，**已成功 37 个**（38 个有 boss_review 的里）。**最后还有 1 条待补**。
  - 推测剩下 1 条原本失败、JSON 修复函数加上后还没补跑。直接重跑就会跳过已完成、补上失败那条：
    ```bash
    rm -rf output/debug
    THINKING=disabled MAX_TOKENS=8000 EFFORT=medium python analyze.py
    ```
- ⬜ aggregate.py 还没跑过

下一步：把缺的那条补完 → `python aggregate.py` → 看 `tags_summary.json` 决定要不要跑 `--consolidate`。

---

## 6. 输出 JSON 的结构（**这是最关键的部分**）

### 6.1 `output/per_row/{card_no}.json` —— 单行分析结果

```jsonc
{
  "card_no": "028G3AG187",
  "member_name": "吴女士",
  "deal": 1,                   // 0/1
  "is_new": "老",              // 新/老
  "channel": null,
  "store": "006003FY成都西财店",
  "staff_name": "王新梅",
  "model": "claude-sonnet-4-6",
  "analysis": {
    "row_summary": "<一句话总结这次接诊和刁姐核心点评>",

    // ↓ 竞品漏掉的视角
    "competitor_missing_logic": [
      {
        "point": "<具体问题>",
        "boss_signal_quote": "<刁姐原话片段>"
      }
    ],

    // ↓ 竞品判断错的地方
    "competitor_wrong_logic": [
      {
        "competitor_said": "<竞品判断概述>",
        "boss_correction": "<刁姐的更正>",
        "why_boss_is_right": "<为什么刁姐对>"
      }
    ],

    // ↓ 刁姐这次的独到视角
    "boss_strengths": [
      { "angle": "<视角名>", "explanation": "<为什么这视角关键>" }
    ],

    // ↓↓↓ 最关键：抽出来的可复用分析逻辑 ↓↓↓
    "extracted_logics": [
      {
        "stage": "进房间前 | 中途到房间里 | 从房间出来 | 跨阶段",
        "secondary_tags": ["接诊环节:破冰", "话术:赞美", ...],
        "logic": "<可注入 prompt 的抽象规则，1-3 句>",
        "type": "诊断逻辑 | 话术逻辑 | 流程逻辑 | 心理洞察 | 风险预警 | 追单逻辑 | 顾客画像逻辑",
        "trigger_signals": ["<对话/录音中能触发该逻辑的关键词>", ...],
        "boss_priority": "high | medium | low",
        "anti_pattern": "<这条逻辑要避免的错误做法，可空>"
      }
    ]
  }
}
```

### 6.2 `output/logic_library.json` —— 逻辑库（aggregate 后的扁平列表）

```jsonc
[
  {
    "id": "L0001",
    "stage": "中途到房间里",
    "secondary_tags": ["顾客性格:慢热", "话术:赞美", "风险与误区:过度赞美"],
    "logic": "对慢热型顾客避免连续表扬赞美，会触发防备...",
    "type": "心理洞察",
    "trigger_signals": ["顾客回应少", "新客", "性格内向"],
    "anti_pattern": "上来就连续表扬",
    "boss_priority": "high",
    "source_card_no": "251101006006006",
    "source_file": "251101006006006.json"
  }
]
```

### 6.3 `output/tag_index.json` —— 倒排索引（**给检索系统直接用**）

```jsonc
{
  "by_stage":          { "进房间前": ["L0001","L0007", ...] },
  "by_secondary_tag":  { "顾客性格:慢热": ["L0042","L0058"] },
  "by_type":           { "心理洞察": [...] },
  "by_priority":       { "high": [...], "medium": [...] },
  "by_trigger_signal": { "顾客回应少": [...] }    // ← 召回用得最多
}
```

**未来分析系统的工作流**：
1. 接到新接诊数据
2. 用规则/embedding 提取对话信号 → 比如识别出"顾客性格慢热"
3. 在 `by_secondary_tag` / `by_trigger_signal` 里查命中的 logic IDs
4. 去 `logic_library.json` 拿 `logic` 字段
5. 拼进 system prompt → 让 Claude 用刁姐的视角分析这条新数据

---

## 7. 标签体系（重要决策）

### 一级标签 stage（4 选 1，强约束）

`进房间前 | 中途到房间里 | 从房间出来 | 跨阶段`

前三个对应数据里的三个录音阶段；"跨阶段"用于真正贯穿全程的逻辑。

### 二级标签 secondary_tags（`类别:具体值` 格式，2-5 个/条）

system prompt 里建议了这些类别（也允许新增，要保持类别名一致）：
- 顾客类型 / 顾客性格 / 顾客状态 / 进店渠道 / 客人新老
- 接诊环节 / 销售动作 / 话术
- 需求类型 / 顾客痛点 / 顾客画像
- 心理与情绪 / 信任建立 / 异议处理
- 专业能力 / 风险与误区 / 成交引导 / 追单逻辑

**当前 37 条已分析数据的标签分布**（341 条 logic 抽出）：
- by stage：`进房间前 120 / 从房间出来 130 / 跨阶段 52 / 中途到房间里 39`
- by priority：`high 242 / medium 99`
- 二级类别 top：`接诊环节 169 / 话术 153 / 成交引导 151 / 风险与误区 123 / 专业能力 93 / 心理与情绪 90 / 信任建立 85 / 销售动作 84 / 追单逻辑 80 / 顾客画像 70`

`tags_summary.json`（aggregate 后会有）会列出每个二级具体值的频次，可以拿来 review 是否需要清洗 / 收敛。

---

## 8. 重要技术决策（踩过的坑）

1. **THINKING 默认从 `adaptive` 改回 `disabled`**：
   - adaptive thinking 在 sonnet-4-6 上很激进，吃光了 max_tokens（8K → 16K → 32K 都不够，常出 `stop_reason=max_tokens`）
   - 这个任务是结构化抽取，不需要深思考。`THINKING=disabled MAX_TOKENS=8000` 又快又稳

2. **Structured outputs (`output_config.format`) 试过但效果不稳**：
   - 已经在 `analyze.py` 里挂上了完整 JSON Schema，但实际测试 sonnet-4-6 仍然会输出非法 JSON
   - 不知道是 SDK 0.94.0 的问题还是 API 的当前状态
   - **不依赖它**，用本地 JSON 修复兜底（见 #3）

3. **JSON 修复函数 `repair_inline_quotes`**：
   - 模型常见错误：用 ASCII `"` 在中文字符串内部作引号（如 `"point": "竞品没识别出"顾客眼部需求"这个信号"`），导致 JSON 字符串被提前闭合
   - 修复策略：遍历字符串，在 string 状态中遇到 `"`，向后跳过空白看下一个字符是否为 `,}]:` 或 EOF；不是的话就把这个 `"` 视为内嵌引号并转义为 `\"`
   - 已经验证：之前失败的 11 条 debug 文件 100% 修复成功

4. **prompt caching**：
   - system prompt 上挂了 `cache_control: ephemeral`
   - 第一次调用是写缓存（1.25× 价格），后续 5 分钟内同 prefix 全部命中（0.1× 价格）
   - 67 行实测节约了大头成本

5. **没有 boss_review 的行直接跳过**：
   - 67 行里 29 行没有 boss_review，跳过它们，因为没东西可学
   - 实际只对 38 行调了 LLM

---

## 9. 下次开新对话时该怎么做

1. 读这个 `HANDOFF.md`
2. `ls output/per_row/ | wc -l` 看进度
3. 如果还没 38 条：
   ```bash
   rm -rf output/debug
   THINKING=disabled MAX_TOKENS=8000 EFFORT=medium python analyze.py
   ```
4. 跑 aggregate：
   ```bash
   python aggregate.py
   ```
5. 看 `output/tags_summary.json` 评估二级标签是否需要规整。如果觉得有大量近义/重复 logics，再跑一次 LLM 合并：
   ```bash
   python aggregate.py --consolidate
   ```
6. 然后才进入下一阶段：基于 `logic_library.json` + `tag_index.json` 设计真正的实时分析系统的 prompt 拼装逻辑。

---

## 10. 已知待办 / 后续可做

- [ ] 把缺的最后 1 条 analyze.py 跑完
- [x] 跑 aggregate.py 出逻辑库 + 标签索引（已完成，341 条 logics）
- [x] 实时分析系统 v1（见 §11）
- [ ] （可选）跑 `aggregate.py --consolidate` 合并近义逻辑
- [ ] review `tags_summary.json` 中的二级标签，必要时合并/拆分类别
- [ ] 评估：拿 5-10 条没 boss_review 的数据，让新系统分析一遍，由刁姐打分对比"裸 prompt"和"带逻辑库 prompt"的效果差

---

## 11. v2 — 实时分析对比系统

**已实现**：输入三阶段录音转文字 → 并行调两次 Claude → 前端 SSE 流式渲染我们的分析 vs 竞品风格基线。

### 文件
- `server.py` —— Flask + SSE 后端
- `web/index.html` `web/app.css` `web/app.js` —— 单页前端
- `requirements.txt` 增加了 `flask>=3.0.0`

### 启动
```bash
pip install -r requirements.txt
export ANTHROPIC_API_KEY=sk-ant-...
python server.py
# 默认 http://127.0.0.1:5057  · MODEL=claude-sonnet-4-6  · PORT=5057 可改
```

### 核心设计
- 启动时把 `output/logic_library.json`（341 条）拼成紧凑文本注入 OURS 的 system prompt（约 35K tokens）
- `cache_control: ephemeral` 挂在 OURS system 上，第二次起命中缓存（实测 cache_creation 107K → 后续 cache_read 命中后单次 input 几百 token）
- 信号预匹配：trigger_signals 子串扫输入文本，前端"命中信号"侧栏高亮（meta 事件）
- 两路并行：用 `threading.Thread` + `queue.Queue` 把两个 Claude 流多路复用到同一个 SSE 响应里
- 事件类型：`meta` / `ours_delta` / `comp_delta` / `ours_done` / `comp_done` / `ours_error` / `comp_error` / `done`

### 接口
| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/` | 单页前端 |
| GET | `/api/library/stats` | 逻辑库总览（前端 header 用） |
| GET | `/api/demo?idx=N` | 取一条三阶段齐全的样例（前端"载入演示数据"按钮）。返回还包含原始的 `boss_review_actual` 和 `competitor_actual`，前端会折叠显示供对照 |
| POST | `/api/analyze` | 流式 SSE，body `{before, mid, after}` 三个阶段文本 |

### 输出 JSON 形态
- **OURS**：`score / score_text / stage_assessments(三阶段细分) / customer_tags / deal_likelihood / deal_or_no_deal_analysis / next_actions[{action,script}] / key_insights[{logic_id,stage,signal_quoted,boss_view}] / risk_warnings[{logic_id,what_happened,consequence}] / ai_summary / boss_summary`
- **COMP**（竞品基线）：`score / score_text / customer_tags / deal_or_no_deal_analysis / next_actions[] / ai_summary`

OURS 比 COMP 多的是：阶段细分评分 + 命中 logic 的 `logic_id` 引用 + 反面做法预警 + 刁姐口吻一句话点评。

### 已踩过的坑
1. 本机 curl 走代理（`http_proxy=127.0.0.1:7890`），测 localhost 时记得 `--noproxy '*'`
2. 模型偶尔会用 ```json``` 包裹输出，前端 `tryParseJSON` 已剥；同时取 `{...}` 子串做兜底
3. THINKING 强制 `disabled` —— 跟 analyze.py 同样的理由，结构化抽取不需要思考
4. 三阶段+整库的总输入约 50K（demo 数据），加上 max_tokens 8000，仍在 sonnet-4-6 200K 上下文内
