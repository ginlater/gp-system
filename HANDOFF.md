# 工牌接诊分析系统 · 交接文档

> 写于 2026-05-18。供新对话 Claude 直接接手开发使用。

---

## 一、项目是什么

**身美医美工牌接诊分析系统**：老板（刁姐）上传顾问的接诊录音，系统自动语音转录（ASR）、调用刁姐蒸馏出的知识库（341 条逻辑）让 Claude 生成整体复盘报告和多维度评分，老板在网页上查看并可填写人工标注点评。

---

## 二、服务器信息

| 项 | 值 |
|---|---|
| 服务器 IP | 43.136.130.133（腾讯云成都） |
| 访问入口 | http://43.136.130.133/gp/ |
| 备用域名 | http://www.aibeautyfulwomen.cn/（.cn 未备案，可能不稳定） |
| 老板账号 | `diajie` / `Diajie2026!` |
| Flask 端口 | 5058（gunicorn，绑定 127.0.0.1） |
| 部署路径 | /app/gp-system/ |
| systemd 服务 | `gongpai.service`（`sudo systemctl restart gongpai`） |
| nginx 配置 | /etc/nginx/sites-available/followup-agent（IP:80 的 /gp/ 块） |
| 服务日志 | /var/log/gongpai-access.log, /var/log/gongpai-error.log |
| OSS | bucket=gongpai-asr, region=oss-cn-chengdu |
| ASR | DashScope fun-asr（阿里云百炼，API Key 在 .env） |
| Claude | claude-opus-4-7，**必须走代理** http://127.0.0.1:7890 |

---

## 三、代码结构

```
/app/gp-system/
├── webapp.py              # Flask 主应用（全部后端逻辑）
├── .env                   # 所有密钥（chmod 600，不进 git）
├── recordings.db          # SQLite 数据库
├── output/
│   └── logic_library.json # 知识库，341 条刁姐逻辑（只读）
├── web_v2/
│   ├── templates/
│   │   ├── base.html      # 布局基模板（注入 window.URL_PREFIX）
│   │   ├── login.html     # 登录页
│   │   ├── index.html     # 接诊列表 + 上传
│   │   └── detail.html    # 接诊详情（录音、评分卡、报告、点评）
│   └── static/
│       ├── app.js         # 前端所有交互逻辑
│       └── style.css      # 样式
└── test/                  # 5 段测试录音（白凤_肖玉_2026-04-25）
```

### webapp.py 关键函数

| 函数 | 作用 |
|---|---|
| `parse_filename()` | 从文件名解析顾客/顾问/时间（支持 `-` 和 `_` 分隔符） |
| `ingest_recording()` | 录音入库 + 关联/创建 session + 触发流水线 |
| `scan_oss_bucket()` | 扫描 OSS，新文件调 ingest_recording |
| `trigger_pipeline_for_recording()` | 启动后台 ASR 线程 |
| `_asr_then_maybe_analyze()` | ASR 完后检查 session 是否可触发分析 |
| `maybe_trigger_session_analysis()` | 所有录音 ASR done → 自动触发 Claude 分析 |
| `run_asr()` | 调 DashScope fun-asr，含重试（并发信号量=2） |
| `run_session_analysis()` | 多段 transcript 拼接 → Claude tool_use → 评分+报告 |
| `compute_stage_scores()` | 从 10 子维度分算阶段分和综合分 |
| `startup_kick()` | gunicorn 启动时自动扫描 OSS + 补跑未完成任务 |

---

## 四、数据库结构

### sessions（接诊，主体）

| 字段 | 说明 |
|---|---|
| id | 主键 |
| advisor | 顾问姓名 |
| customer | 顾客姓名 |
| service_date | 服务日期 YYYY-MM-DD |
| analysis_status | pending / running / done / failed |
| analysis_result | Claude 复盘报告 Markdown 正文 |
| analysis_scores | JSON，结构见下方「评分 JSON 结构」 |
| analysis_signature | 当前录音集合的 sha1，用于检测分析是否过期 |
| has_evaluation | 0/1，是否有老板人工点评 |
| UNIQUE | (advisor, customer, service_date) |

### recordings（录音段，多对一 sessions）

| 字段 | 说明 |
|---|---|
| session_id | FK → sessions.id |
| oss_key | OSS 对象 key（唯一） |
| advisor / customer / recorded_at / duration_label | 元数据（从文件名解析） |
| asr_status | pending / running / done / failed |
| asr_transcript | 转录文本（说话人分离，`[Xs-Ys] 说话人N: 文字` 格式） |

### evaluations（老板点评，一对一 sessions）

| 字段 | 说明 |
|---|---|
| session_id | FK + UNIQUE |
| wrong_points / improvement / correct_practice | 三栏人工标注 |

---

## 五、评分 JSON 结构（analysis_scores 字段）

```json
{
  "overall": 6.7,
  "stages": {
    "pre_room": 7.0,
    "in_room": 7.0,
    "post_room": 6.0
  },
  "highlights": "情感连接8分：关怀自然到位；成交引导7分：共创卡拿下2万",
  "weaknesses": "效果确认5分：出房未做结构化效果复盘；客情收尾6分：未引导好评",
  "dimensions": {
    "档案掌握与破冰": {
      "score": 7,
      "evaluation": "引用录音时间戳的具体评价（2-3句）",
      "improvement": "具体可执行的改进建议（1-2句）"
    }
    // ... 其余 9 个维度同结构
  }
}
```

**10 个子维度分组**：
- **进房前（pre_room）**：档案掌握与破冰、情感连接与共情、今日方案铺垫
- **房中（in_room）**：需求洞察与挖掘、专业讲解与检测解读、节奏与情绪维护
- **房后（post_room）**：效果确认与价值放大、成交引导、异议处理、客情收尾与售后交代

---

## 六、文件命名约定（重要）

格式：`顾客-顾问-录音YYYYMMDDHHMMSS[-_]时长.mp3`

例：
- `白凤_肖玉_录音20260425100202_07分钟32秒.mp3` → 顾客=白凤，顾问=肖玉
- `吕女士-刘佳玲-录音20260510114613-01分钟56秒.mp3` → 顾客=吕女士，顾问=刘佳玲

**顾客在前，顾问在后**（之前搞反过，导致分析角色颠倒，已踩坑记录在此）。

同一 (顾问, 顾客, 日期) 的多段录音自动归并为一次接诊。

---

## 七、已完成功能

- [x] 登录认证（单用户）
- [x] OSS bucket 扫描，新文件自动入库
- [x] 多文件批量上传，上传到 OSS + 自动入库
- [x] 文件名自动解析元数据，支持 `-` 和 `_` 两种分隔符
- [x] session 自动归并（同顾问+顾客+日期 → 一次接诊）
- [x] 自动 ASR 流水线（上传/扫描 → 后台 fun-asr，含重试+并发限制=2）
- [x] 自动 Claude 分析（所有录音 ASR done → 自动触发，多段 transcript 拼接）
- [x] 10 维度评分（进房前/房中/房后，每维度含评价+改进建议）
- [x] 评分卡 UI（综合分圆圈、阶段分、子维度条形图、点击展开定义/评价/改进）
- [x] 复盘报告 Markdown 渲染
- [x] 老板人工点评（错的点/改进建议/正确做法）
- [x] 列表页自动轮询（10s 刷新）
- [x] 补传录音（session 详情页追加录音，自动重跑分析）
- [x] IP + 路径前缀 /gp/ 访问（PrefixMiddleware + nginx）
- [x] Claude API 走代理（httpx http2=False）
- [x] 启动时自动补跑未完成的 ASR 和分析

---

## 八、刚在改的内容（当前状态）

### 问题
老板反馈报告里「总体评价」和评分卡「亮点/短板」内容重叠。

### 设计决策
两者内容**不同**，不该删：
- **亮点/短板**（评分卡）= 纯基于维度分数的一句话，如"情感连接8分：…"
- **总体评价**（报告）= 接诊情景叙述，如"本次是5年老客+手术情境，成交来自…"，不提具体维度分数

### 已改
1. `webapp.py` highlights/weaknesses 的 prompt description 改为"只说维度得分原因，不写情景叙述"
2. `webapp.py` report_markdown prompt 把总体评价节改回，加注"不要提具体维度分数"
3. 修了 webapp.py 语法错误（description 字符串内有中文引号导致 `"` 嵌套）

### 当前状态
代码改完，服务已重启（active）。触发了重新分析，分析任务正在后台运行（background task `bv0c6kl9i`）。

### 下一步需要验证
```bash
# 确认分析完成
sqlite3 /app/gp-system/recordings.db \
  "SELECT analysis_status, substr(analysis_result,1,200) FROM sessions;"

# 验证总体评价回来了、亮点/短板是纯分数描述
```

---

## 九、遗留问题 / 待做

| 优先级 | 问题 |
|---|---|
| 高 | 验证总体评价/亮点短板不再重叠（见上方） |
| 中 | 录音时间戳点击跳转：转录文本里的 `[Xs-Ys]` 点击后播放器跳到对应时间 |
| 中 | analysis_scores 列是本次手动 ALTER TABLE 加的；SCHEMA_SQL 已补上，重建 DB 没问题，但当前 DB 列顺序略乱 |
| 低 | HTTPS：.cn 域名备案后走 certbot 或上传证书到 nginx |
| 低 | 多用户/角色：现在只有一个 boss 账号 |
| 低 | 跨 session 统计：按顾问维度汇总所有接诊的平均分（dashboard） |

---

## 十、重要设计决策和注意事项

### 代理（必读）
```python
# Claude API 必须走代理，OSS/DashScope 直连
import httpx
from anthropic import Anthropic
client = Anthropic(
    api_key=...,
    http_client=httpx.Client(
        proxy="http://127.0.0.1:7890",
        http2=False,   # 关键！HTTP/2 over HTTP-CONNECT 代理会 SSL EOF
        timeout=httpx.Timeout(connect=15.0, read=300.0, write=60.0, pool=15.0),
    ),
)
```

### 前端子路径支持
- nginx 传 `X-Forwarded-Prefix: /gp`
- Flask `PrefixMiddleware` 读头设 `SCRIPT_NAME`
- `base.html` 注入 `window.URL_PREFIX = "{{ request.script_root }}"`
- `app.js` 所有路径用 `U(path)` 函数拼前缀，fetch 用 `api(path)` 函数

### Claude tool_use 强制结构化输出
```python
message = client.messages.create(
    model=CLAUDE_MODEL,
    tools=[SCORE_TOOL],
    tool_choice={"type": "any"},   # 强制调工具
    ...
)
tool_result = next(
    (blk.input for blk in message.content if blk.type == "tool_use"), None
)
```

### session 分析缓存机制
- `analysis_signature` = sha1(sorted recording_ids)
- 新录音加入 session → signature 变 → 旧分析标 pending → 自动重跑
- 手动触发分析：`POST /api/session/<id>/analyze`（清 signature 强制重跑）

### 知识库
- `/app/gp-system/output/logic_library.json`，341 条，只读
- 每次分析完整发给 Claude（约 150KB），不做向量检索（token 成本可接受）
