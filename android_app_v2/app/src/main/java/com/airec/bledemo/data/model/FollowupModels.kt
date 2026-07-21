package com.airec.bledemo.data.model

import com.squareup.moshi.Json

/* ===================================================================
 * 回访话术 / 高情商话术(followup-agent)领域模型。
 *
 * 服务端:huifang-prod /opt/followup-agent(huifang.beautyshining.com)与
 * dev-machine /opt/followup-agent(intelligent.beautyshining.com,高情商变体)。
 * 字段名 1:1 对齐 server.py 真实契约(2026-07-15 上服务器核对):
 *  - 登录 POST /login/account,密码字段名是 **key** 不是 password;
 *  - JWT 7 天有效、无单设备互踢,401 仅有过期一种(静默重登即可);
 *  - 生成 POST /generate 走 SSE 流式(data: {"content": 增量} / data: [DONE]),
 *    SSE 帧解析在 FollowupRepository,不走 Moshi。
 * =================================================================== */

/** POST /login/account 请求体。⚠️ 密码字段名是 key。 */
data class FuLoginReq(
    @Json(name = "username") val username: String,
    @Json(name = "key") val key: String,
)

/** POST /login/account 响应。 */
data class FuLoginResp(
    @Json(name = "token") val token: String? = null,
    @Json(name = "username") val username: String? = null,
    @Json(name = "company") val company: String? = null,
    @Json(name = "error") val error: String? = null,
)

/** GET /quota 响应。groups: 模型组名 → 用量;limit=-1 表示无限。 */
data class FuQuota(
    @Json(name = "username") val username: String? = null,
    @Json(name = "bypass") val bypass: Boolean? = null,
    @Json(name = "no_monthly_reset") val noMonthlyReset: Boolean? = null,
    @Json(name = "month") val month: String? = null,
    @Json(name = "groups") val groups: Map<String, FuQuotaGroup>? = null,
    @Json(name = "error") val error: String? = null,
)

data class FuQuotaGroup(
    @Json(name = "used") val used: Int? = null,
    @Json(name = "limit") val limit: Int? = null,
)

/** GET /customers 响应(导入历史:按账号隔离的顾客档案+话术版本)。 */
data class FuCustomers(
    @Json(name = "customers") val customers: List<FuCustomer>? = null,
)

data class FuCustomer(
    @Json(name = "name") val name: String? = null,
    @Json(name = "savedAt") val savedAt: String? = null,
    @Json(name = "data") val data: Map<String, Any?>? = null,
    @Json(name = "lastScript") val lastScript: String? = null,
    @Json(name = "favorited") val favorited: Boolean? = null,
    @Json(name = "scripts") val scripts: List<FuScript>? = null,
)

/** 单个历史话术版本。 */
data class FuScript(
    @Json(name = "generatedAt") val generatedAt: String? = null,
    @Json(name = "script") val script: String? = null,
    @Json(name = "favorited") val favorited: Boolean? = null,
    @Json(name = "business") val business: Map<String, Any?>? = null,
)

/** GET /employees/list 响应(顾问姓名/门店自动带出)。 */
data class FuEmployees(
    @Json(name = "employees") val employees: List<FuEmployee>? = null,
)

data class FuEmployee(
    @Json(name = "name") val name: String? = null,
    @Json(name = "store") val store: String? = null,
)
