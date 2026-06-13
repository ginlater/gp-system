package com.airec.bledemo.data.repo

import retrofit2.Response

/**
 * 统一的接口结果密封类，供 ViewModel 消费。
 * - Success: 取到 body。
 * - Failure: 业务/HTTP 失败，message 已尽量取后端 error 文案；code 是 HTTP 状态码（0=异常无响应）。
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(
        val message: String,
        val code: Int = 0,
        val cause: Throwable? = null,
    ) : ApiResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): T? = (this as? Success)?.data
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}
