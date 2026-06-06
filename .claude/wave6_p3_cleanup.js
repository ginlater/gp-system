export const meta = {
  name: 'wave6-p3-cleanup',
  description: 'P3收尾: 删死代码session_rebind + 分割录音事务健壮性 + suggest_merge成本约束，含强制验证',
  phases: [
    { title: 'Backend', detail: 'webapp.py 串行: 确认并删死代码 → 分割事务健壮性 → suggest_merge批量约束' },
    { title: 'Frontend', detail: 'admin.html: suggest_merge按分类引导 + null守卫' },
    { title: 'Verify', detail: '对抗式: 死代码真死/分割部分失败不留脏数据/批量约束 + 真机' },
    { title: 'Report', detail: '汇总' },
  ],
}

const CTX = `
【项目】Flask 单体 /opt/gp-system/webapp.py(约11900行,W1-5已上线提交,工作区干净) + web_v2/templates/*.html。DB=/opt/gp-system/recordings.db(生产中,勿动;验证一律用 /tmp 副本+DB_PATH覆盖+monkeypatch Thread.start防烧钱)。
**改前 grep/Read 按函数名定位,别信旧行号。改完 python3 -c "import ast;ast.parse(open('/opt/gp-system/webapp.py').read())" 自检。这是给生产收尾,从严、稳为先。**
`

// ---------------- Phase 1: Backend (串行) ----------------
phase('Backend')
log('P3 后端: 删死代码 → 分割健壮性 → suggest_merge 约束')

const c1 = await agent(
  `${CTX}\n\n你负责【删除死代码: 整session换绑路由 + 其候选接口】,只改 /opt/gp-system/webapp.py。\n` +
  `背景: 存在一个"整 session 换绑"的路由 api_admin_session_rebind(grep 'session/<int:sid>/rebind' 或 'api_admin_session_rebind') 会逐条搬走该 session 全部录音(=换整个客人),以及其候选接口 rebind_candidates(grep 'rebind_candidates' 里管理端那个 api_admin_session_rebind_candidates)。W1调查说它们在前端无任何调用、是历史遗留死代码。\n` +
  `**务必先证实它们真死再删**: \n` +
  `1) grep 整个 /opt/gp-system/web_v2/ 确认没有任何模板调用 /api/admin/session/<sid>/rebind 或该候选端点(注意区分: 顾问端 Wave4 新加的 /api/consultant/rebind_candidates 是活的,别误删;要删的是管理端"整session"那个 api_admin_session_rebind 及其专属候选 api_admin_session_rebind_candidates)。\n` +
  `2) grep webapp.py 确认没有内部函数调用它们。\n` +
  `3) 只有在确认零引用后,才删除这两个路由函数(连同 @app.route 装饰器)。**若发现任何引用,不要删,改为在返回里报告"非死代码,放弃删除"并说明引用处。**\n` +
  `删完 ast.parse 自检 + grep 确认无残留引用导致 NameError。返回: 是否删除、删了哪些函数(行号)、证实死代码的 grep 证据;若未删说明原因。`,
  { label: 'rm-deadcode', phase: 'Backend' }
)

const c2 = await agent(
  `${CTX}\n\n你负责【分割录音端点的事务健壮性/幂等】,只改 /opt/gp-system/webapp.py。先 grep/Read api_admin_recording_split(grep 'recordings/<int:rid>/split' 或 '_run_ffmpeg'/'_split_asr_at')确认现状。\n` +
  `已知问题(W1调查): 分割流程是 上传k1→上传k2→插入两条新recordings→删原OSS对象→删原recordings行→原session分析标outdated,多步外部副作用(OSS双写+删原+多条DB写)无事务,部分失败会留脏数据(原录音与两段并存/新mp3已传但DB未提交)。\n` +
  `要做(稳为先,不改变正常成功路径的功能,只增强失败健壮性):\n` +
  `1) 调整顺序使"提交点"清晰: 先把两条新 recordings 全部 INSERT 并提交DB成功,再删原OSS对象;**删原OSS失败只记日志、不影响主流程**(原对象残留是可接受的孤儿,远好于DB不一致)。原recordings行的删除与新行插入尽量同一DB事务/批次,避免"原行已删但新行未提交"。\n` +
  `2) 幂等/防重: 若同一rid重复触发分割(或原录音已不存在/已被分割),要优雅处理(返回明确错误而非崩溃或重复产出)。可加一个轻量标记或前置校验(rec存在且未被标记分割中)。\n` +
  `3) 失败回滚: 若在DB提交前失败,清理已上传的k1/k2(现有逻辑 parts_created=False 时删k1/k2,确认其覆盖所有早期失败分支)。\n` +
  `4) 不改ffmpeg切割/ASR切分/换绑等其它逻辑,只动事务顺序与失败处理。保持 manager_required 鉴权。\n` +
  `注意 webapp.py 刚被上一个agent改过,重新Read、唯一字符串Edit。改完 ast.parse 自检。返回: 调整后的步骤顺序、幂等/防重做法、失败清理覆盖的分支。`,
  { label: 'split-robust', phase: 'Backend' }
)

const c3 = await agent(
  `${CTX}\n\n你负责【suggest_merge 成本约束】,只改 /opt/gp-system/webapp.py。先 grep/Read api_admin_tags_suggest_merge(grep 'suggest_merge')确认现状。\n` +
  `背景(Wave5验证提示): 该端点一键会对全部~1888碎片词分~16批全量调LLM,成本/耗时高。要加运营约束(不破坏功能,只防失控):\n` +
  `1) 加可选参数 max_batches(默认如3)或 limit(候选词上限,默认如360),单次调用只处理前N批/N个候选(按出现次数高优先),返回里告知"还剩多少未处理碎片词"。让管理员可分次/按分类逐步生成,而非一次烧16批。\n` +
  `2) ?category= 已支持作为LLM归类提示——若传了category,优先取该类相关碎片词(或在提示里强约束),让"按分类分批"真正可行。\n` +
  `3) 返回结构补充 remaining(未处理碎片词数) 字段,供前端提示"继续生成"。\n` +
  `不改聚类质量/审核应用逻辑,只加批量上限与remaining反馈。注意 webapp.py 刚被改过,重新Read、唯一字符串。改完 ast.parse 自检。返回: 新参数、默认值、remaining字段、按分类取词逻辑。`,
  { label: 'suggest-cost-guard', phase: 'Backend' }
)

const backendContract = `== 删死代码 ==\n${c1}\n\n== 分割健壮性 ==\n${c2}\n\n== suggest_merge约束 ==\n${c3}`

// ---------------- Phase 2: Frontend ----------------
phase('Frontend')
log('admin.html: suggest_merge 按分类引导 + null守卫')

const fe = await parallel([
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/admin.html(AI建议归并 成本引导 + 小修)。后端契约:\n${backendContract}\n\n` +
    `要做:\n1) AI建议归并区(genTagMerge/#tmGenBtn,约admin.html:1118-1208 区域)对接后端新的批量约束: 默认按分类/小批量生成(用 max_batches/limit 参数),展示后端返回的 remaining(还剩N个未处理碎片词),并提供"继续生成下一批"按钮。引导管理员分批做、别一次全量,避免烧钱/超时。提示文案说明。\n` +
    `2) 补 genTagMerge() 里 document.getElementById('tmCat') 的 null 守卫(Wave5验证提的风格项,虽#tmCat静态恒存在,加上更稳)。\n` +
    `注意 Wave1/3/4/5 改过 admin.html(升级看板/档案/分页/看板按客人/AI归并UI),重新Read、唯一字符串、别冲突。保持现有class/showMsg/_esc模式。Jinja venv 自检parse。返回改动摘要。`,
    { label: 'fe-admin', phase: 'Frontend' }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 3: Verify ----------------
phase('Verify')
log('对抗式: 死代码真死/分割健壮/成本约束 + 真机')

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
    `对抗式【证伪】P3 后端。契约:\n${backendContract}\n\n` +
    `Read webapp.py + git diff 逐条核:\n` +
    `删死代码: 被删的确实是管理端"整session换绑"api_admin_session_rebind及其候选,不是顾问端Wave4活路由? grep 全 web_v2 + webapp.py 确认零引用(无残留调用导致404/NameError)? 若agent报告"未删"则核其引用证据是否成立。\n` +
    `分割健壮性: 新顺序是否"DB提交成功后才删原OSS、删原OSS失败仅记日志"? 原行删除与新行插入是否同事务/避免中间态? 幂等/防重前置校验是否存在? 失败清理k1/k2是否覆盖早期分支? 构造思维反例:删原OSS抛异常时,DB是否已一致(两段在、原行已删)?\n` +
    `suggest_merge约束: max_batches/limit默认是否合理? remaining字段是否正确反映未处理数? category取词是否生效? 不影响聚类/审核逻辑?\n` +
    `ast.parse过? 按schema输出,problems带file:line。`,
    { label: 'verify-logic', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `P3 真机冒烟+前端,不破坏线上库。1) cp recordings.db(+wal/shm,checkpoint副本)到/tmp/wave6_smoke.db; 2) Flask test_client(import前 monkeypatch threading.Thread.start=no-op + _call_llm防烧钱),export DB_PATH=副本并assert; 3) /healthz 200 无崩溃(确认删死代码+分割改动+约束改动不让启动崩); 4) 确认被删路由确实404(GET/POST 原 /api/admin/session/<sid>/rebind 应 404 Not Found,证明已删且无残留注册); 5) 顾问端Wave4活路由 /api/consultant/rebind_candidates 仍正常(确认没误删); 6) admin GET /api/admin/tags/suggestions?status=pending 仍200(suggest相关未坏); 7) 前端: Read admin.html git diff,核 AI建议归并按分类/分批引导+remaining提示+null守卫,与W1/3/4/5无冲突,Jinja parse+JS完整; 8) 清理副本+复核线上库md5未变。按schema输出,problems带file:line。`,
    { label: 'verify-runtime-fe', phase: 'Verify', schema: VSCHEMA }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 4: Report ----------------
phase('Report')
const summary = await agent(
  `汇总 P3 收尾验证。结果:\n${JSON.stringify(verds, null, 2)}\n后端契约:\n${backendContract}\n前端:\n${JSON.stringify(fe)}\n\n` +
  `输出简体中文交付报告: 1)P3做了什么(删死代码/分割健壮性/suggest约束); 2)死代码是否确认真死且删除无断链(没误删顾问端活路由); 3)分割部分失败是否不再留脏数据; 4)各验证维度结论; 5)遗留问题(带file:line); 6)给监督者明确结论:可交付/需返工(列返工项)。实话实说。这是无人值守的生产收尾,从严判定,有任何存疑都明确标出。`,
  { label: 'p3-report', phase: 'Report' }
)

return { backendContract, frontend: fe, verifications: verds, summary }
