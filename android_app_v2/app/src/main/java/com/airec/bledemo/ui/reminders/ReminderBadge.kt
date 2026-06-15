package com.airec.bledemo.ui.reminders

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全 App 共享的「提醒未读数」唯一真相。
 *
 * 之前首页铃铛红点(HomeViewModel)和提醒列表(RemindersViewModel)各拉各的 /api/consultant/reminders、
 * 各存各的计数 → 列表里处理完/清空了，首页红点还停在旧值(用户反馈：列表空了红点还显示 6)。
 * 现在两边都读/写这一个 StateFlow：任一处拉到最新提醒就 [set]，红点与列表恒一致。
 */
object ReminderBadge {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    /** 更新未读数（负数夹到 0）。任何拉到最新 reminders 的地方都应调一次。 */
    fun set(value: Int) {
        _count.value = value.coerceAtLeast(0)
    }
}
