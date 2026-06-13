package com.airec.bledemo.nav

/**
 * 「美丽陪伴」全部导航目的地路由表（单一事实源）。
 *
 * 屏幕清单对齐 SPEC §4 / warm_2.html。其中底部导航的 4 个 tab（[Home]/[Reception]/
 * [Pending]/[Archive]）外加中间「开启陪伴」FAB（指向 [Home] + 触发开启），其余为栈内
 * push 的次级页（绑定顾客 / 会话预览 / 报告 / 提醒 / 登录 / 设置）。
 *
 * 设计原则：
 *  - 路由字符串集中在此，避免各处硬编码 magic string；下游 screen agent 只引用本对象常量。
 *  - 带参数的路由（如 [report]/[bindCustomer]/[sessionPreview]）提供 `routePattern`（含
 *    `{arg}` 占位，供 NavHost 注册）与 `build(...)`（拼实际路径，供导航调用）两套，签名清晰。
 *
 * 注：warm_2 把 needs-confirm（说话人确认）、customer 详情等也算屏；本壳阶段按任务要求只建
 * 列出的 10 个目的地，其余 screen agent 需要时在自己包内追加路由常量即可（不与本表冲突）。
 */
object Routes {

    // ============ 主壳（4 tab 同居一处，HorizontalPager 左右滑切） ============

    /**
     * 主壳路由：承载「4 tab 横向 Pager + 底栏」的单一 NavHost 目的地。
     * 登录后落这里；左右滑/点底栏在 Pager 内部切页（不再各 tab 独立入栈）。
     * 4 个 tab 不再是独立路由，故 [Home]/[Reception]/[Pending]/[Archive] 仅作 Pager 页序的语义标识保留。
     */
    const val Main = "main"

    // ============ 底部导航 4 tab（Pager 页，非独立目的地；常量保留作页序标识） ============

    /** 陪伴首页：大「开启陪伴」圆钮 + 来源切换 + 陪伴笔状态卡。 */
    const val Home = "home"

    /** 今日接诊：今日接诊列表。 */
    const val Reception = "reception"

    /** 待整理：未绑定陪伴片段列表。 */
    const val Pending = "pending"

    /** 档案：顾客「美丽档案」搜索/详情。 */
    const val Archive = "archive"

    // ============ 栈内次级页 ============

    /** 提醒。 */
    const val Reminders = "reminders"

    /** 登录（未鉴权时的起点）。 */
    const val Login = "login"

    /** 设置 / 版本检查 / 强制更新。 */
    const val Settings = "settings"

    // ============ 角色分流 + 管理台 ============

    /**
     * 角色路由门（startup / 登录成功后的统一落点）：拉 /api/me，按 role 一次性分流——
     * admin/super → [AdminHome]；consultant/store_manager → [Main]；失败/未登录 → [Login]。
     * 分流后把自身 pop 掉，不留在返回栈。
     */
    const val Gate = "gate"

    /** 管理台首页（admin/super）：分组模块卡 landing。 */
    const val AdminHome = "admin_home"

    /** 管理台 · 运营看板（WAVE 1 唯一落地的真实管理模块）。 */
    const val AdminOps = "admin_ops"

    // ---- 带参数的次级页 ----

    /**
     * 绑定顾客：把一段待整理片段（[recordingId]）绑定到顾客。
     * 入口来自待整理列表 / 陪伴结束提示。
     */
    object BindCustomer {
        const val ARG_RECORDING_ID = "recordingId"
        const val routePattern = "bindCustomer/{$ARG_RECORDING_ID}"
        fun build(recordingId: Long): String = "bindCustomer/$recordingId"
    }

    /**
     * 会话预览 + 开始分析：勾选片段→预览→确认并开始分析。
     * 携带会话标识 [sessionId]（绑定后由后端返回）。
     */
    object SessionPreview {
        const val ARG_SESSION_ID = "sessionId"
        const val routePattern = "sessionPreview/{$ARG_SESSION_ID}"
        fun build(sessionId: Long): String = "sessionPreview/$sessionId"
    }

    /**
     * 分析报告：11 个 PART 折叠卡 + 原始音频 + 任务重跑。
     * 携带会话标识 [sessionId]。
     */
    object Report {
        const val ARG_SESSION_ID = "sessionId"
        const val routePattern = "report/{$ARG_SESSION_ID}"
        fun build(sessionId: Long): String = "report/$sessionId"
    }
}
