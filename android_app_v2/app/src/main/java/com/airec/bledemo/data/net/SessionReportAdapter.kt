package com.airec.bledemo.data.net

import com.airec.bledemo.data.model.CaseReview
import com.airec.bledemo.data.model.CustomerTag
import com.airec.bledemo.data.model.DealDiagnosis
import com.airec.bledemo.data.model.ExternalSignals
import com.airec.bledemo.data.model.Harvest
import com.airec.bledemo.data.model.LogicChain
import com.airec.bledemo.data.model.NextSteps
import com.airec.bledemo.data.model.Overview
import com.airec.bledemo.data.model.PainPoint
import com.airec.bledemo.data.model.Persona
import com.airec.bledemo.data.model.RootCause
import com.airec.bledemo.data.model.Scoring
import com.airec.bledemo.data.model.SessionReport
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * 容错解析 [SessionReport]（= 后端 analysis_result，AI 生成、类型不可控）。
 *
 * 背景：报告 JSON 的每个字段都是 AI 产出，类型会飘——比如 `customer_tags` 本该是
 * `[{tag,count}]` 却给成 `["高净值"]`，或 `cases[].timestamp_seconds` 给成小数。
 * 用默认的 KotlinJsonAdapter 整份严格解析时，**任何一个字段类型不符就整份抛错**，
 * 报告页直接白屏（这正是 v2 比走 WebView 的老 app 脆的根因）。
 *
 * 本适配器把 13 个 PART 顶层 key **逐个独立解析**：某个 key 解析失败就置 null，
 * 对应 PART 优雅占位（EmptyPartNote），其余照常渲染——对齐服务器宽松解析的体验。
 *
 * 注册顺序：必须加在 KotlinJsonAdapterFactory **之前**（先注册者优先命中 SessionReport）；
 * 子类型（Overview/Persona/…）不在此命中 → 回落到 KotlinJsonAdapterFactory，无递归。
 * 仅用于读（响应），从不上行序列化；toJson 仅占位。
 */
class SessionReportAdapterFactory : JsonAdapter.Factory {

    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (Types.getRawType(type) != SessionReport::class.java) return null

        fun listType(elem: Type): Type = Types.newParameterizedType(List::class.java, elem)

        return object : JsonAdapter<SessionReport>() {
            override fun fromJson(reader: JsonReader): SessionReport? {
                val raw = reader.readJsonValue() as? Map<*, *> ?: return null

                fun <T> field(key: String, t: Type): T? {
                    val v = raw[key] ?: return null
                    return try {
                        @Suppress("UNCHECKED_CAST")
                        moshi.adapter<Any>(t).lenient().fromJsonValue(v) as T?
                    } catch (e: Exception) {
                        null   // 单个字段类型不符 → 丢弃该字段，绝不连累整份报告
                    }
                }

                return SessionReport(
                    overview = field("overview", Overview::class.java),
                    persona = field("persona", Persona::class.java),
                    rootCause = field("root_cause", RootCause::class.java),
                    painPoints = field("pain_points", listType(PainPoint::class.java)),
                    harvest = field("harvest", Harvest::class.java),
                    cases = field("cases", listType(CaseReview::class.java)),
                    casesSummary = field("cases_summary", String::class.java),
                    logicChain = field("logic_chain", LogicChain::class.java),
                    nextSteps = field("next_steps", NextSteps::class.java),
                    externalSignals = field("external_signals", ExternalSignals::class.java),
                    customerTags = field("customer_tags", listType(CustomerTag::class.java)),
                    dealDiagnosis = field("deal_diagnosis", DealDiagnosis::class.java),
                    scoring = field("scoring", Scoring::class.java),
                )
            }

            override fun toJson(writer: JsonWriter, value: SessionReport?) {
                // 报告仅读不写；占位空对象（实际从不被调用）。
                if (value == null) {
                    writer.nullValue()
                } else {
                    writer.beginObject(); writer.endObject()
                }
            }
        }
    }
}
