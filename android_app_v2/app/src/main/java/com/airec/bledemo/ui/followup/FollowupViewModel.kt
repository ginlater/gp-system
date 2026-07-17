package com.airec.bledemo.ui.followup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.auth.AuthManager
import com.airec.bledemo.data.followup.FollowupRepository
import com.airec.bledemo.data.followup.FollowupRepository.GenEvent
import com.airec.bledemo.data.followup.ScriptSystem
import com.airec.bledemo.data.model.FormBranch
import com.airec.bledemo.data.model.FormField
import com.airec.bledemo.data.model.FormSpecFile
import com.airec.bledemo.data.net.NetworkModule
import com.airec.bledemo.data.repo.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 回访话术 / 高情商话术 ViewModel v2 —— 规格驱动(P1.3)。
 *
 * 表单结构不再手写:assets/followup_form_spec.json 是从网页 index.html 离线提取的
 * 完整规格(全部字段/选项/级联显隐),本 VM 只负责状态与收集,与网页 collectData 同构:
 *  - 只收集**当前可见**字段(级联隐藏的不进 customer_data,与网页 pickValue 一致);
 *  - 同 id 多节选项组自动合并;
 *  - 选「自定义」出现的文本框按 customInput.id 收集。
 * 生成走 FollowupRepository 的 SSE 流式。
 */
class FollowupViewModel : ViewModel() {

    private val _state = MutableStateFlow(FollowupUiState())
    val state: StateFlow<FollowupUiState> = _state.asStateFlow()

    private var repo: FollowupRepository? = null
    private var genJob: Job? = null

    /** 首次进入:定系统、读规格、预填顾问姓名、拉额度。重复调忽略。 */
    fun init(systemKey: String) {
        if (repo != null) return
        val sys = ScriptSystem.byKey(systemKey) ?: ScriptSystem.Followup
        repo = FollowupRepository(sys)
        _state.update { it.copy(system = sys) }
        viewModelScope.launch {
            val spec = withContext(Dispatchers.IO) { loadSpec() }
            _state.update { st ->
                st.copy(
                    spec = spec,
                    selections = defaultSelections(spec),
                    texts = st.texts + ("consultantName" to AuthManager.lastMe?.advisorName.orEmpty()),
                )
            }
        }
        // 动态选项(todayProject/痛点/护理项目等按公司下发);失败退回规格里的静态选项
        viewModelScope.launch {
            (repo?.projectOptions() as? ApiResult.Success)?.let { r ->
                _state.update { it.copy(dynamicOptions = r.data) }
            }
        }
        // 顾问姓名/所属门店自动带出:名单里匹配到本人 → 门店跟着填(与网页"选择顾问后门店自动填写"同源)
        viewModelScope.launch {
            (repo?.employees() as? ApiResult.Success)?.let { r ->
                val myName = AuthManager.lastMe?.advisorName.orEmpty()
                val me = r.data.employees.orEmpty().firstOrNull { it.name == myName }
                if (me?.store != null) {
                    _state.update { it.copy(texts = it.texts + ("companyName" to me.store)) }
                }
            }
        }
        loadQuota()
    }

    /**
     * 网页里带预选默认值的组,进页面即选中(与网页一致):
     * 「是/否」型提及行默认「否」;节气行默认「带入当前节气」。
     */
    private fun defaultSelections(spec: FormSpecFile?): Map<String, List<String>> {
        val out = mutableMapOf<String, List<String>>()
        val all = spec?.common.orEmpty() + spec?.branches.orEmpty().flatMap { it.fields.orEmpty() }
        all.forEach { f ->
            val id = f.id ?: return@forEach
            if (!f.isOptions || f.multi == true || id in out) return@forEach
            val opts = f.options.orEmpty()
            when {
                "带入当前节气" in opts -> out[id] = listOf("带入当前节气")
                opts.size == 2 && "是" in opts && "否" in opts -> out[id] = listOf("否")
            }
        }
        return out
    }

    private fun loadSpec(): FormSpecFile? = runCatching {
        NetworkModule.appContext.assets.open(SPEC_ASSET).bufferedReader().use { r ->
            NetworkModule.moshi.adapter(FormSpecFile::class.java).lenient().fromJson(r.readText())
        }
    }.getOrNull()

    fun loadQuota() {
        val r = repo ?: return
        viewModelScope.launch {
            when (val q = r.quota()) {
                is ApiResult.Success -> {
                    val groups = q.data.groups.orEmpty()
                    val bypass = q.data.bypass == true
                    val g = groups[quotaGroupOf(_state.value.model)]
                    val text = when {
                        bypass || g?.limit == -1 -> "本月不限次"
                        g != null -> "本月剩余 ${(g.limit ?: 0) - (g.used ?: 0)} 次"
                        else -> ""
                    }
                    _state.update {
                        it.copy(quotaText = text, quotaGroups = groups, quotaBypass = bypass, loginError = null)
                    }
                }
                is ApiResult.Failure -> _state.update { it.copy(loginError = q.message) }
            }
        }
    }

    // ─────────────────────────── 表单交互 ───────────────────────────

    fun selectNature(index: Int) = _state.update { it.copy(natureIndex = index) }

    fun setModel(model: String) {
        _state.update { it.copy(model = model) }
        loadQuota()   // 各模型组额度不同,切换后刷新
    }

    /** 点选项:单选=点已选取消/点新的替换;多选=切换。与网页行为一致。 */
    fun toggleOption(groupId: String, option: String, multi: Boolean) {
        _state.update { st ->
            val cur = st.selections[groupId].orEmpty()
            val next = when {
                multi -> if (option in cur) cur - option else cur + option
                option in cur -> emptyList()
                else -> listOf(option)
            }
            st.copy(selections = st.selections + (groupId to next))
        }
    }

    fun setText(id: String, value: String) {
        _state.update { it.copy(texts = it.texts + (id to value)) }
    }

    // ─────────────────────────── 生成 ───────────────────────────

    /** 与网页 collectData 同构:顶层公共 + visitNature + 当前分支可见字段。 */
    fun generate() {
        val r = repo ?: return
        val st = _state.value
        if (st.generating) return

        // 必填校验(只查可见的;无选项的下拉退化成文本,认文本值)
        val missing = st.visibleBranchFields().firstOrNull { f ->
            f.required == true && when {
                f.isOptions && st.resolvedOptions(f).isNotEmpty() -> st.selections[f.id].isNullOrEmpty()
                else -> st.texts[f.id].isNullOrBlank()
            }
        }
        if (st.texts["customerName"].isNullOrBlank()) {
            _state.update { it.copy(toast = "请先填写顾客姓名") }
            return
        }
        if (missing != null) {
            _state.update { it.copy(toast = "「${missing.label ?: missing.id}」是必填项") }
            return
        }

        val data = mutableMapOf<String, Any?>()
        // 顶层公共字段(规格 common 里的 text/textarea 全收,选项组同分支规则)
        st.spec?.common.orEmpty().forEach { f -> collectField(f, st, data) }
        data["visitNature"] = listOf(st.natureText)
        // 当前分支:只收可见字段
        st.visibleBranchFields().forEach { f -> collectField(f, st, data) }

        _state.update { it.copy(generating = true, output = "", warnWords = emptyList(), genError = null, resultMode = true) }
        genJob = viewModelScope.launch {
            r.generateStream(data, model = st.model).collect { ev ->
                when (ev) {
                    is GenEvent.Content -> _state.update { it.copy(output = it.output + ev.delta) }
                    is GenEvent.Restart -> _state.update { it.copy(output = "", toast = "内容触发合规重写，正在重新生成…") }
                    is GenEvent.Warning -> _state.update { it.copy(warnWords = ev.words) }
                    is GenEvent.Failed -> _state.update { it.copy(generating = false, genError = ev.message) }
                    is GenEvent.Done -> {
                        _state.update { it.copy(generating = false) }
                        loadQuota()
                        val done = _state.value
                        val name = done.texts["customerName"].orEmpty()
                        if (name.isNotBlank() && done.output.isNotBlank()) {
                            launch { r.saveCustomer(name, data, done.output, done.model) }
                        }
                    }
                }
            }
        }
    }

    private fun collectField(f: FormField, st: FollowupUiState, out: MutableMap<String, Any?>) {
        val id = f.id ?: return
        when {
            f.isOptions -> {
                val sel = st.selections[id].orEmpty()
                if (sel.isNotEmpty()) {
                    // 同 id 多节:可能已收过,合并去重
                    @Suppress("UNCHECKED_CAST")
                    val prev = out[id] as? List<String> ?: emptyList()
                    out[id] = (prev + sel).distinct()
                } else {
                    // 无选项的下拉(consultantName 等)退化成文本输入,按字符串收
                    st.texts[id]?.takeIf { it.isNotBlank() }?.let { out[id] = it }
                }
                // 自定义文本框:对应选项被选中才收
                val ci = f.customInput
                if (ci?.id != null && ci.showWhen.orEmpty().any { it in sel }) {
                    st.texts[ci.id]?.takeIf { it.isNotBlank() }?.let { out[ci.id!!] = it }
                }
            }
            else -> st.texts[id]?.takeIf { it.isNotBlank() }?.let { out[id] = it }
        }
    }

    fun stopGenerate() {
        genJob?.cancel()
        genJob = null
        _state.update { it.copy(generating = false) }
    }

    fun backToForm() {
        stopGenerate()
        _state.update { it.copy(resultMode = false, genError = null) }
    }

    fun toastShown() = _state.update { it.copy(toast = null) }

    // ─────────────────────────── 导入历史 / 收藏 / 战果 ───────────────────────────

    fun openHistory() {
        _state.update { it.copy(historyOpen = true) }
        loadCustomers()
    }

    fun closeHistory() = _state.update { it.copy(historyOpen = false, businessFor = null) }

    fun loadCustomers() {
        val r = repo ?: return
        _state.update { it.copy(historyLoading = true) }
        viewModelScope.launch {
            when (val res = r.customers()) {
                is ApiResult.Success -> _state.update {
                    it.copy(historyLoading = false, customers = res.data.customers.orEmpty())
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(historyLoading = false, toast = res.message)
                }
            }
        }
    }

    fun toggleFavorite(c: com.airec.bledemo.data.model.FuCustomer) {
        val r = repo ?: return
        val name = c.name ?: return
        val target = c.favorited != true
        // 乐观更新
        _state.update { st ->
            st.copy(customers = st.customers.map { if (it.name == name) it.copy(favorited = target) else it })
        }
        viewModelScope.launch {
            if (r.favorite(name, target) is ApiResult.Failure) loadCustomers()  // 失败回读
        }
    }

    /** 把历史顾客的表单数据回填(与网页「导入历史」等价):列表值进选项组,字符串进文本框。 */
    fun importCustomer(c: com.airec.bledemo.data.model.FuCustomer) {
        val data = c.data.orEmpty()
        val sel = mutableMapOf<String, List<String>>()
        val txt = mutableMapOf<String, String>()
        var nature = _state.value.natureIndex
        data.forEach { (k, v) ->
            when (v) {
                is List<*> -> {
                    val list = v.filterIsInstance<String>()
                    if (k == "visitNature") {
                        list.firstOrNull()?.let { n ->
                            val i = _state.value.branches.indexOfFirst { b -> b.nature == n }
                            if (i >= 0) nature = i
                        }
                    } else sel[k] = list
                }
                is String -> if (v.isNotBlank()) txt[k] = v
                else -> Unit
            }
        }
        _state.update { st ->
            st.copy(
                natureIndex = nature,
                selections = st.selections + sel,
                texts = st.texts + txt,
                historyOpen = false,
                toast = "已导入「${c.name}」的资料",
            )
        }
    }

    fun openBusiness(c: com.airec.bledemo.data.model.FuCustomer) =
        _state.update { it.copy(businessFor = c) }

    fun closeBusiness() = _state.update { it.copy(businessFor = null) }

    /** 提交战果(写该顾客最新话术版本)。 */
    fun submitBusiness(body: Map<String, Any?>) {
        val r = repo ?: return
        val c = _state.value.businessFor ?: return
        val name = c.name ?: return
        val idx = (c.scripts?.size ?: 1) - 1
        viewModelScope.launch {
            when (r.recordBusiness(name, idx.coerceAtLeast(0), body)) {
                is ApiResult.Success -> _state.update {
                    it.copy(businessFor = null, toast = "战果已登记")
                }
                is ApiResult.Failure -> _state.update { it.copy(toast = "战果登记失败,请重试") }
            }
            loadCustomers()
        }
    }

    companion object {
        const val SPEC_ASSET = "followup_form_spec.json"

        /** AI 模型档位(与网页 modelSelect 一致)。 */
        val MODELS = listOf(
            "claude_sonnet" to "至尊版",
            "deepseek_v4_pro" to "专家版",
            "deepseek_v4_pro_lite" to "进阶版",
            "doubao_seed" to "标准版",
            "doubao_seed_lite" to "极速版",
        )

        /** 模型 → 额度组(配额按组计数:doubao_seed 归 doubao 组,其余同名)。 */
        fun quotaGroupOf(model: String): String = when (model) {
            "doubao_seed" -> "doubao"
            else -> model
        }
    }
}

/** 表单 + 生成态。selections/texts 以网页字段 id 为键。 */
data class FollowupUiState(
    val system: ScriptSystem = ScriptSystem.Followup,
    val spec: FormSpecFile? = null,
    val natureIndex: Int = 0,
    val selections: Map<String, List<String>> = emptyMap(),
    val texts: Map<String, String> = emptyMap(),
    val dynamicOptions: Map<String, List<String>> = emptyMap(),
    val model: String = "claude_sonnet",
    // 生成
    val resultMode: Boolean = false,
    val generating: Boolean = false,
    val output: String = "",
    val warnWords: List<String> = emptyList(),
    val genError: String? = null,
    // 导入历史 / 战果
    val historyOpen: Boolean = false,
    val historyLoading: Boolean = false,
    val customers: List<com.airec.bledemo.data.model.FuCustomer> = emptyList(),
    val businessFor: com.airec.bledemo.data.model.FuCustomer? = null,
    // 杂项
    val quotaText: String = "",
    val quotaGroups: Map<String, com.airec.bledemo.data.model.FuQuotaGroup> = emptyMap(),
    val quotaBypass: Boolean = false,
    val loginError: String? = null,
    val toast: String? = null,
) {
    /** 模型胶囊上的余量后缀:" 98/100" / " 不限";没拉到额度时为空串。 */
    fun quotaSuffix(model: String): String {
        if (quotaBypass) return " 不限"
        val g = quotaGroups[FollowupViewModel.quotaGroupOf(model)] ?: return ""
        val limit = g.limit ?: return ""
        if (limit == -1) return " 不限"
        return " ${(limit - (g.used ?: 0)).coerceAtLeast(0)}/$limit"
    }
    val branches: List<FormBranch> get() = spec?.branches.orEmpty()

    val natureText: String
        get() = branches.getOrNull(natureIndex)?.nature ?: ""

    /** 当前分支的可见字段(级联显隐,与网页一致)。 */
    fun visibleBranchFields(): List<FormField> =
        branches.getOrNull(natureIndex)?.fields.orEmpty().filter { f ->
            (f.visibleWhen?.matches(selections) != false) &&
                (f.parentVisibleWhen?.matches(selections) != false)
        }

    /**
     * ★2026-07-17 —— 这个字段是不是「同一个 id 被拆成多个分类区块」的其中一块?
     *
     * 【背景】网页上「所做的项目」是一个 data-group,底下并排 4 个分类(身体/面部/美学/特殊),
     * 四类里任选一个就算填了。离线抽 followup_form_spec.json 时按 .options 逐个抽,
     * 于是变成 4 条同 id、各自 required=true 的字段;渲染出来就是四个都挂「必填」,
     * 顾问一看以为四类每类都得选。(校验其实是按 id 查 selections,选一个就全过,
     * 所以只是显示误导,不会真拦人——但顾问不知道,照样一个个填。)
     *
     * 【用途】渲染时靠它把「必填」只标在第一块上,后面几块不标,见 FieldCard。
     * 返回该 id 在本分支里出现的次数;>1 就是被拆过的。
     */
    fun sectionCountOf(id: String?): Int {
        if (id == null) return 0
        return branches.getOrNull(natureIndex)?.fields.orEmpty().count { it.id == id }
    }

    /** 该字段是不是同 id 多块里的第一块(第一块才标必填)。 */
    fun isFirstSectionOf(f: FormField): Boolean {
        val id = f.id ?: return true
        val same = branches.getOrNull(natureIndex)?.fields.orEmpty().filter { it.id == id }
        return same.firstOrNull() === f
    }

    /** 字段实际选项:动态类目命中则用服务端下发 + 保留静态兜底项(自定义/都不是)。 */
    fun resolvedOptions(f: FormField): List<String> {
        val dyn = f.dynamicCat?.let { dynamicOptions[it] }
        return if (!dyn.isNullOrEmpty()) dyn + f.options.orEmpty().filter { it in STATIC_KEEP }
        else f.options.orEmpty()
    }

    companion object {
        private val STATIC_KEEP = setOf("自定义", "都不是", "其它", "其他")
    }
}
