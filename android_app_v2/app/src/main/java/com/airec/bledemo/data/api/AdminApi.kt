package com.airec.bledemo.data.api

import com.airec.bledemo.data.model.OpsDashboardResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/* ===================================================================
 * Retrofit 接口：管理台（admin / super / store_manager 的管理类视图）端点。
 *
 * base = https://gp.beautyshining.com（同 [ConsultantApi]，复用同一 NetworkModule
 * 的 OkHttp + PrefsCookieJar，会话 Cookie 自动带）。取实例：
 *   NetworkModule.adminApi  （= NetworkModule.retrofit.create(AdminApi::class.java)）
 *
 * 全部返回 Response<T>，便于 Repository 区分 HTTP 状态码 + 取后端 error 文案。
 * 字段以 webapp.py 真实路由为准（见 data/model/AdminOps.kt）。
 *
 * WAVE 1 仅含运营看板；后续管理模块（删除审批 / 员工 / 标签 等）在此追加端点即可。
 * =================================================================== */
interface AdminApi {

    /**
     * 运营看板（@manager_required：admin / super / store_manager 均可；
     * store_manager 由后端 current_store_filter() 收口到本店）。
     *
     * @param storeId admin/super 可选按门店筛选；店长无需传（后端忽略并强制本店）。
     */
    @GET("api/admin/ops_dashboard")
    suspend fun opsDashboard(
        @Query("store_id") storeId: Long? = null,
    ): Response<OpsDashboardResponse>
}
