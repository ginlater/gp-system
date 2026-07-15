package com.airec.bledemo.data.api

import com.airec.bledemo.data.model.FuEmployees
import com.airec.bledemo.data.model.FuLoginReq
import com.airec.bledemo.data.model.FuLoginResp
import com.airec.bledemo.data.model.FuQuota
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * followup-agent 端点(回访话术 www.aibeautyfulwomen.com / 高情商话术 43.136.130.133,
 * 同一套代码两处部署,base URL 由 FollowupModule 按系统实例化)。
 *
 * 生成接口 POST /generate 是 SSE 流式,不走 Retrofit,见 FollowupRepository.generateStream。
 */
interface FollowupApi {

    /** 登录。⚠️ 密码字段名是 key(见 FuLoginReq)。JWT 7 天,无单设备互踢。 */
    @POST("login/account")
    suspend fun login(@Body req: FuLoginReq): Response<FuLoginResp>

    /** 本月额度(模型组 → used/limit,limit=-1 无限)。 */
    @GET("quota")
    suspend fun quota(@Header("Authorization") bearer: String): Response<FuQuota>

    /** 生成后保存顾客+话术(网页端同款自动存档,历史在网页端也能看到)。体是自由 dict。 */
    @POST("customers")
    suspend fun saveCustomer(
        @Header("Authorization") bearer: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): Response<ResponseBody>

    /** 按公司动态下发的表单选项(类目 key → 选项数组;todayProject/痛点/护理项目等用)。 */
    @GET("project-options")
    suspend fun projectOptions(
        @Header("Authorization") bearer: String,
    ): Response<Map<String, @JvmSuppressWildcards List<String>>>

    /** 顾问名单(姓名+门店;顾问姓名/所属门店自动带出用)。 */
    @GET("employees/list")
    suspend fun employees(
        @Header("Authorization") bearer: String,
    ): Response<FuEmployees>

    /** 改密(统一密码同步用)。体 {old_password,new_password}。 */
    @POST("change-password")
    suspend fun changePassword(
        @Header("Authorization") bearer: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): Response<ResponseBody>

    /** 导入历史:本账号的顾客档案列表(含话术版本/收藏/战果)。 */
    @GET("customers")
    suspend fun customers(
        @Header("Authorization") bearer: String,
    ): Response<com.airec.bledemo.data.model.FuCustomers>

    /** 收藏/取消收藏顾客。 */
    @POST("customers/{name}/favorite")
    suspend fun favoriteCustomer(
        @Header("Authorization") bearer: String,
        @retrofit2.http.Path("name") name: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): Response<ResponseBody>

    /** 战果登记(覆盖写指定版本;字段=服务端 BusinessRequest)。 */
    @POST("customers/{name}/versions/{idx}/business")
    suspend fun recordBusiness(
        @Header("Authorization") bearer: String,
        @retrofit2.http.Path("name") name: String,
        @retrofit2.http.Path("idx") idx: Int,
        @Body body: Map<String, @JvmSuppressWildcards Any?>,
    ): Response<ResponseBody>
}
