export const meta = {
  name: 'wave1-reminders-overhaul',
  description: 'Wave1：提醒系统大改(查看即递减+去重+店长/管理员升级链+已读已处理) + 分割文案 + api/me补store_id，含强制验证',
  phases: [
    { title: 'Backend', detail: 'webapp.py 串行改：提醒子系统 → api/me（避免单文件冲突）' },
    { title: 'Frontend', detail: '并行改三个模板(不同文件)：consultant/admin/report' },
    { title: 'Verify', detail: '多 agent 对抗式验证：静态审查+真机跑+DB校验+前端审查' },
    { title: 'Report', detail: '汇总验证结论' },
  ],
}

const CTX = `
【项目】Flask 单体，/opt/gp-system/webapp.py(11075行) + web_v2/templates/*.html。DB=/opt/gp-system/recordings.db(SQLite, DB_PATH 环境变量可覆盖)。
【角色】users.role: super|admin|consultant|store_manager(店长真实存在)。店长绑 users.store_id，规则=只看本店。库里现有店长1人(唐书娟 id=32071 store_id=1)。
【关键表 reminder_log】列: id,company_id,store_id,target_user_id,target_name,kind('unbound'|'unviewed'),level(1/2/3),channel('inapp'|'phone'|'sms'|'wecom'|'board'),ref_type('recording'|'session'),ref_id,message,result,processed(默认0),processed_at,created_at。
【现状关键代码】
- 查看埋点 api_report_view_enter(webapp.py:5953-5962) 只 _log_view_event(~5932) 写 report_view_events，完全不碰 reminder_log。
- 提醒生成 run_reminder_scan(webapp.py:10636+) 后台每600秒一轮；unviewed 分级在 10755-10784：L1>H小时 inapp给顾问；L2 hrs>=24 给顾问inapp + 写一条 channel='board' "请店长跟进"(10770-10773)；L3 hrs>=48 board给管理员(10777-10780)。
- _emit_reminder(10610) 写一行; _already_reminded(10590) 判重含 date(created_at)=? (按天判重→刷屏根因); _reminders_today_count(10600); _close_resolved_reminders(10789) 仅 scan 内调用。
- 顾问/店长收件箱 api_consultant_reminders(11023-11038): WHERE target_user_id=? AND processed=0 AND channel IN('inapp','phone') —— board被排除、target是顾问，所以店长收不到升级。
- 管理员提醒看板 /api/admin/reminder_log(10997)。
- api_me(5775) 返回 id/username/role/company_id/advisor_name/employee_id/phone，缺 store_id(虽然 login 时 session 已存)。
【实测痛点】board 升级行 target_user_id 都是顾问(王凤/何智莉…)，不是店长；店长唐书娟收件箱只能看到关于她自己的72条，看不到任何"请你跟进别人"的升级。单顾问180条inapp只对应15个distinct ref_id(按天判重12倍冗余)。
`

const RULES = `
【老板已拍板的升级模型——必须严格实现】
A. 顾问的报告(unviewed):
   - 当天: 提醒顾问本人(L1, 已有, inapp)。
   - 第二天(自然日 D+1, 不是满24h)顾问仍未查看 → 同时升级给「本店店长」+「管理员」。
   - 顾问任何时候点了"查看报告" → 顾问本人的该提醒立刻 processed=1(badge即时-1)，给店长/管理员的升级项也一并关闭。
B. 店长自己的报告(unviewed): 第二天店长仍未看自己的报告 → 只升级给「管理员」(不再通知店长本人，因为他就是店长)。
C. 可见范围: 店长看本店所有人; 管理员看所有门店所有人。
D. 管理员必须能看到每条升级项的「店长已读」「店长已处理」状态。
【"第二天"判定】用自然日: 报告 analysis_finished_at 的日期 < 今天日期(date)，且至今未被查看 → 触发升级。不要再用 hrs>=24。
【"已读"vs"已处理"】两个独立状态: 店长打开升级项=已读(read_at); 店长在看板点「已跟进」按钮=已处理(handled_at + handled_by)。
【去重】顾问个人 inapp 提醒改为"一对象一行": 同一(kind,ref_type,ref_id,target_user_id,level)未处理的 inapp 只保留1行(判重去掉 date 约束); phone 渠道保留按天(用于重拨)。badge=对象数。
`

// ---------------- Phase 1: Backend (串行，单文件防冲突) ----------------
phase('Backend')
log('Wave1 启动：后端 webapp.py 串行改动（提醒子系统 → api/me）')

const reminderContract = await agent(
  `${CTX}\n${RULES}\n\n` +
  `你负责【提醒子系统后端大改】，只改 /opt/gp-system/webapp.py（不要碰任何 .html，模板由后续 agent 改）。请先 Read 相关函数确认现状再改。要实现：\n\n` +
  `1) Schema 迁移(幂等): 给 reminder_log 增加列 read_at TEXT, handled_at TEXT, handled_by INTEGER。仿照 webapp.py 现有启动迁移风格(grep 'ALTER TABLE' 找到启动迁移区，用 try/except 或 PRAGMA table_info 判断列是否存在再 ALTER)，确保重复启动不报错。\n\n` +
  `2) 升级项的数据模型: 用一种与个人提醒可区分的方式表示"升级项"。建议: 升级项写 reminder_log，channel='escalation'，level=2，ref_type='session'，ref_id=报告 session id，store_id=该报告所属门店，target_user_id 保留为"报告所属顾问"(标识是谁的报告)，message 含顾问名。一个 session 在升级阶段只保留1行 escalation(判重: kind='unviewed',ref_type='session',ref_id,channel='escalation' 不带日期)。\n\n` +
  `3) run_reminder_scan 的 unviewed 分级(webapp.py:10755-10784) 改为按老板模型:\n` +
  `   - 把"第二天判定"从 hrs>=24 改成 自然日: date(analysis_finished_at) < date(now) 且该 session 至今无 report_view_events 的 enter 记录(未被查看)。\n` +
  `   - 顾问报告满足"第二天未看": 生成1条 channel='escalation' 升级项(store_id=报告门店)。这条同时被本店店长收件箱和管理员看板读取，不需要给每个店长/管理员各写一行。\n` +
  `   - 若该报告所属用户本身是 store_manager(查 users.role): 升级项标记为仅管理员可见(可用 level=3 或一个 result 标记区分'manager_own'),店长收件箱不显示自己的;管理员看板显示。\n` +
  `   - 个人 inapp 提醒(给顾问本人的 L1)保留,但改判重为"一对象一行"(见去重规则)。\n` +
  `4) 去重重构: 修改 _already_reminded(10590) 或调用处,使 channel='inapp' 与 channel='escalation' 的判重不含 date(create一次后不再重复); channel='phone' 保留按天。并写一段一次性迁移/清理: 把现存"同一对象多行未处理 inapp"折叠为最新1行(其余 processed=2 或删除)，避免历史刷屏继续显示。把清理放进启动迁移区,只跑一次(可用一个 marker)。\n\n` +
  `5) 查看即递减: 在 api_report_view_enter(5953) 成功记录后,同步执行 UPDATE reminder_log SET processed=1, processed_at=datetime('now','localtime') WHERE ref_type='session' AND ref_id=<sid> AND processed=0 AND channel IN('inapp','escalation')。这样顾问查看→个人提醒和升级项都即时关闭。\n\n` +
  `6) 店长收件箱: 修改 api_consultant_reminders(11023) 或新增逻辑——当前用户是 store_manager 时,返回 = (target_user_id=自己 的个人 inapp/phone) ∪ (channel='escalation' AND store_id=该店长的 store_id AND processed=0)。普通顾问维持原样(只看自己的 inapp/phone)。升级项条目带上"谁的报告/顾问名/session id/是否已读"。当店长拉取到某条 escalation 时,如果该条 read_at 为空则置 read_at=now(表示已读)。\n\n` +
  `7) 新增店长「已跟进」接口: POST /api/manager/reminder/<rid>/handle ,鉴权 manager_required(或等价),校验该 escalation 的 store_id 属于当前店长本店,置 handled_at=now, handled_by=当前用户id。返回 ok。\n\n` +
  `8) 管理员提醒看板接口: 扩展 /api/admin/reminder_log(10997) 或新增 /api/admin/escalations ,返回所有 channel='escalation' 的升级项(super看全部,admin看本公司),每条附带: 顾问名/门店/session/报告生成时间/store已读(read_at)/store已处理(handled_at,handled_by 的姓名)/是否已被查看关闭(processed)。支持一个 processed=0 的"待跟进"过滤。\n\n` +
  `务必: 改完用 python -c "import ast; ast.parse(open('/opt/gp-system/webapp.py').read())" 自检语法。\n` +
  `最后用一段文字返回【接口契约摘要】给前端 agent 用: 列出你新增/改动的所有端点(方法+路径+请求体+返回字段)、reminder_log 新列、店长收件箱返回结构、管理员升级看板返回结构、"已跟进"接口。这段摘要是你的返回值。`,
  { label: 'reminder-backend', phase: 'Backend' }
)

const apiMeFix = await agent(
  `只改 /opt/gp-system/webapp.py。两处小修(P0.8):\n` +
  `1) api_me(webapp.py:5775 附近,返回 dict 在 ~5781-5785) 增加返回 store_id 字段(从 current_user() 或 session 取,与登录时存的一致)。\n` +
  `2) users 表的 role 列注释(schema 里写 '-- super | admin | consultant')已过时,如果代码里有该注释字符串,补上 store_manager(仅注释,改不改不影响运行,能改则改)。\n` +
  `注意: 此时另一个 agent 刚改过 webapp.py,请重新 Read 确认当前内容再用唯一字符串 Edit,避免冲突。改完 ast.parse 自检语法。返回一句话说明改了哪几行。`,
  { label: 'api-me-fix', phase: 'Backend' }
)

// ---------------- Phase 2: Frontend (并行，不同文件) ----------------
phase('Frontend')
log('后端完成，并行改三个模板')

const fe = await parallel([
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/consultant.html。后端契约如下(必须按它对接):\n\n${reminderContract}\n\n` +
    `要做:\n1) 顾问端提醒 badge(现 consultant.html:1554-1586 拉 /api/consultant/reminders)保持,但确保查看报告返回后刷新能体现"即时-1"(后端已在 view 时 processed=1,前端只要按现有 ~120s 刷新或在查看返回后主动重拉一次即可;若能在查看报告动作后主动调用刷新更好)。\n` +
    `2) 当登录用户是店长(store_manager)时,提醒面板要能显示"本店待跟进升级项"列表(后端 /api/consultant/reminders 对店长已返回 escalation 条目): 每条显示 顾问名/客户报告/生成时间,并提供一个「已跟进」按钮 → 调后端"已跟进"接口(见契约)。点开/展示即视为已读(后端在拉取时置 read_at)。\n` +
    `普通顾问看不到 escalation,维持原样。判断角色可用 /api/me 返回的 role。\n` +
    `保持页面现有风格/类名。改完说明改了哪些函数/行。`,
    { label: 'fe-consultant', phase: 'Frontend' }
  ),
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/admin.html。后端契约:\n\n${reminderContract}\n\n` +
    `要做: 管理员提醒看板(现 admin.html:~458-490 提醒日志)增加"升级待跟进"视图,展示后端升级看板返回的每条升级项,列含: 顾问/门店/报告生成时间/「店长已读」(read_at 有则显示时间,无则"未读")/「店长已处理」(handled_at + 处理人姓名,无则"未处理")/是否已被顾问查看关闭。提供"只看待跟进(processed=0)"过滤。保持现有风格/类名/loadXXX 模式。改完说明改了哪些函数/行。`,
    { label: 'fe-admin', phase: 'Frontend' }
  ),
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/report.html(P0.6 文案修正)。问题: 分割按钮 report.html:106 的 title 写"...各自重新转写后可分别换绑",但确认框 report.html:1583 写"转录会按切点自动切开(不重新转写)",两处矛盾。实际后端默认不重跑ASR(仅原录音未转写完才重跑)。请把按钮 title 改为与真实行为一致的措辞,例如"默认按已有转录切开;原录音未转写完才会重新转写,切后可分别换绑"。只动文案,不动逻辑。返回改了哪几行。`,
    { label: 'fe-report', phase: 'Frontend' }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 3: Verify (对抗式，多 agent) ----------------
phase('Verify')
log('强制验证：静态审查 + 真机跑 + DB 校验 + 前端审查')

const VSCHEMA = {
  type: 'object', additionalProperties: false,
  required: ['dimension', 'pass', 'checks', 'problems', 'evidence'],
  properties: {
    dimension: { type: 'string' },
    pass: { type: 'boolean', description: '该维度是否整体通过' },
    checks: { type: 'array', items: { type: 'object', additionalProperties: false,
      required: ['item', 'result'], properties: {
        item: { type: 'string' }, result: { type: 'string', enum: ['通过','失败','存疑'] }, note: { type: 'string' } } } },
    problems: { type: 'array', items: { type: 'string' }, description: '发现的问题(含 file:line)' },
    evidence: { type: 'string', description: '怎么验证的、看到了什么' },
  },
}

const verds = await parallel([
  () => agent(
    `你是对抗式代码审查员,任务是【证伪】Wave1 提醒后端改动是否真的实现了老板模型,不要轻信。\n后端契约:\n${reminderContract}\n\n老板模型:\n${RULES}\n\n` +
    `逐条核对 /opt/gp-system/webapp.py(Read 相关函数 + git diff): \n` +
    `- 查看即递减: api_report_view_enter 是否真的 UPDATE reminder_log processed=1(含 escalation+inapp, ref_id=sid)? 会不会误关别的 session?\n` +
    `- 去重: inapp/escalation 判重是否去掉了 date? phone 是否保留按天? 历史折叠迁移是否只跑一次且幂等?\n` +
    `- 第二天判定: 是否改成自然日 date()< 比较 而非 hrs>=24? "未查看"判定是否正确(无 enter 事件)?\n` +
    `- 升级触达: escalation 行 store_id 是否=报告门店? 店长收件箱查询是否 = 个人 ∪ (escalation AND store_id=本店)? 店长自己的报告是否只升管理员、不进自己收件箱?\n` +
    `- 已读/已处理: read_at 在店长拉取时置? 「已跟进」接口是否校验本店归属并写 handled_at/handled_by?\n` +
    `- 管理员看板是否能拿到 店长已读/已处理 状态?\n` +
    `- Schema 迁移是否幂等? ast.parse 是否通过?\n` +
    `按 schema 输出,problems 必须带 file:line。`,
    { label: 'verify-backend-logic', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `你做【真机冒烟测试】,不要破坏线上库。步骤:\n` +
    `1) cp /opt/gp-system/recordings.db /tmp/wave1_test.db (用副本,绝不动原库)。\n` +
    `2) 用 DB_PATH=/tmp/wave1_test.db 在一个不冲突端口(如 PORT 环境或代码默认端口换一个,grep app.run 看怎么起;用 run_in_background 起 flask)启动 /opt/gp-system/webapp.py。等待几秒,curl /healthz 确认起来了、没有因语法/迁移错误崩。\n` +
    `3) 验证 reminder_log 三个新列已迁移: sqlite3 /tmp/wave1_test.db "PRAGMA table_info(reminder_log)" 应含 read_at/handled_at/handled_by。\n` +
    `4) 直接在副本库造数据或挑一条现有 unviewed 提醒,调 /api/report_view/enter (需登录态——可看 login 逻辑或直接 SQL 验证回写函数; 若登录复杂,改为静态确认 SQL 回写语句正确 + 用 sqlite3 手动模拟 UPDATE 看 processed 变化)。\n` +
    `5) 关停后台 server,删除 /tmp/wave1_test.db。\n` +
    `报告: server 是否正常启动、迁移是否生效、有无 500/异常堆栈。按 schema 输出,evidence 写清命令与输出。`,
    { label: 'verify-runtime', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `审查前端三模板改动是否正确对接后端契约。\n契约:\n${reminderContract}\n前端各 agent 自述:\n${JSON.stringify(fe)}\n\n` +
    `Read /opt/gp-system/web_v2/templates/{consultant.html,admin.html,report.html} 的相关改动(git diff)核对:\n` +
    `- consultant.html: 店长是否能看到 escalation 列表+「已跟进」按钮且调对了接口? 普通顾问是否仍看不到? 查看后是否会刷新使 badge 递减?\n` +
    `- admin.html: 升级看板是否展示了 店长已读/店长已处理(含处理人)/待跟进过滤?\n` +
    `- report.html: 分割文案矛盾是否消除?\n` +
    `- 有无明显 JS 错误(未定义函数、fetch 路径打错、漏 await)。按 schema 输出,problems 带 file:line。`,
    { label: 'verify-frontend', phase: 'Verify', schema: VSCHEMA }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 4: Report ----------------
phase('Report')
const summary = await agent(
  `汇总 Wave1 验证结论。三个验证 agent 的结构化结果:\n${JSON.stringify(verds, null, 2)}\n\n` +
  `后端契约:\n${reminderContract}\n\napi/me修复:\n${apiMeFix}\n\n` +
  `请输出一份简体中文交付报告: 1)Wave1 做了什么(分提醒大改/分割文案/api-me三块); 2)验证是否通过、每个维度结论; 3)还存在的问题或未尽事项(带 file:line); 4)给监督者(我)的明确结论: 可以交付 / 需返工(列出返工项)。实话实说,有问题就报。`,
  { label: 'wave1-report', phase: 'Report' }
)

return { contract: reminderContract, apiMeFix, frontend: fe, verifications: verds, summary }
