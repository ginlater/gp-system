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
 *  - 拉后端版本 [ConsultantRepository.appVersionV2]：本机 versionCode < minVersionCode → 标记【强制升级】。
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
        // 进设置自动查一次 v2 独立版本接口（/api/app/v2/version，与 v1 的 /api/app/version 分开）。
        if (!versionChecked) { versionChecked = true; checkVersion() }
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

    /** 查 v2 独立版本接口并比对：installed < min → 强制；< latest → 可选更新。fail-open：失败一律不挡。 */
    fun checkVersion() {
        _state.update { it.copy(versionLoading = true, versionError = null) }
        viewModelScope.launch {
            when (val r = repo.appVersionV2()) {
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
                            versionCheckDone = true,
                        )
                    }
                }
                is ApiResult.Failure -> _state.update {
                    // fail-open：拿不到后端版本就当作"无需升级"，不弹强升卡
                    it.copy(versionLoading = false, versionError = r.message, mustUpgrade = false, updateAvailable = false, versionCheckDone = true)
                }
            }
        }
    }

    /** 上传运行诊断：把笔的运行日志 + 设备/版本/笔状态发给工程师远程排查。 */
    fun uploadDiag() {
        if (_state.value.diagUploading) return
        _state.update { it.copy(diagUploading = true, diagResult = null) }
        viewModelScope.launch {
            val pc = com.airec.bledemo.soni.SoniPenController.instance()
            val meta = pc?.diagMetaJson() ?: "{}"
            when (val r = repo.uploadDiag(meta, pc?.diagPenlogFile(), pc?.diagLastResultFile())) {
                is ApiResult.Success -> _state.update { it.copy(diagUploading = false, diagResult = "已上传，工程师可远程查看 ✅") }
                is ApiResult.Failure -> _state.update { it.copy(diagUploading = false, diagResult = r.message ?: "上传失败，请重试") }
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

    /**
     * ★2026-07-17 合规 —— 注销账号。
     *
     * 小米驳回要求「应用内的账号注销入口」,苹果 5.1.1(v) 同样强制:凡支持登录的
     * App 必须能在 App 内自助注销,不能只留「联系客服」。
     *
     * 服务端删的是 users 行(登录凭证 + 手机号/姓名/工号);录音与报告归门店所有、
     * 不跟着删——这个边界写在后端 api_delete_my_account 的注释里,别改错。
     *
     * 成功后必须走一遍 auth.logout() 清本地会话与各系统缓存,否则 Cookie 还在、
     * 下次冷启动会拿着一个已删账号的会话去打接口。
     */
    fun deleteAccount(password: String, onDone: () -> Unit) {
        if (_state.value.deletingAccount) return
        _state.update { it.copy(deletingAccount = true, deleteAccountError = null) }
        viewModelScope.launch {
            when (val r = repo.deleteMyAccount(password)) {
                is ApiResult.Success -> {
                    auth.logout()   // 清 Cookie/凭证/teach 等各系统缓存
                    _state.update { it.copy(deletingAccount = false) }
                    onDone()
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(deletingAccount = false, deleteAccountError = r.message) }
                }
            }
        }
    }

    fun clearDeleteAccountError() {
        _state.update { it.copy(deleteAccountError = null) }
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
    val versionCheckDone: Boolean = false,
    val mustUpgrade: Boolean = false,
    val updateAvailable: Boolean = false,
    val loggingOut: Boolean = false,
    val diagUploading: Boolean = false,
    val diagResult: String? = null,
    // ★2026-07-17 合规 —— 账号自助注销(小米驳回项 + 苹果 5.1.1(v) 强制)
    val deletingAccount: Boolean = false,
    val deleteAccountError: String? = null,
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

    /** 升级地址：优先下载页（带安装引导、对微信更友好），转成绝对 URL 供浏览器打开。 */
    val updateUrl: String?
        get() {
            val path = appVersion?.pageUrl?.takeIf { it.isNotBlank() }
                ?: appVersion?.apkUrl?.takeIf { it.isNotBlank() }
                ?: return null
            return if (path.startsWith("http")) path
            else com.airec.bledemo.data.net.NetworkModule.BASE_URL.trimEnd('/') + path
        }
}
