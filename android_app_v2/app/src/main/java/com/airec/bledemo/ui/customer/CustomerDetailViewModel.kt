package com.airec.bledemo.ui.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.model.CustomerProfileResponse
import com.airec.bledemo.data.model.CustomerValueResponse
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「客户详情」状态机 —— 对齐 redesign 原型 #customer，1:1 复刻 web customer_profile.html 的两只读接口：
 *  - GET /api/admin/customer_profile（[ConsultantRepository.customerProfile]）→ 基本信息 / 累积标签 / 陪伴时间线
 *  - GET /api/admin/customer_value（[ConsultantRepository.customerValue]）→ 客户价值预测（管理员/店长生成，顾问端只读）
 *
 * 顾问端【只读】：本 App 不提供生成/刷新价值预测的入口；价值未生成时仅给「看客户价值预测」重新拉一次缓存。
 * 红线：UI 对外一律「陪伴 / 陪伴时间线」，绝不出现录音/录制。
 */

/** 「客户详情」UI 状态。profile/value 各自独立加载，互不阻塞。 */
data class CustomerDetailState(
    val profileLoading: Boolean = false,
    val profileError: String? = null,
    val profile: CustomerProfileResponse? = null,
    val valueLoading: Boolean = false,
    val valueError: String? = null,
    val value: CustomerValueResponse? = null,
) {
    /** 已生成价值预测正文（has_cache 且 content 非空）。 */
    val hasValueContent: Boolean
        get() = value?.content != null && value.error == null
}

class CustomerDetailViewModel(
    private val repo: ConsultantRepository = ConsultantRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(CustomerDetailState())
    val state: StateFlow<CustomerDetailState> = _state.asStateFlow()

    /** 当前加载的顾客 id（防 LaunchedEffect 重复加载同一人）。 */
    private var loadedId: Long? = null

    /** 由 LaunchedEffect(customerId) 触发：首次加载 profile + value。 */
    fun load(customerId: Long) {
        if (loadedId == customerId) return
        loadedId = customerId
        loadProfile(customerId)
        loadValue(customerId)
    }

    /** 拉顾客档案（基本信息 / 累积标签 / 陪伴时间线，range=all）。 */
    fun loadProfile(customerId: Long) {
        _state.update { it.copy(profileLoading = true, profileError = null) }
        viewModelScope.launch {
            when (val r = repo.customerProfile(customerId, range = "all")) {
                is ApiResult.Success -> {
                    val d = r.data
                    if (d.error != null) {
                        _state.update { it.copy(profileLoading = false, profileError = d.error) }
                    } else {
                        _state.update { it.copy(profileLoading = false, profileError = null, profile = d) }
                    }
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(profileLoading = false, profileError = r.message) }
            }
        }
    }

    /**
     * 拉客户价值预测（缓存）。has_cache=false → content 为 null（尚未生成），
     * 屏内给「看客户价值预测」按钮再调一次本方法（管理员可能已补生成）。
     */
    fun loadValue(customerId: Long) {
        _state.update { it.copy(valueLoading = true, valueError = null) }
        viewModelScope.launch {
            when (val r = repo.customerValue(customerId)) {
                is ApiResult.Success -> {
                    val d = r.data
                    if (d.error != null) {
                        _state.update { it.copy(valueLoading = false, valueError = d.error, value = d) }
                    } else {
                        _state.update { it.copy(valueLoading = false, valueError = null, value = d) }
                    }
                }
                is ApiResult.Failure ->
                    _state.update { it.copy(valueLoading = false, valueError = r.message) }
            }
        }
    }

    /** 「看客户价值预测」点击：重新拉缓存。 */
    fun refetchValue() {
        loadedId?.let { loadValue(it) }
    }
}
