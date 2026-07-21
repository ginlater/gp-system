package com.airec.bledemo.data.model

import com.squareup.moshi.Json

/* ===================================================================
 * teach 网课（多系统整合 P1）领域模型。
 *
 * 服务端 = huifang-prod /opt/teach-server FastAPI（teach.beautyshining.com），
 * 字段名 1:1 对齐 app.py + teach_progress_store.py + quiz_helpers.py 真实返回
 * （2026-07-15 已上服务器核对，别凭感觉改 key）。
 *
 * 鉴权：POST /teach/login {username,password}(JSON) → {token}，之后走
 * Authorization: Bearer。单设备策略——每次登录踢掉旧 session，被踢的请求回 401，
 * TeachRepository 收到 401 会静默重登一次再重试。
 * =================================================================== */

// ─────────────────────────── 请求体 ───────────────────────────

/** POST /teach/login 请求体（JSON，非表单）。 */
data class TeachLoginReq(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String,
)

/** POST /teach/heartbeat 请求体。chapter 传当前正在读的章 key（可空串）。 */
data class TeachHeartbeatReq(
    @Json(name = "chapter") val chapter: String = "",
)

/** POST /teach/quiz/submit 请求体。answers 与题目一一对应：单选传 "A"~"D"，情境题传作答文本。 */
data class TeachQuizSubmitReq(
    @Json(name = "chapter") val chapter: String,
    @Json(name = "quiz_index") val quizIndex: Int,
    @Json(name = "answers") val answers: List<String>,
)

// ─────────────────────────── 响应 ───────────────────────────

/** POST /teach/login 响应。成功 {token,username}；401/403 {error}。 */
data class TeachLoginResp(
    @Json(name = "token") val token: String? = null,
    @Json(name = "username") val username: String? = null,
    @Json(name = "error") val error: String? = null,
)

/** GET /teach/me/stats 响应（课程列表 + 打卡 + 学习时长，一次拉全）。 */
data class TeachStats(
    @Json(name = "ok") val ok: Boolean? = null,
    @Json(name = "checkin") val checkin: TeachCheckin? = null,
    @Json(name = "study") val study: TeachStudy? = null,
    @Json(name = "progress") val progress: TeachProgress? = null,
    @Json(name = "error") val error: String? = null,
)

/** 打卡统计。last_30_days = 最近打卡日期（"YYYY-MM-DD"）。 */
data class TeachCheckin(
    @Json(name = "today_signed") val todaySigned: Boolean? = null,
    @Json(name = "streak") val streak: Int? = null,
    @Json(name = "total_days") val totalDays: Int? = null,
    @Json(name = "last_30_days") val last30Days: List<String>? = null,
)

/** 学习时长统计（心跳累计）。 */
data class TeachStudy(
    @Json(name = "today_seconds") val todaySeconds: Int? = null,
    @Json(name = "today_minutes") val todayMinutes: Double? = null,
    @Json(name = "total_seconds") val totalSeconds: Int? = null,
)

/** 课程总进度。 */
data class TeachProgress(
    @Json(name = "current_chapter") val currentChapter: String? = null,
    @Json(name = "completed_count") val completedCount: Int? = null,
    @Json(name = "total_count") val totalCount: Int? = null,
    @Json(name = "chapters") val chapters: List<TeachChapter>? = null,
)

/**
 * 单章进度。
 * - allowed=false：本账号没被分配该章 → 列表置灰「未开通」，不让进。
 * - unlocked=false：顺序未解锁（前一章 3 套测验各 ≥60 才解锁）→ 锁图标，不让进。
 * - completed：3 套测验全过。quizN_score 为历史最高分（没考过 = null）。
 */
data class TeachChapter(
    @Json(name = "key") val key: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "allowed") val allowed: Boolean? = null,
    @Json(name = "unlocked") val unlocked: Boolean? = null,
    @Json(name = "read_done") val readDone: Boolean? = null,
    @Json(name = "quiz1_score") val quiz1Score: Int? = null,
    @Json(name = "quiz2_score") val quiz2Score: Int? = null,
    @Json(name = "quiz3_score") val quiz3Score: Int? = null,
    @Json(name = "completed") val completed: Boolean? = null,
) {
    /** 按序取三套测验分数（index 1..3）。 */
    fun quizScore(index: Int): Int? = when (index) {
        1 -> quiz1Score
        2 -> quiz2Score
        3 -> quiz3Score
        else -> null
    }
}

/** POST /teach/checkin 响应（幂等，重复打卡也回 ok）。 */
data class TeachCheckinResp(
    @Json(name = "ok") val ok: Boolean? = null,
    @Json(name = "today_signed") val todaySigned: Boolean? = null,
    @Json(name = "streak") val streak: Int? = null,
    @Json(name = "total_days") val totalDays: Int? = null,
    @Json(name = "error") val error: String? = null,
)

/** POST /teach/heartbeat 响应。服务端 25s 节流，<25s 的心跳回 throttled=true（不计时长）。 */
data class TeachHeartbeatResp(
    @Json(name = "ok") val ok: Boolean? = null,
    @Json(name = "throttled") val throttled: Boolean? = null,
    @Json(name = "today_seconds") val todaySeconds: Int? = null,
)

/** GET /teach/quiz/{chapter}/{quiz_index} 响应。答案/解析已被服务端剥掉。 */
data class TeachQuizResp(
    @Json(name = "ok") val ok: Boolean? = null,
    @Json(name = "chapter") val chapter: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "quiz_index") val quizIndex: Int? = null,
    @Json(name = "total") val total: Int? = null,
    @Json(name = "questions") val questions: List<TeachQuestion>? = null,
    @Json(name = "error") val error: String? = null,
)

/**
 * 单题。type: single_choice（options 是 {"A":文案,…}）| scenario（无 options，自由作答 150-250 字，
 * 服务端按关键词命中打分，每题满分 25；单选每题 15）。
 */
data class TeachQuestion(
    @Json(name = "type") val type: String? = null,
    @Json(name = "question") val question: String? = null,
    @Json(name = "options") val options: Map<String, String>? = null,
) {
    val isScenario: Boolean get() = type == "scenario"
}

/** POST /teach/quiz/submit 响应。passed = score ≥60；chapter_completed = 三套全过（本次达成）。 */
data class TeachSubmitResp(
    @Json(name = "ok") val ok: Boolean? = null,
    @Json(name = "score") val score: Int? = null,
    @Json(name = "passed") val passed: Boolean? = null,
    @Json(name = "details") val details: List<TeachQuizDetail>? = null,
    @Json(name = "chapter_completed") val chapterCompleted: Boolean? = null,
    @Json(name = "next_unlocked") val nextUnlocked: String? = null,
    @Json(name = "error") val error: String? = null,
)

/** 单题判分明细（提交后展示对错/正确答案/解析）。 */
data class TeachQuizDetail(
    @Json(name = "type") val type: String? = null,
    @Json(name = "correct") val correct: Boolean? = null,
    @Json(name = "user") val user: Any? = null,
    @Json(name = "answer") val answer: String? = null,
    @Json(name = "explanation") val explanation: String? = null,
    @Json(name = "points") val points: Int? = null,
    @Json(name = "hits") val hits: Any? = null,
)
