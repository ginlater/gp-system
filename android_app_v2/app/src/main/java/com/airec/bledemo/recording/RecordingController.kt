package com.airec.bledemo.recording

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 「美丽陪伴」引擎接线层 —— 干净的 Kotlin 接口 + 状态模型。
 *
 * 这一层把现有 Java 引擎（[PhoneMicService] / [com.airec.bledemo.PenController] / [RecordingBus]）
 * 暴露给上层 Compose UI / ViewModel，**全部用「陪伴」语义命名**（守产品红线：顾客可见处零「录音」）。
 *
 * 设计原则（复用而非重写）：
 *  - 不改动任何 Java 引擎内部逻辑（看门狗 / 断线宽限 / 补传重试 / opus 直传 等踩坑稳定件）。
 *  - 引擎以 [RecordingBus] 单向上报状态字符串（idle/recording/paused/uploading/error/starting），
 *    本控制器订阅它、翻译成 [RecordingState] 经 [StateFlow] 暴露给 UI。
 *  - 对应 WebAppBridge.Host 的每个能力都有一个「陪伴」语义方法，UI 只依赖本接口，不直接碰 Java。
 *
 * 镜像语义：以陪伴笔真实状态为准（笔上按钮 / 声控自发开录 也会经引擎回调点亮「陪伴进行中」），
 * 所以 [RecordingState] 是被引擎驱动的，UI 不应假设「点了 startCompanion 就一定立刻进入 Recording」。
 */
interface RecordingController {

    // ============ 状态（StateFlow，UI collectAsState 即可） ============

    /** 当前陪伴状态机（Idle/Recording/Paused/Uploading；含错误文案、时长、最近一段 recordingId）。 */
    val state: StateFlow<RecordingState>

    /** 陪伴笔是否「真在线」（已验证回包、非裸 GATT 指针）。用于决定默认来源 / 是否提示连接。 */
    val penConnected: StateFlow<Boolean>

    /** 后台待传 / 在传段数（异步上传指示，不阻塞当前陪伴）。 */
    val pendingCount: StateFlow<Int>

    /** 累计「保存失败被放弃」段数（本批；开新一段时引擎清零）。 */
    val failedCount: StateFlow<Int>

    /** 后台下载进度（0-100），用于「保存中 N%」。无下载时为 0。 */
    val progressPercent: StateFlow<Int>

    /** 陪伴笔电量（cmd=6 上报；null=未知/未连接）。首页连接后显示。 */
    val penBattery: StateFlow<PenBattery?>

    /**
     * 一次性面向用户的陪伴笔提示（连接成功 / 错误 / 归属拒绝 / 「开机自动录制」被关 等）。
     * 走独立事件流而非去重的 [state]——否则相同文案被 StateFlow 去重、或被随后的 Idle 覆盖而被悄悄吞掉
     * （对齐旧宿主每次 Toast.makeText 都弹）。UI collect → Toast。
     */
    val penEvents: SharedFlow<String>

    /**
     * 后台机身片段落地（一段补传成功 / 建占位）信号 → UI 刷新「待整理」列表与首页待整理计数
     * （对齐旧宿主 onPenUploaded/onPenPlaceholderCreated → loadPending）。
     */
    val penListChanged: SharedFlow<Unit>

    /**
     * 一段录音录完、已拿到可绑定的 recordingId（占位记录，**不必等上传完成**）→ UI 弹「现在绑定顾客」对话框，
     * 避免顾问录完忘记绑定，也让顾问录完立刻绑下一位、不必等上传。
     * 手机麦：停录即建占位；陪伴笔：建占位片段时。携带 recordingId 供直达绑定页。
     */
    val bindPrompt: SharedFlow<Long>

    /**
     * 连接成功 / 查到笔录音状态时回调，让上层（[RecordingModule]）重注最新上传上下文（会话 Cookie）。
     * 兜底：Cookie 轮换后笔在非首页自发录音也能用新 Cookie 上传（对齐旧宿主每次 connect/record-status 都 setUploadContext）。
     */
    var onNeedContextRefresh: (() -> Unit)?

    /**
     * 等价 [state] 的便捷只读流别名（语义化命名，供需要直接 observe 状态的调用方使用）。
     * 命名对齐任务要求里的 observeState。
     */
    fun observeState(): StateFlow<RecordingState> = state

    // ============ 能力（对应 WebAppBridge.Host，全部「陪伴」语义命名） ============

    /** 当前可用陪伴来源（手机麦克风 / 陪伴笔）。对应 Host.bridgeGetSources。 */
    fun getSources(): List<CompanionSource>

    /**
     * 开启陪伴。对应 Host.bridgeStartRecording(source)。
     * - [CompanionSource.Phone]：起前台 [PhoneMicService] 用手机麦克风。
     * - [CompanionSource.Pen]：经 PenController 唤醒陪伴笔（笔未连接会触发连接提示）。
     */
    fun startCompanion(source: CompanionSource)

    /** 结束陪伴。对应 Host.bridgeStopRecording。 */
    fun stopCompanion()

    /** 暂停（仅陪伴笔）。对应 Host.bridgePauseRecording。 */
    fun pause()

    /** 继续（仅陪伴笔）。对应 Host.bridgeResumeRecording。 */
    fun resume()

    /** 陪伴笔当前是否已连接（真在线判定）。对应 Host.bridgeIsPenConnected。 */
    fun isPenConnected(): Boolean

    /**
     * 读取上次选定的陪伴来源（持久化于 SharedPreferences）。无记录时返回 null（调用方自行兜底默认）。
     * 由 ViewModel 初始化默认来源用（避免「按笔是否在线重置」抹掉用户选择）。
     */
    fun lastSource(): CompanionSource?

    /** 持久化用户选定的陪伴来源（pickSource 选定后调）。 */
    fun saveSource(source: CompanionSource)

    /** 打开陪伴笔扫描 / 连接页。对应 Host.bridgeConnectPen。 */
    fun connectPen()

    /** 打开陪伴笔管理页（调试用）。对应 Host.bridgeOpenPenManager。 */
    fun openPenManager()

    /**
     * 后台待传 / 失败段数快照（一次性，页面重载后恢复 badge 用；持续观察用 [pendingCount]/[failedCount]）。
     * 对应 Host.bridgePendingInfo（"count,failed"）。
     */
    fun pendingInfo(): PendingInfo

    /**
     * 从陪伴笔同步：拉机身文件列表（脱机本地保存、尚未导入的片段），异步经 [onPenFiles] 回调。
     * 对应 Host.bridgeSyncPenFiles。
     */
    /** ★2.1.9:回调参数 null = 拉取失败/笔未连接；空列表 = 笔里确实没文件。 */
    fun syncPenFiles(onPenFiles: (List<PenFile>?) -> Unit)

    /** ★2.1.9:把【服务器已确认收到/已删除】的机身文件从笔里删掉（同步时顺手清理，开销极小）。 */
    fun deleteSyncedPenFiles(names: List<String>)

    /** ★2.2.0:顾问删了"同步中"的段 → 取消后台还在搬运/待传的那个任务（不再传、不再复活）。 */
    fun cancelPenTaskByPlaceholder(placeholderId: Long)

    /** ★2.1.9:机身清单已读到几条（读取中的进度；用于超时提示"已读到 N 段"而不是误判失败）。 */
    fun penFileListProgress(): Int

    /** 上传选中的机身文件（从同步列表勾选导入到「待整理」）。对应 Host.bridgeUploadPenFiles。 */
    fun uploadPenFiles(fileNames: List<String>)

    /** 立刻重推待补传队列（「重试」入口）。对应 Host.bridgeRetryPenUploads。 */
    fun retryPenUploads()

    /**
     * 设置 / 刷新上传上下文（登录 Cookie + 上传 URL）。
     * 引擎里手机麦克风走 Intent extra、陪伴笔自发录音也要用 → 进入接诊 / 连接 / 开始前刷新。
     */
    fun setUploadContext(cookie: String, uploadUrl: String)

    /** 网络恢复 → 清退避、立刻重推待传。引擎已实现，UI/网络监听层在网络回来时调它。 */
    fun onNetworkAvailable()

    /** App 回前台 / 进接诊页：静默自动连回上次那支陪伴笔（不弹扫描页）。savedMac 为空则忽略。 */
    fun autoConnectPen(savedMac: String?)

    /** 让引擎回调指向本控制器（进入接诊页 / 开始用笔陪伴前调用，幂等）。 */
    fun activate()

    /** 注册 / 注销状态监听（Activity onResume 注册、onPause 注销，避免泄漏）。 */
    fun attach()
    fun detach()
}

// ============================================================================
// 状态模型
// ============================================================================

/** 陪伴来源。 */
enum class CompanionSource(val wireValue: String) {
    /** 手机麦克风。 */
    Phone("phone"),

    /** 蓝牙陪伴笔。 */
    Pen("pen");

    companion object {
        /** 引擎 / Bridge 用的来源串 → 枚举。未知一律按手机。 */
        fun fromWire(value: String?): CompanionSource =
            entries.firstOrNull { it.wireValue == value } ?: Phone
    }
}

/**
 * 陪伴状态机。映射自 [RecordingBus] 的状态字符串：
 *  - idle                  → [Idle]
 *  - starting / recording  → [Recording]（starting 即「正在唤醒陪伴笔…」，仍属进行中态，带 starting 标志）
 *  - paused                → [Paused]
 *  - uploading             → [Uploading]
 *  - error                 → [Idle] + errorMessage（错误不是独立屏，落回 Idle 并携带文案给 UI 提示）
 */
sealed interface RecordingState {

    /** 进行中态共用的时长（秒）。 */
    val durationSec: Int get() = 0

    /** 空闲。可携带上一段的错误文案（来自引擎的 error 上报）/ 提示文案。 */
    data class Idle(
        /** 引擎 error 上报的文案（如「录音笔没开机/没响应」）；正常空闲为 null。 */
        val errorMessage: String? = null,
        /** 上一段陪伴成功上传后回传的 recordingId（>0 时可用于跳绑定），否则 -1。 */
        val lastRecordingId: Long = -1L,
        /** 成功 / 提示文案（如「已上传，请选择顾客」）。 */
        val message: String? = null,
    ) : RecordingState

    /**
     * 陪伴进行中。
     * @param starting true = 已发开始命令、等陪伴笔确认真开录（「正在唤醒陪伴笔…」）；UI 可显示加载态。
     */
    data class Recording(
        override val durationSec: Int = 0,
        val source: CompanionSource = CompanionSource.Phone,
        val starting: Boolean = false,
        /**
         * true = 已拿到陪伴笔的真实录音时长（durationSec 可信）。
         * false = 重连到「已在录」的笔、还没同步到真实时长（durationSec 是 0 占位）——
         *         UI 应在计时位置显示「正在同步小伙伴时间…」，而非从 0 往上跳的假计时。
         */
        val timeSynced: Boolean = true,
        /** 引擎附带文案（如「🔄 信号断开，正在自动重连，录音继续中…」）。 */
        val message: String? = null,
    ) : RecordingState

    /** 已暂停（仅陪伴笔）。 */
    data class Paused(
        override val durationSec: Int = 0,
        val source: CompanionSource = CompanionSource.Pen,
    ) : RecordingState

    /** 上传中（手机麦克风结束后的上传阶段；陪伴笔走后台队列，一般直接回 Idle）。 */
    data class Uploading(
        override val durationSec: Int = 0,
        val message: String? = null,
    ) : RecordingState
}

/** 后台待传 / 失败段数快照（对应 Host.pendingInfo 的 "count,failed"）。 */
data class PendingInfo(
    val count: Int,
    val failed: Int,
) {
    companion object {
        /** 从引擎 "count,failed" 串解析；解析不出返回全 0。 */
        fun parse(raw: String?): PendingInfo {
            if (raw.isNullOrBlank()) return PendingInfo(0, 0)
            val parts = raw.split(',')
            val c = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            val f = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            return PendingInfo(c, f)
        }
    }

    /** 回到引擎期望的 "count,failed" 串。 */
    fun toWire(): String = "$count,$failed"
}

/**
 * 陪伴笔机身文件（「从陪伴笔同步」勾选导入用）。
 * 字段对应 PenController.deliverPenFileList 产出的 JSON：name/ra/size/dur/uploaded。
 */
data class PenFile(
    /** 机身文件名（上传时按它去重 / 定位）。 */
    val name: String,
    /** 录音开始的可读墙上时间（"yyyy-MM-dd HH:mm:ss"），解析不到为空串。 */
    val recordedAt: String,
    /** 文件大小（字节）。 */
    val sizeBytes: Long,
    /** 时长（秒）。 */
    val durationSec: Int,
    /** 是否已上传过（App 本地记录的已传集合）。 */
    val uploaded: Boolean,
)

/**
 * 陪伴笔电量（声云笔 cmd=6 上报）。
 * @param percent 0–100 电量百分比（充电时仅供参考）。
 * @param charging 是否充电中（声云 cbc 百位=充电标记）。
 */
data class PenBattery(
    val percent: Int,
    val charging: Boolean,
)
