package com.airec.bledemo.data.api

import com.airec.bledemo.data.model.TeachCheckinResp
import com.airec.bledemo.data.model.TeachHeartbeatReq
import com.airec.bledemo.data.model.TeachHeartbeatResp
import com.airec.bledemo.data.model.TeachLoginReq
import com.airec.bledemo.data.model.TeachLoginResp
import com.airec.bledemo.data.model.TeachQuizResp
import com.airec.bledemo.data.model.TeachQuizSubmitReq
import com.airec.bledemo.data.model.TeachStats
import com.airec.bledemo.data.model.TeachSubmitResp
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * teach 网课服务端点（base = https://teach.beautyshining.com/，见 TeachModule）。
 *
 * 与工牌不同：鉴权不是 Cookie 而是 Bearer token（/teach/login 换取，Header 显式传）。
 * token 的获取/续期/401 重登统一收在 TeachRepository，Screen/VM 不直接碰本接口。
 */
interface TeachApi {

    /** 登录（JSON 体）。成功 {token,username}；错 401 {error}；停用 403 {error}。 */
    @POST("teach/login")
    suspend fun login(@Body req: TeachLoginReq): Response<TeachLoginResp>

    /** 课程列表 + 进度 + 打卡 + 学习时长，一次拉全。 */
    @GET("teach/me/stats")
    suspend fun stats(@Header("Authorization") bearer: String): Response<TeachStats>

    /** 每日打卡（幂等，无请求体）。 */
    @POST("teach/checkin")
    suspend fun checkin(@Header("Authorization") bearer: String): Response<TeachCheckinResp>

    /** 学习时长心跳（读课件停留期间每 30s 发一次；服务端 25s 节流）。 */
    @POST("teach/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") bearer: String,
        @Body req: TeachHeartbeatReq,
    ): Response<TeachHeartbeatResp>

    /** 取一套测验题（答案已剥掉）。quizIndex ∈ 1/2/3。 */
    @GET("teach/quiz/{chapter}/{quizIndex}")
    suspend fun quiz(
        @Header("Authorization") bearer: String,
        @Path("chapter") chapter: String,
        @Path("quizIndex") quizIndex: Int,
    ): Response<TeachQuizResp>

    /** 提交测验。 */
    @POST("teach/quiz/submit")
    suspend fun submitQuiz(
        @Header("Authorization") bearer: String,
        @Body req: TeachQuizSubmitReq,
    ): Response<TeachSubmitResp>

    /** 改密(统一密码同步用)。体 {old_password,new_password}。 */
    @POST("teach/change-password")
    suspend fun changePassword(
        @Header("Authorization") bearer: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): Response<okhttp3.ResponseBody>
}
