package com.airec.bledemo.data.model

import com.squareup.moshi.Json

/* ===================================================================
 * 分析任务（11 个，平铺 T1–T11）。
 * 来自 GET /api/session/<sid>/tasks → tasks[]（webapp.py TASK_REGISTRY）。
 * status: done | running | failed | pending | missing。
 * 注意：UI 不得出现"调用 1/2/3"（call 字段仅内部排序/分桶用，不展示）。
 * =================================================================== */
data class Task(
    @Json(name = "task_id") val id: String,                 // T1 .. T11
    @Json(name = "name") val name: String? = null,          // 顾客真实画像 / 质检评分 ...
    @Json(name = "call") val call: Int? = null,             // 内部分组(1/2/3)，UI 禁止展示
    @Json(name = "status") val status: String? = null,      // done | running | failed | pending | missing
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "error") val error: String? = null,
) {
    val isDone: Boolean get() = status == "done"
    val isRunning: Boolean get() = status == "running"
    val isFailed: Boolean get() = status == "failed"
}

/** GET /api/session/<sid>/tasks 响应。 */
data class TasksResponse(
    @Json(name = "tasks") val tasks: List<Task>? = null,
)
