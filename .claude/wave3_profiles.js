export const meta = {
  name: 'wave3-customer-profiles',
  description: 'Wave3客户档案cluster: 档案分Tab+三列(P1.4)+豆包三档骨架(P1.5)+重复客人分页(P1.6)+顾问看档案权限模型(P1.7)，含强制验证',
  phases: [
    { title: 'Backend', detail: 'webapp.py 串行: 档案聚合端点+权限模型 → 重复客人分页 → 豆包provider骨架' },
    { title: 'Frontend', detail: '并行: admin.html(分Tab+档案列+分页) / customer_profile.html(模型选择器) / consultant.html(档案入口)' },
    { title: 'Verify', detail: '对抗式: 顾问越权听录音被拦? + 真机跑 + 前端审查' },
    { title: 'Report', detail: '汇总' },
  ],
}

const CTX = `
【项目】Flask 单体 /opt/gp-system/webapp.py(约11400行,Wave1/2已改过工作区未提交) + web_v2/templates/*.html。DB=/opt/gp-system/recordings.db(DB_PATH可覆盖)。
**改前必须先 Read/grep 按函数名定位,Wave1/2 已让行号偏移,别信旧行号。改完 python3 -c "import ast;ast.parse(open('/opt/gp-system/webapp.py').read())" 自检。**
【相关现状(行号近似,以实际为准)】
- 顾客列表 GET /api/admin/customers(约6437) 只 SELECT id,name,member_card,phone_tail,company_id,created_at; 返回 customers/total/total_all; @manager_required。
- 单客详情 GET /api/admin/customer_profile(约7553) 返回 info/accumulated_tags/sessions(每次接诊 service_date/advisor/store_name/tags)/session_count; @manager_required。
- 客户价值 GET /api/admin/customer_value(约7715) 读 customer_value_cache; POST(约7751) 调LLM生成,**已读 data.get("model")**; customer_value_cache 列含 model/content/source_signature。
- 模型 SUPPORTED_MODELS(约71-76) 只有 deepseek-v4-pro + claude-sonnet-4-6; _call_llm 派发(约3195-3204) 仅 anthropic/deepseek; GET /api/models(约5439) 列模型。
- 疑似重复 GET /api/admin/customers/duplicates(约6746) 无分页,Python 内存全量分桶返回 groups; @manager_required。
- 页面 /customer/<id>(约4544 customer_page) 渲染 customer_profile.html; @manager_required。
- 录音签名URL GET /api/recording/<rid>/url(约5119) 返回可听的签名URL —— 这是"听录音"的真正闸口。
- 客户搜索 GET /api/customers/search(约7808) 已存在。
- recordings 表有 advisor(文本)、uploader_user_id、store_id; users 有 advisor_name/store_id/role。
【已决策的权限模型(P1.7,务必精确)】
- 客人档案(含价值预测/痛点作战方案) = 所有登录用户都能看(含普通顾问,能看不是自己负责的客人档案)。但仍限本公司(按 session company_id),顾问不能跨公司。
- 录音(音频)只有 管理员/超管、店长(本店)、以及该录音的接待顾问 能听。判定"接待顾问"= recording.advisor==users.advisor_name 或 recording.uploader_user_id==users.id。
- 即: 放开档案"看",收紧录音"听"。
【已决策(P1.5)】豆包凭证暂无 → 只搭骨架: SUPPORTED_MODELS 加两档(读环境变量配置,空则 disabled),provider 派发加 doubao 分支(未配置时抛清晰错误),前端模型选择器列三档但豆包档置灰。三档 label: 极速版=doubao-2.0-lite, 进阶版=doubao-2.0-pro, 专家版=deepseek-v4-pro(现成可用)。
`

// ---------------- Phase 1: Backend (串行) ----------------
phase('Backend')
log('Wave3 后端: 档案聚合+权限 → 重复客人分页 → 豆包骨架')

const c1 = await agent(
  `${CTX}\n\n你负责【客户档案聚合端点 + P1.7 权限模型】,只改 /opt/gp-system/webapp.py。先 Read customers / customer_profile / customer_value / customer_page / recording url 端点确认现状。要做:\n\n` +
  `1) 新增分页聚合端点 GET /api/admin/customer_profiles (供"顾客档案"Tab用,不要改现有 /api/admin/customers):\n` +
  `   - 参数: page(默认1)、page_size(默认100)、q(搜索 姓名/会员卡号/尾号)。\n` +
  `   - 每个客人返回: id, name, member_card, phone_tail, latest_advisor(最新接待人=该客人最近一次 session 的 advisor), latest_service_date(最新服务时间=最近 session.service_date 或对应录音 recorded_at,精确到年月日), has_profile(是否建档=customer_value_cache 是否存在该客人的缓存行 → bool)。\n` +
  `   - 用一条带 LEFT JOIN 的 SQL(利用 Wave2 建的 idx_sessions_cust_date): 对每客取最新一条 session(按 service_date desc) + LEFT JOIN customer_value_cache 判断 has_profile。按 customer_id 关联,customer_id 为空的老客户用姓名兜底(沿用 _customer_session_clause 思路)。务必分页(LIMIT/OFFSET),返回 {items, page, page_size, total}。\n` +
  `   - 鉴权 manager_required(此 Tab 在管理端)。\n\n` +
  `2) P1.7 放开档案"看": 把 /customer/<id> 页面(customer_page)、GET /api/admin/customer_profile、GET/POST /api/admin/customer_value 三处的 @manager_required 改为 @login_required(所有登录角色可访问),但**内部保留 company_id 作用域**(顾问只能看本公司客人;super 跨公司维持)。store_manager/admin 行为不变。注意 POST customer_value(触发LLM生成)是否也对顾问开放——按决策"档案含价值预测所有人可看",顾问可看已生成的;**生成(POST)建议仍限 manager 触发以免顾问乱触发烧钱**,GET 对所有人开。请这样区分: GET customer_value=login_required, POST customer_value=manager_required。\n\n` +
  `3) P1.7 收紧录音"听"(安全核心): 修改 GET /api/recording/<rid>/url(约5119),加权限闸:\n` +
  `   - admin/super: 放行; store_manager: 仅本店录音(recording.store_id==本人 store_id)放行; consultant: 仅当 recording.advisor==本人 advisor_name 或 recording.uploader_user_id==本人 id 放行; 否则 403 {"error":"无权收听非本人接待的录音"}。\n` +
  `   - 这是后端硬闸,即使前端显示播放按钮也必须拦住。Read 该端点现有鉴权再改,保持其余逻辑。\n\n` +
  `返回【契约摘要】: customer_profiles 端点(参数+返回字段)、三处档案端点鉴权变化、recording url 新权限规则。`,
  { label: 'profiles-perm', phase: 'Backend' }
)

const c2 = await agent(
  `只改 /opt/gp-system/webapp.py(P1.6 疑似重复客人分页)。先 Read GET /api/admin/customers/duplicates(约6746,Wave1/2后行号有偏移,grep 'duplicates' 定位)确认现状(现为 Python 内存全量分桶返回 groups,无分页)。\n` +
  `要做: 给该端点加分页——参数 page(默认1)、page_size(默认20,单位=组)。保持现有分桶逻辑正确性,在返回前对 groups 做切片,返回 {groups:本页, page, page_size, total_groups}。当前实测仅约11组、性能无忧,所以分页主要为展示与未来增长;但请在注释里标注"分桶仍全量加载,组数大增时需改SQL HAVING"。鉴权维持 manager_required。\n` +
  `注意此时 webapp.py 刚被上一个 agent 改过,重新 Read、用唯一字符串 Edit。改完 ast.parse 自检。返回改动摘要(新参数+返回结构)。`,
  { label: 'dup-pagination', phase: 'Backend' }
)

const c3 = await agent(
  `只改 /opt/gp-system/webapp.py(P1.5 豆包三档模型骨架,凭证暂无)。先 Read SUPPORTED_MODELS / MODEL_PROVIDER / _call_llm 派发 / GET /api/models / _call_deepseek 确认现状。要做:\n` +
  `1) SUPPORTED_MODELS 增加两档(给每档加一个中文 tier_label 字段,顺便给现有 deepseek 补 tier_label):\n` +
  `   - {id:'doubao-2.0-lite', provider:'doubao', tier_label:'极速版', name/label 自拟}\n` +
  `   - {id:'doubao-2.0-pro', provider:'doubao', tier_label:'进阶版'}\n` +
  `   - 现有 deepseek-v4-pro: tier_label:'专家版'。\n` +
  `2) 环境变量配置(读 os.environ,空则视为未配置): DOUBAO_API_KEY、DOUBAO_API_BASE(默认火山方舟 https://ark.cn-beijing.volces.com/api/v3)、DOUBAO_EP_LITE、DOUBAO_EP_PRO(接入点ID,豆包用接入点ID而非模型名)。\n` +
  `3) 新增 _call_doubao(model, system_prompt, user_prompt, tool=None, ...): 走火山方舟 OpenAI 兼容接口(可用 requests 或现有 http 客户端,参考 _call_deepseek 写法),用对应接入点ID; **若 DOUBAO_API_KEY 或对应 EP 未配置,raise 一个清晰异常 RuntimeError('豆包(火山方舟)未配置,请设置 DOUBAO_API_KEY/接入点ID')**。这是骨架,不需要真跑通,但代码路径要完整、配置齐了就能用。\n` +
  `4) _call_llm 派发加 provider=='doubao' 分支调 _call_doubao; MODEL_PROVIDER 补两个 id。\n` +
  `5) GET /api/models: 每个模型返回 tier_label; doubao 两档在 DOUBAO 未配置时 disabled=True(类似现有 deepseek 无 key 时 disabled 的写法); deepseek 专家版照常。\n` +
  `注意 webapp.py 刚被前两个 agent 改过,重新 Read、唯一字符串 Edit。改完 ast.parse 自检。返回: SUPPORTED_MODELS 新结构、/api/models 返回字段、_call_doubao 签名与未配置行为。`,
  { label: 'doubao-scaffold', phase: 'Backend' }
)

const backendContract = `== 档案+权限 ==\n${c1}\n\n== 重复客人分页 ==\n${c2}\n\n== 豆包骨架 ==\n${c3}`

// ---------------- Phase 2: Frontend (并行,不同文件) ----------------
phase('Frontend')
log('并行改 admin.html / customer_profile.html / consultant.html')

const fe = await parallel([
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/admin.html(P1.4 拆Tab + 档案三列 + P1.6 重复客人分页)。后端契约:\n${backendContract}\n\n` +
    `要做:\n1) 把现有"顾客管理"面板(现 导入+新增+列表 三块混在一个 tab-customers,约 admin.html:261-303)内部拆成两个二级 Tab: 「客户管理」(Tab1=保留现有 批量导入+新增单个+顾客列表) 和 「顾客档案」(Tab2=新建)。\n` +
    `2) Tab2 顾客档案: 调新端点 GET /api/admin/customer_profiles(分页),表格列: 姓名/会员卡号/最新接待人(latest_advisor)/最新服务时间(latest_service_date)/是否建立档案(has_profile→已建档/未建档徽章)/操作(档案按钮跳 /customer/<id>)。带搜索框(q)+分页控件(page/page_size,显示 total)。仿现有 loadCustomers/renderCustomers 模式。\n` +
    `3) P1.6: 疑似重复客人视图(loadDuplicates,约 admin.html:1193)改为带分页(后端已加 page/page_size/total_groups),加翻页控件与"共N组"显示。\n` +
    `注意 Wave1/2 已改过 admin.html(升级看板、待分析徽章),重新 Read、用唯一字符串、别冲突。保持现有 class/风格。Jinja 用项目 venv 自检 parse。返回改动摘要。`,
    { label: 'fe-admin', phase: 'Frontend' }
  ),
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/customer_profile.html(P1.5 模型选择器)。后端契约:\n${backendContract}\n\n` +
    `现状: 价值预测卡片 genValue() POST /api/admin/customer_value body 只传 {customer_id},无模型选择。要做:\n` +
    `1) 在价值预测卡片加一个模型下拉(选择器): 拉 GET /api/models,按 tier_label 显示三档"极速版/进阶版/专家版",disabled 的(豆包未配置)置灰并标"(未配置)"。默认选 专家版(deepseek-v4-pro,现成可用)。\n` +
    `2) genValue() 把选中的 model 一起 POST(后端已支持 data.get('model'))。\n` +
    `3) 该页面现在也会被普通顾问访问(P1.7 放开了档案查看)——确保页面对顾问正常渲染;价值预测的"生成"按钮(POST)若后端对顾问返回403(POST 仍限 manager),前端要优雅提示"仅管理员/店长可生成"而不是报错崩溃。\n` +
    `保持现有风格。Jinja venv 自检 parse。返回改动摘要。`,
    { label: 'fe-profile', phase: 'Frontend' }
  ),
  () => agent(
    `只改 /opt/gp-system/web_v2/templates/consultant.html(P1.7 顾问看档案入口)。后端契约:\n${backendContract}\n\n` +
    `已决策: 顾问也能看任意客人档案(含价值预测),但只能听自己接待客人的录音(录音后端已加硬闸)。要做:\n` +
    `1) 给顾问端加一个"客户档案"入口/页签: 一个搜索框调 GET /api/customers/search(已存在),列出客人,点击跳 /customer/<id>(该页面/接口已对顾问放开)。\n` +
    `2) 不需要在顾问端重做档案详情页(复用 /customer/<id>)。只要"能搜到→能打开档案"。\n` +
    `3) 录音收听限制由后端 /api/recording/<rid>/url 的403保证;若顾问在档案/会话里点了非本人录音,前端应优雅提示"无权收听非本人接待的录音"而非静默失败(若涉及的播放代码在本文件)。\n` +
    `注意 Wave1 已改过 consultant.html(提醒/升级),重新 Read、唯一字符串、别冲突。保持现有风格。Jinja venv 自检。返回改动摘要。`,
    { label: 'fe-consultant', phase: 'Frontend' }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 3: Verify ----------------
phase('Verify')
log('对抗式验证: 顾问越权听录音 + 真机 + 前端')

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
    `对抗式【证伪】Wave3 后端,重点 P1.7 权限模型(安全)。契约:\n${backendContract}\n\n` +
    `Read /opt/gp-system/webapp.py 相关端点 + git diff,逐条核:\n` +
    `- 录音听闸(安全核心): GET /api/recording/<rid>/url 是否真的: admin/super 放行、store_manager 限本店、consultant 仅 advisor==本人或 uploader==本人、否则403? 构造反例(一个顾问去取另一个顾问 advisor 的 recording url)在逻辑上是否会被拦? 有没有绕过(比如其它返回签名URL的端点没设防)? grep 是否还有别的端点直接吐 recording 签名URL/oss key 给前端而未设防。\n` +
    `- 档案看放开: customer_page / GET customer_profile / GET customer_value 是否改 login_required 且仍按 company_id 限本公司(顾问不能跨公司)? POST customer_value 是否仍 manager_required(防顾问触发烧钱)?\n` +
    `- customer_profiles 聚合端点: 分页参数对不对? 最新接待人/最新服务时间/是否建档 SQL 是否正确(利用 idx_sessions_cust_date)? 有无 N+1?\n` +
    `- 重复客人分页: page/page_size/total_groups 是否正确?\n` +
    `- 豆包骨架: SUPPORTED_MODELS 三档+tier_label? _call_doubao 未配置时抛清晰错误(不静默)? /api/models disabled 标记对? 派发分支对?\n` +
    `- ast.parse 过? 按 schema 输出,problems 带 file:line。`,
    { label: 'verify-backend', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `Wave3 真机冒烟,不破坏线上库。1) cp /opt/gp-system/recordings.db /tmp/wave3_smoke.db; 2) export DB_PATH=副本,venv python 非5058端口起 webapp(.env setdefault,先export再import并assert webapp.DB_PATH==副本; 可把 threading.Thread.start 打no-op拦后台线程避免烧钱); 3) /healthz 200 无崩溃; 4) GET /api/models 确认返回三档+tier_label,豆包档 disabled=True(因副本无DOUBAO配置); 5) 在副本造一个 consultant 用户登录,GET 一个"非他advisor"的 recording url 应得403、GET 自己advisor的应200(或合理); GET /api/admin/customer_profiles?page=1&page_size=5 看是否返回 items+total+三列字段; GET duplicates?page=1 看分页结构; 6) 关服+删副本+清理。按 schema 输出,evidence 写清命令与HTTP码。`,
    { label: 'verify-runtime', phase: 'Verify', schema: VSCHEMA }
  ),
  () => agent(
    `审查 Wave3 前端三模板。前端自述:\n${JSON.stringify(fe)}\n后端契约:\n${backendContract}\n\n` +
    `Read git diff 核对:\n- admin.html: 顾客管理是否拆成两个二级Tab? 顾客档案Tab是否调 /api/admin/customer_profiles 且渲染 最新接待人/最新服务时间/是否建档 三列+搜索+分页? 重复客人是否加了分页控件? 与Wave1/2改动(升级看板/待分析徽章)有无冲突?\n- customer_profile.html: 模型选择器是否拉/api/models按tier_label显示三档、豆包置灰、默认专家版? genValue是否传model? 顾问访问时POST 403是否优雅提示?\n- consultant.html: 是否加了客户档案搜索入口跳/customer/<id>? 与Wave1提醒改动有无冲突?\n- 三模板 Jinja venv parse 是否OK? 有无JS括号失衡/误删函数/fetch路径错。按 schema 输出,problems带file:line。`,
    { label: 'verify-frontend', phase: 'Verify', schema: VSCHEMA }
  ),
]).then(r => r.filter(Boolean))

// ---------------- Phase 4: Report ----------------
phase('Report')
const summary = await agent(
  `汇总 Wave3 验证。结果:\n${JSON.stringify(verds, null, 2)}\n后端契约:\n${backendContract}\n前端:\n${JSON.stringify(fe)}\n\n` +
  `输出简体中文交付报告: 1)Wave3做了什么(档案分Tab+三列/豆包骨架/重复分页/顾问看档案权限); 2)P1.7权限模型安全是否确证(顾问越权听录音被拦=重点); 3)各验证维度结论; 4)遗留问题(带file:line); 5)给监督者明确结论:可交付/需返工(列返工项)。实话实说。`,
  { label: 'wave3-report', phase: 'Report' }
)

return { backendContract, frontend: fe, verifications: verds, summary }
