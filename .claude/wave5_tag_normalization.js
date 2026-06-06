export const meta = {
  name: 'wave5-tag-normalization',
  description: 'Wave5 标签归一化命门: Call1受控词表 + AI建议归并(审核后应用) + 存量碎片词聚类 + tag_stats双口径(接诊次数+客人数)，含强制验证',
  phases: [
    { title: 'Backend', detail: 'webapp.py: 受控词表+建议归并/审核应用端点 → tag_stats双口径+展示按customer_id' },
    { title: 'Frontend', detail: 'admin.html: AI建议归并审核UI + 标签统计加客人数列' },
    { title: 'Verify', detail: '对抗式: 聚类质量(怕痛类归一) + 审核应用回填 + 双口径 + 真机' },
    { title: 'Report', detail: '汇总' },
  ],
}

const CTX = `
【项目】Flask 单体 /opt/gp-system/webapp.py(约11500行,W1-4已上线提交,工作区干净) + web_v2/templates/*.html。DB=/opt/gp-system/recordings.db(已上线,含 customer_tags.customer_id 等新结构)。
**改前 grep/Read 按函数名定位。改完 python3 -c "import ast;ast.parse(open('/opt/gp-system/webapp.py').read())" 自检。**
【已决策(Wave5)】
- 存量碎片标签归并 = 【AI出建议、管理员审核后应用】(不自动应用)。
- 流行度口径 = 【接诊次数 + 客人数 两列都显示】。
- 标签分类 = 沿用现有7类(客人新老/顾客类型/顾客画像/顾客痛点/顾客状态/需求类型/竞品)。
【相关现状(grep定位)】
- 标签词典 tag_dictionary(company_id,category,canonical_tag,synonyms(JSON数组),status=active|blacklist,UNIQUE(company_id,canonical_tag))。实测61项,synonyms 几乎全空。
- 标签落库 save_customer_tags(约webapp.py:3500+): 从分析结果抽标签写 customer_tags,写前调 normalize_tag(只做 strip().lower() 精确匹配标准词/同义词,命中黑名单跳过)。现在已写 customer_id(Wave2)。
- normalize_tag / get_tag_dict_map / _norm_key(约3390-3436): 精确匹配,无模糊/无AI。
- renormalize_company_tags(约7191-7217): 词典变更后重算 customer_tags 的 canonical_tag(命中标准词→写canonical,黑名单→删行,未命中→NULL)。
- 待归类池 tag_unclassified(约7348) + assign(约7369,支持 merge/new/blacklist)。
- 标签统计 tag_stats(约7425): scope=customer/competitor; EFF=COALESCE(NULLIF(canonical_tag,''),tag); 已算 service_count=COUNT(DISTINCT source_session_id) 和 customer_count=COUNT(DISTINCT customer_name); 支持时间区间 ?from/?to; 门店过滤。
- Call1 分析 prompt(约2553-2585): 要求输出 customer_tags 数组(≥5个 tag+count),但【没有注入公司现有标准词清单】→ AI 每次造新词,把归一压力全转给事后。
- 实测: customer_tags 2299行,canonical 非空仅~52行(97.7% NULL); '怕痛'语义裂成 怕痛体质/怕疼耐受力弱/忍痛配合型/拒绝痛感 等6+种独立行。
- 标签统计前端 admin.html(约loadTagStats / 标签统计 tab): 现只展示一个口径列。
`

// ---------------- Phase 1: Backend (串行) ----------------
phase('Backend')
log('Wave5 后端: 受控词表 + AI建议归并/审核应用 → tag_stats 双口径')

const c1 = await agent(
  `${CTX}\n\n你负责【标签归一化核心: 受控词表 + AI建议归并(审核后应用)】,只改 /opt/gp-system/webapp.py。先 Read save_customer_tags / normalize_tag / renormalize_company_tags / tag_dictionary 相关 + Call1 prompt 确认现状。要做:\n\n` +
  `1) 源头约束(受控词表): 在 Call1 分析 prompt(约2553-2585)注入【该公司现有标准词清单】(从 tag_dictionary 取 active 的 canonical_tag,按7类分组),要求 AI【优先复用清单里的标准词】,实在没有才造新词。从源头压住新词面爆炸。注意 prompt 拼接处可能在生成请求时动态读词典——找到 Call1 调用点,把词表作为变量注入。控制清单长度(太长截断/按类取高频)。\n\n` +
  `2) AI建议归并机制(审核后应用,不自动改库):\n` +
  `   - 新建表 tag_merge_suggestions(id, company_id, category, canonical TEXT, members_json TEXT(JSON数组,被归并的碎片词), sample_count INTEGER, status TEXT default 'pending'(pending|applied|rejected), created_at, applied_at)。幂等迁移(CREATE TABLE IF NOT EXISTS)。\n` +
  `   - POST /api/admin/tags/suggest_merge: 取该公司 customer_tags 里 canonical_tag IS NULL 的【distinct 原始 tag + 出现次数】,分批(每批不超过~120个词,控制单次LLM体积)调 _call_llm 让模型把【同义/近义碎片词聚成组】,每组产出 {canonical(选最规范的标准词), members[](该组所有碎片词), category(归到7类之一)}。把结果写入 tag_merge_suggestions(status='pending')。**不直接改 tag_dictionary/customer_tags**。返回本次生成的建议组数。可带 ?category= 限定批次。用 tool/function-calling 拿结构化输出。鉴权 manager_required。\n` +
  `   - GET /api/admin/tags/suggestions?status=pending: 列出待审核建议组(canonical/members/sample_count/category)。\n` +
  `   - POST /api/admin/tags/suggestions/<id>/apply: 管理员审核通过(可传入编辑后的 canonical/members/category 覆盖)。应用时: upsert 进 tag_dictionary(canonical_tag=canonical, category, synonyms=members 合并进去, status='active'),然后调 renormalize_company_tags 回填 customer_tags.canonical_tag。标记该 suggestion status='applied',applied_at=now。\n` +
  `   - POST /api/admin/tags/suggestions/<id>/reject: 标记 rejected。\n` +
  `3) 不破坏现有 normalize_tag 精确匹配/黑名单逻辑;归一仍以 tag_dictionary 为准,本波只是"把词典喂饱"。\n\n` +
  `返回【契约摘要】: Call1 受控词表注入点、tag_merge_suggestions 表结构、4个端点(方法/路径/参数/返回)、apply 的 upsert+renormalize 流程。`,
  { label: 'tag-normalize-core', phase: 'Backend' }
)

const c2 = await agent(
  `${CTX}\n\n你负责【标签统计双口径 + 展示侧按 customer_id】,只改 /opt/gp-system/webapp.py。注意上一个 agent 刚改过 webapp.py,重新 Read、唯一字符串 Edit。要做:\n` +
  `1) tag_stats(约7425): 确认/确保同时返回【接诊次数 service_count=COUNT(DISTINCT source_session_id)】和【客人数 customer_count】两个口径(老板要两列都看)。把 customer_count 从 COUNT(DISTINCT customer_name) 升级为【优先 COUNT(DISTINCT customer_id),customer_id 为空的行回退按 customer_name】,减少同名串扰(Wave2 已加 customer_id)。两个口径都返回,前端各一列。\n` +
  `2) 标签历史展示端点(grep 'WHERE customer_name=' 找,约webapp.py:4765 附近的 session customer_tags 展示): 若它按 customer_name 聚合导致同名客户标签混入,改为优先按 customer_id 聚合(有 customer_id 用 customer_id,无则回退姓名)。\n` +
  `3) 保持时间区间(?from/?to)、门店过滤、scope=customer/competitor 不变。竞品口径不在本波改。\n` +
  `改完 ast.parse 自检。返回【契约摘要】: tag_stats 返回字段(两个口径列名)、customer_count 新算法、展示端点改动。`,
  { label: 'tag-stats-dual', phase: 'Backend' }
)

const backendContract = `== 归一化核心 ==\n${c1}\n\n== 统计双口径 ==\n${c2}`

// ---------------- Phase 2: Frontend ----------------
phase('Frontend')
log('admin.html: AI建议归并审核UI + 标签统计双口径列')

const fe = await parallel([
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/admin.html(标签归一化审核UI + 统计双口径)。后端契约:\n${backendContract}\n\n` +
    `要做:\n1) 在标签词典/待归类相关 tab 加【AI建议归并】区: 一个"生成AI归并建议"按钮(调 POST /api/admin/tags/suggest_merge,可选分类),下面列出待审核建议组(GET suggestions?status=pending)——每组显示 标准词canonical / 被归并的碎片词members(可勾选删个别词) / 样本数 / 归类category(可改)。每组给【通过应用】(调 apply,把可能编辑过的 canonical/members/category 传回) 和【拒绝】(reject) 按钮。应用后该组消失、提示已回填。\n` +
    `2) 标签统计表(loadTagStats / 标签统计 tab)加列: 同时显示【接诊次数(service_count)】和【客人数(customer_count)】两列(老板要两个口径都看),字段对齐后端契约。\n` +
    `注意 Wave1/3 改过 admin.html(升级看板/顾客档案Tab/重复分页/看板按客人),重新 Read、唯一字符串、别冲突。保持现有 class/loadXXX 模式。Jinja venv 自检 parse。返回改动摘要。`,
    { label: 'fe-admin', phase: 'Frontend' }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 3: Verify ----------------
phase('Verify')
log('对抗式: 聚类质量 + 审核应用回填 + 双口径 + 真机')

const VSCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['dimension', 'pass', 'checks', 'problems', 'evidence'],
  properties: {
    dimension: { type: 'string' }, pass: { type: 'boolean' },
    checks: { type: 'array', items: { type: 'object', additionalProperties: false,
      required: ['item', 'result'], properties: {
        item: { type: 'string' }, result: { type: 'string', enum: ['通过','失败','存疑'] }, note: { type: 'string' } } } },
    problems: { type: 'array', items: { type: 'string' } }, evidence: { type: 'string' },
  },
}

const verds = await parallel([
  () => agent(
    `对抗式【证伪】Wave5 标签归一化后端。契约:\n${backendContract}\n\n` +
    `Read webapp.py 相关端点 + git diff,逐条核:\n` +
    `- 受控词表: Call1 prompt 是否真的注入了公司现有 active 标准词清单、要求AI优先复用? 清单长度是否有控制?\n` +
    `- 建议机制是否"审核后应用": suggest_merge 是否只写 tag_merge_suggestions(pending)、不直接改 tag_dictionary/customer_tags? apply 才 upsert词典+renormalize? reject 标记正确?\n` +
    `- apply 应用后是否调 renormalize_company_tags 回填 customer_tags.canonical_tag? 编辑后的 canonical/members 是否被采纳?\n` +
    `- tag_stats 是否同时返回 service_count(接诊次数) 和 customer_count(客人数)? customer_count 是否优先 customer_id、回退姓名?\n` +
    `- 展示端点是否改为优先 customer_id 聚合?\n` +
    `- 迁移(tag_merge_suggestions)幂等? ast.parse 过? 按 schema 输出,problems 带 file:line。`,
    { label: 'verify-logic', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `【聚类质量实测】这是命门,要真跑一次看AI归并质不质量。不破坏线上库: 1) cp recordings.db(+wal/shm,checkpoint)到 /tmp/wave5_q.db; 2) export DB_PATH=副本,Flask test_client(import前 monkeypatch threading.Thread.start=no-op),assert DB_PATH==副本; 3) admin 登录 POST /api/admin/tags/suggest_merge(可限一个分类或小批量,控制LLM成本); 4) GET /api/admin/tags/suggestions?status=pending 看生成的建议组——**重点核查: '怕痛/怕疼/对疼痛敏感/怕痛体质/忍痛配合型' 这类是否被聚到同一组(canonical 合理)? 有没有把不相干的词错误塞进一组(误合)?** 抽样5-8组人工判断合理性; 5) 挑一组 POST apply,验证 tag_dictionary 写入 synonyms + customer_tags.canonical_tag 被 renormalize 回填(该组碎片词的行 canonical 变成标准词); 6) 清理副本+复核线上库未动。按 schema 输出,evidence 给出实际生成的建议组样本。若 LLM 不可用(无key/超时)则改为静态审查聚类prompt+流程并标存疑。`,
    { label: 'verify-clustering', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `Wave5 真机冒烟+前端审查,不破坏线上库。后端契约:\n${backendContract}\n前端自述:\n${JSON.stringify(fe)}\n\n` +
    `1) cp recordings.db 副本,Flask test_client(防烧钱 monkeypatch),/healthz 200 无崩溃(确认 Wave5 改动+新表迁移不让启动崩); 2) PRAGMA 确认 tag_merge_suggestions 表已建; 3) GET /api/admin/tag_stats 看是否含 service_count 和 customer_count 两字段; 4) 前端: Read admin.html git diff,核 AI建议归并审核UI(生成/列建议/通过/拒绝)接对端点、标签统计加了客人数列、与Wave1/3/4改动无冲突、Jinja venv parse + JS 完整; 5) 清理+复核线上库未动。按 schema 输出,problems 带 file:line。`,
    { label: 'verify-runtime-fe', phase: 'Verify', schema: VSCHEMA }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 4: Report ----------------
phase('Report')
const summary = await agent(
  `汇总 Wave5 验证。结果:\n${JSON.stringify(verds, null, 2)}\n后端契约:\n${backendContract}\n前端:\n${JSON.stringify(fe)}\n\n` +
  `输出简体中文交付报告: 1)Wave5做了什么(受控词表/AI建议归并审核/双口径统计/展示按customer_id); 2)**AI聚类质量实测结论(怕痛类是否正确归一、有无误合)——这是命门重点**; 3)是否严格"审核后应用"(不自动改库); 4)各验证维度结论; 5)遗留问题(带file:line); 6)给监督者明确结论:可交付/需返工(列返工项)。实话实说。`,
  { label: 'wave5-report', phase: 'Report' }
)

return { backendContract, frontend: fe, verifications: verds, summary }
