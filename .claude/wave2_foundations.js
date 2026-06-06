export const meta = {
  name: 'wave2-foundations',
  description: 'Wave2地基: customer_tags加customer_id(修P0.7同名合并误伤)+sessions索引 + P0.1/P0.2手动分析文案，含强制验证',
  phases: [
    { title: 'Backend', detail: 'webapp.py: customer_tags customer_id 迁移/回填/合并改造 + sessions 索引' },
    { title: 'Frontend', detail: '并行改 index.html / admin.html 手动分析文案' },
    { title: 'Verify', detail: '对抗式验证: 同名合并不误伤 + 真机迁移 + 前端文案' },
    { title: 'Report', detail: '汇总验证结论' },
  ],
}

const CTX = `
【项目】Flask 单体，/opt/gp-system/webapp.py(约11400行,Wave1已改过,工作区未提交) + web_v2/templates/*.html。DB=/opt/gp-system/recordings.db(SQLite,DB_PATH可覆盖)。改前先 Read 确认当前内容(Wave1 已动过 webapp.py，行号会偏移，按函数名/grep 定位，别信旧行号)。
【已决策】AI分析=手动(省钱),不要加自动触发,只改文案+给醒目"待分析"入口。
【相关现状】
- customer_tags 表: 列 id,customer_name,advisor_name,tag,source_session_id,created_at,mention_count,canonical_tag,service_date —— 没有 customer_id。2299 行,97.7% canonical 为空。
- sessions 表: 有 customer_id 列(部分老数据为空)、service_date、company_id、advisor(文本)、customer(文本)。
- 写标签 save_customer_tags(webapp.py 约3439-3531) 落 customer_tags,但不写 customer_id。
- 合并 merge(约6532-6656): 关键bug在 "UPDATE customer_tags SET customer_name=into_name WHERE customer_name=from_name"(约6621) —— 按姓名整体改写,会误伤所有同名客户(库内'测试'4人/'李女士'/'张女士'等)。
- 撤销 unmerge(约6659-6743): 读 affected 快照还原。
- _customer_session_clause(约7547) 用 customer_id OR customer=name 兜底匹配。
- Wave1 已有的启动迁移区(grep 'app_migration_marker' 或 'ALTER TABLE' 找到)，新迁移仿照同风格、幂等、用 marker 防重复。
`

// ---------------- Phase 1: Backend (串行) ----------------
phase('Backend')
log('Wave2 后端: customer_tags customer_id 地基 + sessions 索引')

const dbContract = await agent(
  `${CTX}\n\n你负责【customer_tags 身份地基改造 + sessions 索引】，只改 /opt/gp-system/webapp.py。先 Read 相关函数(save_customer_tags / merge / unmerge / 启动迁移区 / 建表区)确认现状。要做:\n\n` +
  `1) 迁移(幂等,放启动迁移区,marker 守卫): \n` +
  `   - customer_tags ADD COLUMN customer_id INTEGER (PRAGMA table_info 判存在再 ALTER)。\n` +
  `   - 建索引 CREATE INDEX IF NOT EXISTS idx_ctags_customer ON customer_tags(customer_id)。\n` +
  `   - 给 sessions 建 CREATE INDEX IF NOT EXISTS idx_sessions_cust_date ON sessions(company_id, customer_id, service_date)。\n\n` +
  `2) 回填 customer_id(一次性,marker 守卫,只跑一次): \n` +
  `   - 主路径: UPDATE customer_tags SET customer_id=(SELECT s.customer_id FROM sessions s WHERE s.id=customer_tags.source_session_id) WHERE source_session_id IS NOT NULL AND customer_id IS NULL AND 该 session 的 customer_id 非空。\n` +
  `   - 不要用纯姓名回填(同名会串)。source_session_id 为空 或 对应 session.customer_id 为空的行，customer_id 留 NULL(它们本就无法精确归属)。\n` +
  `   - 记录回填了多少行、剩多少 NULL，日志打印。\n\n` +
  `3) save_customer_tags(约3439-3531) 改为写入 customer_id: 该函数有 source_session_id/session 上下文，落 customer_tags 时把对应 sessions.customer_id 一并写入 customer_id 列(取不到则 NULL)。保持其余逻辑(归一/黑名单跳过/mention_count)不变。\n\n` +
  `4) 修 P0.7 合并误伤(核心): 把 merge(约6621) 的 "UPDATE customer_tags SET customer_name=into_name WHERE customer_name=from_name" 改成按 customer_id 精确迁移:\n` +
  `   - UPDATE customer_tags SET customer_id=into_id, customer_name=into_name WHERE customer_id=from_id。\n` +
  `   - 对 customer_id 为 NULL 的历史标签: 仅当能通过 source_session_id 确认属于 from(该 session.customer_id=from_id, 注意 merge 内若先 repoint 了 sessions.customer_id 要理清顺序)才迁移; 否则留着不动。绝不再用 "WHERE customer_name=from_name" 这种按名整体改写。\n` +
  `   - 同步更新 merge 写入 affected 快照的部分(记录被迁移的 tag id 集合)，使 unmerge 能按 tag id 精确还原 customer_id/customer_name。\n\n` +
  `5) unmerge(约6659-6743) 对应改回: 按 affected 快照里的 tag id 把 customer_id 还原为 from_id、customer_name 还原 from_name。不要按姓名还原。\n\n` +
  `务必: 改完 python -c "import ast; ast.parse(open('/opt/gp-system/webapp.py').read())" 自检。\n` +
  `返回【契约摘要】: 列出新增列/索引、回填策略与实测回填行数(可在副本库试跑)、save_customer_tags 改动、merge/unmerge 新逻辑、affected 快照新结构。`,
  { label: 'db-foundation', phase: 'Backend' }
)

// ---------------- Phase 2: Frontend (并行,不同文件) ----------------
phase('Frontend')
log('改手动分析文案: index.html + admin.html')

const fe = await parallel([
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/index.html(P0.1/P0.2 手动分析文案)。已决策: AI分析是手动的,不自动触发。现状: "上传并分析"按钮(约index.html:264)上传后提示"已上传 N 个文件,正在自动 ASR + 分析…"(约925)——这是假承诺(后端只跑ASR不分析)。\n` +
    `请: 1) 把按钮文案从"上传并分析"改为"上传"(或"上传录音");\n` +
    `2) 上传成功提示改为真实表述,例如"已上传 N 个文件,正在转写文字;如需生成报告,请到列表勾选后点'分析'";\n` +
    `3) 若上传区附近有合适位置,加一句小字说明"分析需手动触发(避免误跑产生费用)"。\n` +
    `只动文案/提示,不动上传逻辑。先 Read 确认当前行号(Wave1未动过此文件,但仍以实际内容为准)。返回改了哪几行。`,
    { label: 'fe-index', phase: 'Frontend' }
  ),
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/admin.html(P0.1/P0.2 手动分析文案 + 待分析入口)。已决策: AI分析手动。现状bug: 管理员代绑成功提示"代绑成功,已触发分析"(约admin.html:1778)、未绑定列表提示"绑定后顾问端会自动看到并触发分析"(约500)——都是假承诺(后端 admin_bind 只跑ASR不分析)。\n` +
    `请: 1) 把这两处"已触发分析/自动触发分析"文案改成真实表述,如"代绑成功,已绑定,待分析(请到接诊列表点'开始分析')"、"绑定后顾问端可见,分析需手动开始";\n` +
    `2) 如果未绑定录音列表/接诊相关列表里有展示 analysis_status 的地方,给 pending/未分析 的项加一个醒目"待分析"标记(纯前端展示,数据若已有 analysis_status 字段则用,没有就只改文案不强加)。\n` +
    `注意 Wave1 刚改过 admin.html(加了升级看板),请重新 Read 当前内容、用唯一字符串定位,别和 Wave1 改动冲突。只动文案/标记。返回改了哪几处。`,
    { label: 'fe-admin', phase: 'Frontend' }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 3: Verify ----------------
phase('Verify')
log('对抗式验证: 同名合并不误伤 + 真机迁移 + 前端文案')

const VSCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['dimension', 'pass', 'checks', 'problems', 'evidence'],
  properties: {
    dimension: { type: 'string' },
    pass: { type: 'boolean' },
    checks: { type: 'array', items: { type: 'object', additionalProperties: false,
      required: ['item', 'result'], properties: {
        item: { type: 'string' }, result: { type: 'string', enum: ['通过','失败','存疑'] }, note: { type: 'string' } } } },
    problems: { type: 'array', items: { type: 'string' } },
    evidence: { type: 'string' },
  },
}

const verds = await parallel([
  () => agent(
    `对抗式【证伪】Wave2 customer_tags 改造,重点验证 P0.7 同名合并误伤是否真的修好。契约:\n${dbContract}\n\n` +
    `用 /opt/gp-system/recordings.db 的副本(cp 到 /tmp/wave2_verify.db,绝不动原库)做实测:\n` +
    `1) 造数据: 在副本里找/造两个同名不同 id 的客户(如两个'测试'),各自挂几条 customer_tags(用各自 source_session_id, 回填后 customer_id 不同)。\n` +
    `2) 合并其中一个到第三方客户,验证: 只有 from_id 的标签被迁走, 同名的另一个客户的标签 customer_id/customer_name 纹丝不动(这是修复核心)。\n` +
    `3) unmerge 后验证按 tag id 精确还原,没有把同名别人的标签带回来。\n` +
    `4) 回填: customer_id 回填是否只走 source_session_id→session.customer_id, 没有用姓名兜底; NULL 残留是否合理。\n` +
    `5) 迁移幂等(marker)、save_customer_tags 是否写 customer_id、sessions 索引是否建。ast.parse 是否过。\n` +
    `按 schema 输出,problems 带 file:line。给出你 SQL 实测的关键输出。`,
    { label: 'verify-merge', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `Wave2 真机冒烟,不破坏线上库。1) cp /opt/gp-system/recordings.db /tmp/wave2_smoke.db; 2) export DB_PATH=/tmp/wave2_smoke.db,用 venv python 在非5058端口起 webapp(参考: 默认端口硬编码在 app.run 处,换一个;.env 用 setdefault 所以必须先 export DB_PATH 再 import,并 assert webapp.DB_PATH==副本); 3) curl /healthz 确认无迁移/语法崩溃; 4) PRAGMA table_info(customer_tags) 确认 customer_id 列; PRAGMA index_list 确认 idx_ctags_customer / idx_sessions_cust_date; 5) 抽查 customer_tags 回填后 customer_id 非空比例; 6) 关服+删副本+清理。按 schema 输出,evidence 写清命令与输出。注意启动会触发 startup_kick(对副本库无害但会想跑分析,可忽略或确认不影响)。`,
    { label: 'verify-runtime', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `审查 Wave2 前端文案改动。前端 agent 自述:\n${JSON.stringify(fe)}\n\n` +
    `Read /opt/gp-system/web_v2/templates/{index.html,admin.html} 的 git diff 核对:\n` +
    `- index.html: "上传并分析"按钮是否改名? "正在自动分析"假承诺是否改成真实表述?\n` +
    `- admin.html: 代绑"已触发分析"(约1778)、列表"自动触发分析"(约500) 是否改成"待分析/手动开始"? 是否和 Wave1 升级看板改动冲突(应在不同位置)? 待分析标记是否合理?\n` +
    `- 有无破坏现有 JS(误删函数/标签不闭合)。Jinja 能否解析(用项目 venv 试 parse)。\n` +
    `按 schema 输出,problems 带 file:line。`,
    { label: 'verify-frontend', phase: 'Verify', schema: VSCHEMA }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 4: Report ----------------
phase('Report')
const summary = await agent(
  `汇总 Wave2 验证。验证结果:\n${JSON.stringify(verds, null, 2)}\n后端契约:\n${dbContract}\n前端:\n${JSON.stringify(fe)}\n\n` +
  `输出简体中文交付报告: 1)Wave2做了什么(customer_tags身份地基/修P0.7/sessions索引/手动分析文案); 2)P0.7同名合并误伤是否确证修好(这是重点); 3)验证各维度结论; 4)遗留问题(带file:line); 5)给监督者明确结论: 可交付/需返工(列返工项)。实话实说。`,
  { label: 'wave2-report', phase: 'Report' }
)

return { dbContract, frontend: fe, verifications: verds, summary }
