package com.airec.bledemo.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.model.Reminder
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 提醒屏（SPEC §4.8 / warm_2 #reminders）。
 *
 * 拉 [ConsultantRepository.reminders]，按后端 `scope` 拆成两组：
 *  - personal：本人未处理的「未绑定 / 未查看」提醒；
 *  - escalation：本店待跟进升级项（仅店长可见），可点「已跟进」→ [ConsultantRepository.handleReminder]。
 *
 * 「已跟进」乐观更新：先把该条标 handled，再发请求；失败回滚并冒一句错误。
 */
class RemindersViewModel(
    private val repo: ConsultantRepository = ConsultantRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(RemindersUiState())
    val state: StateFlow<RemindersUiState> = _state.asStateFlow()

    // F8：mark_read 不再纯 fire-and-forget——成功后本次会话不重发（页面停留期 2 分钟轮询
    // 不再每次都 POST），失败则下次 load 自动补发（弱网不丢已读）。
    private var markReadDone = false

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = repo.reminders()) {
                is ApiResult.Success -> {
                    // E7 方案B:真的打开了提醒页 → 显式上报已读(服务端已不再"拉取即已读")
                    if (!markReadDone) {
                        launch { markReadDone = repo.markRemindersRead() is ApiResult.Success }
                    }
                    val items = r.data.items ?: emptyList()
                    val personal = items.filter { it.scope != "escalation" }
                    val escalation = items.filter { it.scope == "escalation" }
                    _state.update {
                        it.copy(
                            loading = false,
                            error = null,
                            personal = personal,
                            escalation = escalation,
                            showEscalationGroup = escalation.isNotEmpty(),
                        )
                    }
                    // 列表拉到最新即同步首页铃铛红点（非顾问端 escalation 恒空，所以 personal+escalation 两端都对）。
                    ReminderBadge.set(personal.size + escalation.size)
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(loading = false, error = r.message) }
                }
            }
        }
    }

    /** 店长「已跟进」某条升级项：乐观置为 handled，失败回滚。 */
    fun handle(reminderId: Long) {
        // 已在处理中则忽略重复点击
        if (_state.value.handling.contains(reminderId)) return
        _state.update { it.copy(handling = it.handling + reminderId, error = null) }
        viewModelScope.launch {
            when (val r = repo.handleReminder(reminderId)) {
                is ApiResult.Success -> {
                    _state.update { s ->
                        s.copy(
                            handling = s.handling - reminderId,
                            handledIds = s.handledIds + reminderId,
                        )
                    }
                    // 已跟进的升级项不再计入红点（个人项 + 未跟进升级项）。
                    val s = _state.value
                    ReminderBadge.set(s.personal.size + s.escalation.count { it.id !in s.handledIds })
                }
                is ApiResult.Failure -> {
                    _state.update { s ->
                        s.copy(handling = s.handling - reminderId, error = r.message)
                    }
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}

/**
 * 提醒屏 UI 状态。
 *
 * @param personal 个人提醒（未绑定 / 未查看…）
 * @param escalation 本店待跟进升级项（仅店长有数据）
 * @param showEscalationGroup 是否展示升级项分组（有数据才显示）
 * @param handledIds 本次已点「已跟进」的升级项 id（前端立即变 已跟进 胶囊）
 * @param handling 正在请求「已跟进」的 id（按钮置 loading / 防重复）
 */
data class RemindersUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val personal: List<Reminder> = emptyList(),
    val escalation: List<Reminder> = emptyList(),
    val showEscalationGroup: Boolean = false,
    val handledIds: Set<Long> = emptySet(),
    val handling: Set<Long> = emptySet(),
) {
    val isEmpty: Boolean
        get() = !loading && error == null && personal.isEmpty() && escalation.isEmpty()
}
