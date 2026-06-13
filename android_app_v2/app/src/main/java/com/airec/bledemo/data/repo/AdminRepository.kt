package com.airec.bledemo.data.repo

import com.airec.bledemo.data.api.AdminApi
import com.airec.bledemo.data.model.OpsDashboardResponse
import com.airec.bledemo.data.net.NetworkModule
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

/**
 * 管理台数据仓库：把 [AdminApi] 的端点包成 suspend 函数，统一返回 [ApiResult]。
 * 给管理台各 ViewModel 用——所有调用都在 IO 线程，失败时尽量带上后端 error 文案。
 *
 * 风格与 [ConsultantRepository] 一致（同样的 call / parseError / defaultErr）。
 * WAVE 1 仅含运营看板；后续模块在此追加 suspend 包装即可。
 */
class AdminRepository(
    private val api: AdminApi = NetworkModule.adminApi,
    private val moshi: Moshi = NetworkModule.moshi,
) {

    // ───────────── 运营看板 ─────────────

    /**
     * 运营看板。
     * @param storeId admin/super 可选按门店筛选；店长不必传（后端强制本店）。
     */
    suspend fun opsDashboard(storeId: Long? = null): ApiResult<OpsDashboardResponse> =
        call { api.opsDashboard(storeId) }

    // ───────────── 内部：统一调用 + 错误解析（同 ConsultantRepository） ─────────────

    private suspend fun <T> call(block: suspend () -> Response<T>): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val resp = block()
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body != null) ApiResult.Success(body)
                    else ApiResult.Failure("空响应", resp.code())
                } else {
                    ApiResult.Failure(parseError(resp), resp.code())
                }
            } catch (e: Exception) {
                ApiResult.Failure(e.message ?: "网络异常", 0, e)
            }
        }

    private fun <T> parseError(resp: Response<T>): String {
        return try {
            val raw = resp.errorBody()?.string()
            if (raw.isNullOrBlank()) return defaultErr(resp.code())
            val adapter = moshi.adapter(ErrorEnvelope::class.java)
            adapter.fromJson(raw)?.error ?: defaultErr(resp.code())
        } catch (e: Exception) {
            defaultErr(resp.code())
        }
    }

    private fun defaultErr(code: Int): String = when (code) {
        401 -> "登录已失效，请重新登录"
        403 -> "无管理权限"
        404 -> "未找到"
        else -> "请求失败（HTTP $code）"
    }

    private data class ErrorEnvelope(val error: String? = null)
}
