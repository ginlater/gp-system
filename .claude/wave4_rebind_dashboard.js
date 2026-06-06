export const meta = {
  name: 'wave4-rebind-dashboard',
  description: 'Wave4: 顾问端换绑增强(P1.1)+运营看板改按客人&章节展开去重(P1.3/P0.5)，含强制验证(并复检Wave3安全修复)',
  phases: [
    { title: 'Backend', detail: 'webapp.py 串行: 顾问换绑增强 → 看板按客人统计' },
    { title: 'Frontend', detail: '并行: consultant.html换绑弹窗 / admin.html运营看板表' },
    { title: 'Verify', detail: '对抗式: 换绑越权/会员号 + 看板口径 + 安全复检(录音越权) + 真机' },
    { title: 'Report', detail: '汇总' },
  ],
}

const CTX = `
【项目】Flask 单体 /opt/gp-system/webapp.py(约11400行,Wave1/2/3已改+我刚补了安全修复,工作区未提交) + web_v2/templates/*.html。
**改前必须 grep/Read 按函数名定位,行号已多次偏移。改完 python3 -c "import ast;ast.parse(open('/opt/gp-system/webapp.py').read())" 自检。**
【老板已拍板(Wave4)】
- 顾问换绑选"已有客人" = 只能从【本人接待过的客人(当日 daily_reception + 历史 sessions.advisor=本人)】里选,选不到就【新增客人(自动生成会员号)】。不放开搜全公司。
- 换绑/补登时把客人自动补登进当日接诊单 = 【算一次接诊业绩】(计入接诊量)。
【相关现状(grep定位,行号近似)】
- 顾问换绑(直接,无审批) direct_rebind: POST /api/consultant/recordings/<rid>/direct_rebind。现强约束:目标客人必须已在该录音当天 daily_reception,否则报"请先补登"。这就是要放宽的点。
- 顾问换绑弹窗 consultant.html(约343-360 弹窗 + openRebind/submitRebind 约1458-1511):现只把"当天 daily_reception 客人"渲染成 radio 单选,无搜索已有/无新增入口。
- 顾问"今日接诊"补登 today_reception/add: 支持搜已有 + 新增,但新增客人 member_card 不自动生成(取用户输入或NULL)。_generate_member_card 已存在(格式 M+YYMMDD+4随机,公司内唯一)。
- 管理端成熟版换绑(参考实现): customer_options / add_day_customer / remove_day_customer / recording_rebind —— 已支持搜已有(LIKE)+新增(自动会员号)+删新增(安全闸:名下有录音则拒删)。顾问端要"下放"这套能力但**收窄候选范围到本人接待过的**。
- 运营看板报告查看统计 report_view_stats: GET /api/admin/report_view_stats。现 GROUP BY user_id,username,role(按"用户"统计),输出 sessions_viewed/enter_count/part_expand_count/total_minutes/favorite_part。前端 admin.html loadViewStats 表头硬编码"用户|角色|看过报告数|进入次数|章节展开次数|总时长|最常看章节"。
- 埋点 report_view_events(user_id,username,role,company_id,session_id,source,part_key,event,duration_ms,created_at)。part_key 取值: overall、part1~part11、extra_老板点评。part1 默认展开(不算"展开")。可展开章节=part2~part11(10个)+extra_老板点评(1个)=11个。
- "章节展开次数"现算法 part_expand_count = SUM(CASE WHEN event='enter' AND part_key<>'overall' THEN 1)(按事件累加,错!且把 part1 也算进去)。
`

const RULES_DASH = `
【看板改按客人(P1.3)+章节展开去重(P0.5)——目标口径】
- 维度从"按用户"改为"按客人(顾问×客人×报告session)分行": JOIN sessions(e.session_id=s.id) GROUP BY user_id,session_id(每个查看者对每个报告一行;若一个报告被多人看则多行,但展示以顾问视角)。
- 列: 删掉"用户"列;显示 顾客姓名(s.customer)、顾问姓名(s.advisor)、服务日期(s.service_date,即录音日期)、最近浏览时间(MAX(e.created_at))、展开章节数(去重,见下,可显示 n/11)、总时长(分钟)。按最近浏览时间倒序。
- 展开章节数(P0.5核心): COUNT(DISTINCT part_key) 且 part_key 必须在白名单 EXPANDABLE_PARTS={part2,part3,...,part11, extra_老板点评}(共11,排除 overall 和 part1)。即"一共展开了几个不同章节",反复展开同一章只算1。把这11个章节集合写成常量,既做分子也做"n/11"分母,别再用 SUM(...<>overall...)。
- 口径(默认): 该顾问对该客人报告的"累计"——展开章节=该(顾问,session)所有查看里的 distinct 章节并集; 最近浏览=MAX; 总时长=该(顾问,session)累计。
- 近30天窗口保持(现有 where 时间过滤)。门店过滤口径沿用现有(按查看者门店),不在本波动它。
`

// ---------------- Phase 1: Backend (串行) ----------------
phase('Backend')
log('Wave4 后端: 顾问换绑增强 → 看板按客人')

const c1 = await agent(
  `${CTX}\n\n你负责【P1.1 顾问端换绑增强】,只改 /opt/gp-system/webapp.py。先 grep/Read direct_rebind、today_reception/add、_generate_member_card、管理端 customer_options/add_day_customer/remove_day_customer 确认现状。要做(严格按老板决策"仅本人接待过的客人 + 选不到就新增自动会员号 + 补登算接诊"):\n\n` +
  `1) 新增"顾问本人接待过的客人"搜索端点(供换绑弹窗选已有客人): GET /api/consultant/rebind_candidates?rid=<录音id>&q=<搜索词>。\n` +
  `   - 候选 = 该顾问【本人接待过的客人】: daily_reception(advisor_user_id=本人,任意日期) ∪ sessions(advisor=本人) 关联到的 company_customers,按 q 模糊(姓名/会员卡号/尾号) 去重返回。**不要返回全公司客人**(老板明确收窄)。\n` +
  `   - 标注每个候选是否已在该录音当天的 daily_reception(前端可提示)。鉴权 _consultant_required。\n\n` +
  `2) 顾问"新增客人"自动会员号 + 补登: 复用或新增端点让顾问在换绑场景新增客人——调用 _generate_member_card 自动发会员号(对齐管理端),写入 company_customers,并把该客人补登进【该录音当天】的 daily_reception(advisor_user_id=本人)。补登算接诊(daily_reception 本就是接诊量口径,正常 INSERT 即计入,不要额外排除)。可复用 today_reception/add 但**修正其新增分支:member_card 为空时自动 _generate_member_card**。新增的客人要可删(复用安全闸:名下有录音则拒删,参考 remove_day_customer/today_reception DELETE)。\n\n` +
  `3) 放宽 direct_rebind 约束: 目标客人若不在该录音当天 daily_reception,不要直接报错;改为"自动补登进当天 daily_reception 再换绑"(对齐管理端 recording_rebind 的自动补登),补登算接诊。**但必须校验目标客人属于"本人接待过的客人"集合或是本人刚新增的**(不能换绑到从没接待过的任意人,保留风控);若目标完全陌生则拒绝并提示。\n\n` +
  `4) 不改审批流(direct_rebind 仍直接执行无审批),不改管理端。保持换绑会作废新旧 session 分析、写审计的现有逻辑。\n\n` +
  `返回【契约摘要】: 新增/改动端点(方法+路径+参数+返回)、direct_rebind 放宽后的校验规则、新增客人自动会员号与补登逻辑、删除新增客人的安全闸。`,
  { label: 'rebind-enhance', phase: 'Backend' }
)

const c2 = await agent(
  `${CTX}\n${RULES_DASH}\n\n你负责【P1.3 看板改按客人 + P0.5 章节展开去重】,只改 /opt/gp-system/webapp.py。先 grep/Read report_view_stats 确认现状。注意 webapp.py 刚被上一个 agent 改过,重新 Read、唯一字符串 Edit。要做:\n` +
  `1) 把 report_view_stats(GET /api/admin/report_view_stats)改造/新增为"按客人"维度: JOIN sessions ON e.session_id=s.id, GROUP BY e.user_id,e.session_id。输出每行: customer(s.customer)、advisor(s.advisor)、service_date(s.service_date)、last_view(MAX(e.created_at))、expanded_chapters(去重展开章节数)、total_minutes、可附 viewer 顾问名。按 last_view 倒序。\n` +
  `2) 展开章节去重(P0.5): 定义常量 EXPANDABLE_PARTS = ['part2',...,'part11','extra_老板点评'](共11)。expanded_chapters = COUNT(DISTINCT part_key) WHERE event='enter' AND part_key IN EXPANDABLE_PARTS。返回里带上 total_expandable=11 供前端显示 n/11。绝不再用 SUM(...part_key<>'overall'...)(那把 part1 和重复展开都算进去了)。\n` +
  `3) 保留近30天时间窗口与现有门店过滤口径。若改动会影响其它调用方,保持向后兼容(可新增返回字段,旧字段保留或前端同步改)。\n` +
  `改完 ast.parse 自检。返回【契约摘要】: 端点返回结构(新字段)、EXPANDABLE_PARTS 常量、去重 SQL 写法。`,
  { label: 'dashboard-by-customer', phase: 'Backend' }
)

const backendContract = `== 换绑增强 ==\n${c1}\n\n== 看板按客人 ==\n${c2}`

// ---------------- Phase 2: Frontend (并行) ----------------
phase('Frontend')
log('并行改 consultant.html(换绑弹窗) / admin.html(运营看板表)')

const fe = await parallel([
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/consultant.html(P1.1 换绑弹窗增强)。后端契约:\n${backendContract}\n\n` +
    `现状: 换绑弹窗(约343-360 + openRebind/submitRebind 约1458-1511)只把当天 daily_reception 客人渲染成 radio,无搜索/新增,当天没候选就把人踢出去。要做(按老板决策):\n` +
    `1) 弹窗里加"搜索本人接待过的客人"(调新端点 GET /api/consultant/rebind_candidates?rid=&q=),列出候选可选中。范围仅本人接待过的(后端已收窄)。\n` +
    `2) 加"新增当日客人"入口: 输入姓名(+可选尾号),提交后端自动生成会员号并补登当天接诊,新增项可选中;新增项旁给"删除"(写错了能撤,调后端删除端点,名下有录音则后端拒绝、前端提示)。\n` +
    `3) 当天没有候选时不再把人踢出,直接让其搜索历史客人或新增。\n` +
    `4) submitRebind 仍调 direct_rebind(直接换绑无审批);提示文案明确"仅移动这一段录音,本客人其余录音不受影响,新旧报告将重新生成"(消除"换整个客人"错觉)。\n` +
    `注意 Wave1/3 已改过 consultant.html(提醒/升级、客户档案入口),重新 Read、唯一字符串、别冲突。保持现有风格(双皮肤)。Jinja venv 自检。返回改动摘要。`,
    { label: 'fe-consultant', phase: 'Frontend' }
  ),
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/admin.html(P1.3/P0.5 运营看板报告查看表改按客人)。后端契约:\n${backendContract}\n\n` +
    `现状: loadViewStats 表头硬编码"用户|角色|看过报告数|进入次数|章节展开次数|总时长|最常看章节",按用户一行。要做:\n` +
    `1) 改表头与渲染为按客人一行: 顾客姓名|顾问姓名|服务日期|最近浏览时间|展开章节(显示 n/11)|总时长(分钟)。删掉"用户"列(只显示顾问姓名)。按最近浏览时间倒序。\n` +
    `2) 展开章节列用后端去重值 expanded_chapters + total_expandable 显示"n/11"。\n` +
    `3) 字段名严格对齐后端契约返回(customer/advisor/service_date/last_view/expanded_chapters/total_expandable/total_minutes)。\n` +
    `注意 Wave1/3 改过 admin.html(升级看板/待分析徽章/顾客档案Tab/重复分页),重新 Read、唯一字符串、别冲突。保持现有 class/loadXXX 模式。Jinja venv 自检。返回改动摘要。`,
    { label: 'fe-admin', phase: 'Frontend' }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 3: Verify (含安全复检) ----------------
phase('Verify')
log('对抗式验证 + Wave3安全修复复检')

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
    `对抗式【证伪】Wave4 换绑增强 + 看板。契约:\n${backendContract}\n\n` +
    `Read webapp.py 相关端点 + git diff,逐条核:\n` +
    `换绑(P1.1):\n- rebind_candidates 是否真的只返回"本人接待过的客人"(daily_reception 本人 ∪ sessions advisor=本人),不会泄露全公司任意客人? 构造: 一个顾问搜一个他没接待过的客人,应搜不到。\n- 新增客人是否自动 _generate_member_card? 是否补登当天 daily_reception(算接诊)? 新增项删除是否有"名下有录音则拒删"安全闸?\n- direct_rebind 放宽后,是否仍校验目标属于"本人接待过/本人新增"集合、拒绝完全陌生客人? 自动补登是否对齐?\n看板(P1.3/P0.5):\n- 是否 GROUP BY user_id,session_id 按客人分行? 输出 顾客/顾问/服务日期/最近浏览/展开章节/总时长?\n- 展开章节是否 COUNT(DISTINCT part_key) 且白名单 EXPANDABLE_PARTS(11个,排除 overall+part1)? 反复展开同章只算1? part1 不计入? 用合成 report_view_events 实测一个 case。\n- ast.parse 过? 按 schema 输出,problems 带 file:line。`,
    { label: 'verify-logic', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `【安全复检】上一波(Wave3)发现并已由监督者修复的"录音越权"漏洞,本波必须确认仍然封死(我刚在 webapp.py 加了统一闸 _can_listen_recording 并改了 /api/session/<sid>、/session/<sid>)。Read webapp.py 核:\n` +
    `1) grep 全部 oss_signed_url(...) 调用点(约10处),逐一确认: 每个会把 audio_url/签名URL 返回给前端的端点,要么 @admin_required/函数内 admin 校验、要么 advisor/uploader=本人 自限定、要么经 _can_listen_recording 判定。列出每处端点+鉴权结论。\n` +
    `2) 重点 /api/session/<int:sid>(api_session_get): 是否加了 company 作用域 403? 是否对每条录音用 _can_listen_recording 决定 audio_url(无权则 None)? 构造逻辑反例: consultant 取一个非本人 advisor 的 session,audio_url 应为 None(转写可见、音频不可听); 跨公司应 403。\n` +
    `3) /session/<int:sid> HTML 页: 是否加 company 403?\n` +
    `4) _can_listen_recording 逻辑是否正确(admin/super放行、store_manager本店、consultant本人advisor/uploader)? 有无新端点(Wave4换绑)又引入了未设防的签名URL?\n` +
    `按 schema 输出,problems 带 file:line。这是安全门槛,从严。`,
    { label: 'verify-security', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `Wave4 真机冒烟,不破坏线上库。1) cp /opt/gp-system/recordings.db /tmp/wave4_smoke.db(含 -wal/-shm,checkpoint 使自包含); 2) 用 Flask test_client(零端口,import 前 monkeypatch threading.Thread.start=no-op 防烧钱,先 export DB_PATH=副本 再 import 并 assert webapp.DB_PATH==副本); 3) /healthz 200 无崩溃(确认换绑/看板改动+我刚的安全修复都不让启动崩); 4) 造 consultant 登录: GET /api/consultant/rebind_candidates?rid=&q= 看返回结构、确认不含其没接待过的客人; GET 一个非本人 session 的 /api/session/<sid> 确认 recordings[].audio_url 为 None、本人 session 的为非None; 5) admin 登录 GET /api/admin/report_view_stats 看是否含 customer/advisor/service_date/last_view/expanded_chapters/total_expandable 字段; 6) 清理副本+复核线上库未动。按 schema 输出,evidence 写清命令与HTTP码/字段。`,
    { label: 'verify-runtime', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `审查 Wave4 前端两模板(consultant.html 换绑弹窗 / admin.html 运营看板表)。前端自述:\n${JSON.stringify(fe)}\n后端契约:\n${backendContract}\n\n` +
    `Read git diff 核: consultant 换绑弹窗是否有 搜本人接待过客人+新增(自动会员号提示)+删除新增+不再踢人+"仅移动这一段录音"文案? admin 看板表是否改成 顾客/顾问/服务日期/最近浏览/展开章节(n/11)/总时长 且删了"用户"列、字段对齐契约? 与 Wave1/3 改动有无冲突? Jinja venv parse + JS 括号/函数完整? 按 schema 输出,problems 带 file:line。`,
    { label: 'verify-frontend', phase: 'Verify', schema: VSCHEMA }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 4: Report ----------------
phase('Report')
const summary = await agent(
  `汇总 Wave4 验证。结果:\n${JSON.stringify(verds, null, 2)}\n后端契约:\n${backendContract}\n前端:\n${JSON.stringify(fe)}\n\n` +
  `输出简体中文交付报告: 1)Wave4做了什么(换绑增强/看板按客人/章节去重); 2)换绑是否严格"仅本人接待过的客人+新增自动会员号+补登算接诊"; 3)P0.5章节展开去重是否对; 4)**Wave3安全修复复检结论(录音越权是否彻底封死,含其它签名URL端点审计)**; 5)各验证维度结论; 6)遗留问题(带file:line); 7)给监督者明确结论:可交付/需返工(列返工项)。实话实说。`,
  { label: 'wave4-report', phase: 'Report' }
)

return { backendContract, frontend: fe, verifications: verds, summary }
