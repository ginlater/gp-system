package com.airec.bledemo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airec.bledemo.data.auth.AuthManager
import com.airec.bledemo.data.model.AppVersion
import com.airec.bledemo.data.model.Me
import com.airec.bledemo.data.repo.ApiResult
import com.airec.bledemo.data.repo.ConsultantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置屏 ViewModel（SPEC §4.11）。
 *
 * 职责：
 *  - 持有本机已安装版本（versionName / versionCode）——由 Screen 从 PackageManager 读出后经 [setInstalledVersion] 注入
 *    （AGP buildConfig 未开启，BuildConfig.VERSION_NAME 不可用；强制升级判定本就要比对"已安装"版本）。
 *  - 拉陪伴师信息 [ConsultantRepository.me]（advisor_name / role / 门店等）。
 *  - 拉后端版本 [ConsultantRepository.appVersion]：本机 versionCode < minVersionCode → 标记【强制升级】。
 *  - 退出登录 [AuthManager.logout]（先调 /logout 再清本地 Cookie，失败也清本地），完成后回调上层切回登录。
 *
 * 取实例：Composable 里 `viewModel()` 默认无参构造即可（repo/auth 取 NetworkModule 默认）。
 *
 * 产品红线：本屏顾客可见，文案一律「陪伴师 / 美丽陪伴」，绝不出现"录音"。
 */
class SettingsViewModel(
    private val repo: ConsultantRepository = ConsultantRepository(),
    private val auth: AuthManager = AuthManager(),
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var versionChecked = false
    private var meLoaded = false

    /**
     * 注入本机已安装版本（由 Screen 从 PackageManager 读出）。
     * 首次注入后顺带触发 me / version 加载（只触发一次，旋转屏不重复）。
     */
    fun setInstalledVersion(versionName: String, versionCode: Long) {
        _state.update { it.copy(installedVersionName = versionName, installedVersionCode = versionCode) }
        if (!meLoaded) { meLoaded = true; loadMe() }
        // v2 是独立版本系列、独立分发：绝不查 /api/app/version（那是 v1 WebView 杰理包的端点，
        // 返回的是 v1 的 2.1.14/code25）。否则 v2(code9) 会被判「需强制升级到 v1 的 APK」、把两个包搅在一起。
        // v2 只显示本机自己的版本，与 v1 不互通；v2 的新版靠 /download/v2 单独分发。
        versionChecked = true   // 占位，确保不再触发任何 v1 版本检查
    }

    /** 拉陪伴师信息（顶部账号卡）。失败不阻断，留空展示即可。 */
    fun loadMe() {
        _state.update { it.copy(meLoading = true, meError = null) }
        viewModelScope.launch {
            when (val r = repo.me()) {
                is ApiResult.Success -> _state.update { it.copy(me = r.data, meLoading = false, meError = null) }
                is ApiResult.Failure -> _state.update { it.copy(meLoading = false, meError = r.message) }
            }
        }
    }

    /** 拉后端版本并比对：installed < min → 需要强制升级。fail-open：失败一律不挡。 */
    fun checkVersion() {
        _state.update { it.copy(versionLoading = true, versionError = null) }
        viewModelScope.launch {
            when (val r = repo.appVersion()) {
                is ApiResult.Success -> {
                    val v = r.data
                    val installed = _state.value.installedVersionCode
                    val min = v.minVersionCode ?: 0
                    val latest = v.latestVersionCode ?: 0
                    // installed<=0 取不到 → fail-open，不判强升
                    val mustUpgrade = installed in 1 until min
                    val hasOptional = !mustUpgrade && installed in 1 until latest
                    _state.update {
                        it.copy(
                            appVersion = v,
                            mustUpgrade = mustUpgrade,
                            updateAvailable = hasOptional,
                            versionLoading = false,
                            versionError = null,
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    // fail-open：拿不到后端版本就当作"无需升级"，不弹强升卡
                    it.copy(versionLoading = false, versionError = r.message, mustUpgrade = false, updateAvailable = false)
                }
            }
        }
    }

    /** 退出登录。完成后触发 [onDone]（上层把导航起点切回登录）。 */
    fun logout(onDone: () -> Unit) {
        if (_state.value.loggingOut) return
        _state.update { it.copy(loggingOut = true) }
        viewModelScope.launch {
            auth.logout()
            _state.update { it.copy(loggingOut = false) }
            onDone()
        }
    }
}

/**
 * 设置屏 UI 状态。
 *
 * @param installedVersionName 本机已安装的 versionName（如 "2.0.8"，开发包带 -v2dev 后缀）
 * @param installedVersionCode 本机已安装的 versionCode；<=0 表示取不到（fail-open）
 * @param me 陪伴师信息
 * @param appVersion 后端版本信息
 * @param mustUpgrade installed < minVersionCode → 强制升级（红色不可绕过卡）
 * @param updateAvailable 非强制但有更新（installed < latest）→ 柔和"可更新"提示
 */
data class SettingsUiState(
    val installedVersionName: String = "—",
    val installedVersionCode: Long = -1L,
    val me: Me? = null,
    val meLoading: Boolean = false,
    val meError: String? = null,
    val appVersion: AppVersion? = null,
    val versionLoading: Boolean = false,
    val versionError: String? = null,
    val mustUpgrade: Boolean = false,
    val updateAvailable: Boolean = false,
    val loggingOut: Boolean = false,
) {
    /** 顶部展示用陪伴师名；缺省退回用户名。 */
    val displayName: String
        get() = me?.advisorName?.takeIf { it.isNotBlank() }
            ?: me?.username?.takeIf { it.isNotBlank() }
            ?: "陪伴师"

    /** 角色中文。 */
    val roleLabel: String
        get() = when (me?.role) {
            "store_manager" -> "店长"
            "consultant" -> "陪伴师"
            else -> "陪伴师"
        }

    /** 顶部头像首字（陪伴师名首字）。 */
    val avatarChar: String
        get() = displayName.trim().take(1).ifBlank { "陪" }

    /** 后端最新版本名（用于升级卡文案）。 */
    val latestVersionName: String?
        get() = appVersion?.latestVersionName?.takeIf { it.isNotBlank() }

    /** 升级说明文案（后端 updateNote）。 */
    val updateNote: String?
        get() = appVersion?.updateNote?.takeIf { it.isNotBlank() }

    /** 升级下载地址（优先 apkUrl，退回 pageUrl）。 */
    val updateUrl: String?
        get() = appVersion?.apkUrl?.takeIf { it.isNotBlank() }
            ?: appVersion?.pageUrl?.takeIf { it.isNotBlank() }
}
