import Foundation

/// 会话详情 + 分析报告。android 端对应 `data/model/SessionReport.kt` + `SessionReportAdapter.kt`。
///
/// GET /api/session/<sid> → sessions 整行 + recordings[] + evaluations[] + report(11 PART 结构)。
/// report 是 AI 生成的结构化 JSON,类型会飘 → **每个 PART key 独立 `try?` 解析**,单个字段坏不连累整份
/// (这正是 v2「报告白屏」根因的修复;见 [[v2-report-parse-crash-float-int]])。AI 数字字段一律 Double?。

// MARK: - 会话外层

struct SessionDetail: Decodable {
    var id: Int?
    var advisor: String?
    var customer: String?
    var customerId: Int?
    var companyId: Int?
    var serviceDate: String?
    var locked: Int?
    var analysisStatus: String?
    var analysisProgress: String?
    var analysisError: String?
    var displayStatus: String?          // running|queued|done|failed|stuck|idle
    var deleteRequestPending: Bool?
    var recordings: [SessionRecording]?
    var evaluations: [Evaluation]?
    var report: SessionReport?
    var error: String?
}

struct SessionRecording: Decodable, Identifiable {
    var id: Int
    var ossKey: String?
    var recordedAt: String?
    var durationLabel: String?
    var sizeBytes: Int?
    var source: String?
    var customer: String?
    var asrStatus: String?
    var asrTranscript: String?          // 逐字转写文本:每行 "[5.20s - 7.60s] 说话人0: …"
    var asrError: String?
    var asrStartedAt: String?
    var asrFinishedAt: String?
    var asrSpeakerCount: Int?
    var asrSpeakerWarning: Int?
    var speakerConfirmed: Int?
    var advisor: String?
    var uploaderUserId: Int?
    var storeId: Int?
    var audioUrl: String?               // 仅对有权收听者签发,否则 nil(转写/报告仍可见)
}

struct Evaluation: Decodable, Identifiable {
    var id: Int?
    var sessionId: Int?
    var comment: String?
    var author: String?
    var createdAt: String?
    var rowID: Int { id ?? -1 }
}

// MARK: - report：13 个 PART(容错独立解析)

struct SessionReport: Decodable {
    var overview: Overview? = nil           // 01
    var persona: Persona? = nil             // 02
    var rootCause: RootCause? = nil         // 03
    var painPoints: [PainPoint]? = nil      // 04
    var harvest: Harvest? = nil             // 05
    var cases: [CaseReview]? = nil          // 06
    var casesSummary: String? = nil         // 06
    var logicChain: LogicChain? = nil       // 07
    var nextSteps: NextSteps? = nil         // 08
    var externalSignals: ExternalSignals? = nil // 09
    var customerTags: [CustomerTag]? = nil  // 10
    var dealDiagnosis: DealDiagnosis? = nil // 11
    var scoring: Scoring? = nil             // 质检评分(挂 PART01)

    enum CodingKeys: String, CodingKey {
        case overview, persona, rootCause, painPoints, harvest, cases, casesSummary
        case logicChain, nextSteps, externalSignals, customerTags, dealDiagnosis, scoring
    }

    init(from decoder: Decoder) throws {
        // 顶层不是对象(如 null/字符串)→ 全 nil,绝不抛。
        guard let c = try? decoder.container(keyedBy: CodingKeys.self) else { return }
        func f<T: Decodable>(_ key: CodingKeys, _ t: T.Type) -> T? {
            (try? c.decodeIfPresent(T.self, forKey: key)) ?? nil  // 单 PART 类型不符 → 丢弃该 PART
        }
        overview = f(.overview, Overview.self)
        persona = f(.persona, Persona.self)
        rootCause = f(.rootCause, RootCause.self)
        painPoints = f(.painPoints, [PainPoint].self)
        harvest = f(.harvest, Harvest.self)
        cases = f(.cases, [CaseReview].self)
        casesSummary = f(.casesSummary, String.self)
        logicChain = f(.logicChain, LogicChain.self)
        nextSteps = f(.nextSteps, NextSteps.self)
        externalSignals = f(.externalSignals, ExternalSignals.self)
        customerTags = f(.customerTags, [CustomerTag].self)
        dealDiagnosis = f(.dealDiagnosis, DealDiagnosis.self)
        scoring = f(.scoring, Scoring.self)
    }
}

// ── PART 01 全维度评估总览 ──
struct Overview: Decodable {
    var customerValue: OverviewCell?
    var painSummary: OverviewPainSummary?
    var salesDiagnosis: OverviewCell?
    var qualityScore: OverviewScore?
    var suggestions: [String]?
}
struct OverviewCell: Decodable { var tag: String?; var tagKind: String?; var note: String? }
struct OverviewPainSummary: Decodable { var tag: String?; var tagKind: String?; var items: [OverviewPainItem]? }
struct OverviewPainItem: Decodable { var color: String?; var text: String? }
struct OverviewScore: Decodable { var score: Double?; var note: String? }

// ── PART 02 顾客真实画像 ──
struct Persona: Decodable { var lead: String?; var signals: [PersonaSignal]?; var summary: String? }
struct PersonaSignal: Decodable { var signal: String?; var interpretation: String? }

// ── PART 03 失分根因 ──
struct RootCause: Decodable {
    var lead: String?
    var headline: String?
    var productDimension: [String]?
    var problemDimension: [String]?
    var gapNote: String?
}

// ── PART 04 痛点·作战方案 ──
struct PainPoint: Decodable {
    var title: String?
    var badge: String?
    var lead: String?
    var steps: [PainStep]?
    var strategyTable: [PainStrategy]?
}
struct PainStep: Decodable { var label: String?; var body: String? }
struct PainStrategy: Decodable { var strategy: String?; var logic: String? }

// ── PART 05 价值收割·黄金窗口 ──
struct Harvest: Decodable { var intro: String?; var steps: [HarvestStep]? }
struct HarvestStep: Decodable { var title: String?; var body: String? }

// ── PART 06 关键 Case 复盘 ──
struct CaseReview: Decodable {
    var kind: String?          // good | bad | miss
    var title: String?
    var quote: String?
    var timestampSeconds: Double?   // AI 可能给小数 → Double 兜底
    var timestampLabel: String?
    var segment: Int?
    var surface: String?
    var deep: String?
    var improve: String?
}

// ── PART 07 逻辑链 + 训练路径 ──
struct LogicChain: Decodable {
    var badChain: String?
    var badChainNote: String?
    var goodChain: String?
    var missingStep: String?
    var training: [TrainingItem]?
}
struct TrainingItem: Decodable { var stage: String?; var issue: String?; var skill: String? }

// ── PART 08 下一步·回店规划 ──
struct NextSteps: Decodable {
    var returnScripts: [NextTitledBody]?
    var priorityProjects: [PriorityProject]?
    var medicalObjections: [ObjectionQa]?
    var objectionQa: [ObjectionQa]?
    var painEntryScripts: [PainEntryScript]?
}
struct NextTitledBody: Decodable { var title: String?; var body: String? }
struct PriorityProject: Decodable { var name: String?; var desc: String? }

/// 异议应答:兼容 objection/q 与 answer/a 两套字段名。
struct ObjectionQa: Decodable {
    var objection: String?
    var q: String?
    var answer: String?
    var a: String?
    var question: String? { objection ?? q }
    var reply: String? { answer ?? a }
}
struct PainEntryScript: Decodable {
    var painName: String?
    var entry: String?
    var principle: String?
    var direction: String?
    var salesLink: String?
}

// ── PART 09 竞品·学习清单 ──
struct ExternalSignals: Decodable {
    var medicalAesthetics: [Competitor]?
    var otherInstitutions: [Competitor]?
    var externalBrands: [Competitor]?
    func flatten() -> [Competitor] {
        (medicalAesthetics ?? []) + (otherInstitutions ?? []) + (externalBrands ?? [])
    }
}
struct Competitor: Decodable {
    var item: String?
    var type: String?
    var customerQuote: String?
    var competitorLearn: String?
}

// ── PART 10 顾客标签 ──
struct CustomerTag: Decodable { var tag: String?; var count: Int? }

// ── PART 11 成交诊断 ──
struct DealDiagnosis: Decodable {
    var dealResult: Bool?
    var dealAmount: String?
    var riskLevel: String?     // high | medium | low
    var riskAlert: Bool?
    var riskText: String?
    var dimensions: DealDimensions?
}
struct DealDimensions: Decodable {
    var customerMoved: DealDimension?
    var customerAgreed: DealDimension?
    var effectSatisfied: DealDimension?
    var priceMatched: DealDimension?
    var urgencyBuilt: DealDimension?
}
struct DealDimension: Decodable { var status: String?; var note: String? }  // ok | partial | missing

// ── 质检评分明细(可点击查看详情)──
struct Scoring: Decodable {
    var overall: Double?
    var stages: [ScoringStage]?
    var goodHighlights: [String]?
    var badHighlights: [String]?
    /// 综合分 = 各阶段分均值,保留1位(对齐 android overallScoreOf,不读后端可能为0的存量值)。
    var displayOverall: Double? {
        let ss = (stages ?? []).compactMap { $0.computedScore }
        guard !ss.isEmpty else { return overall }
        return ((ss.reduce(0, +) / Double(ss.count)) * 10).rounded() / 10
    }
}
struct ScoringStage: Decodable {
    var name: String?; var score: Double?; var sub: [ScoringSub]?
    /// 阶段分 = 子项均值,保留1位;无子项退回存量 score。
    var computedScore: Double? {
        let subs = (sub ?? []).compactMap { $0.score }
        guard !subs.isEmpty else { return score }
        return ((subs.reduce(0, +) / Double(subs.count)) * 10).rounded() / 10
    }
}
struct ScoringSub: Decodable { var name: String?; var score: Double?; var detail: String? }

// ── PART 10 动态接口 ──
struct CustomerTagsResponse: Decodable {
    var firstVisit: Bool?
    var currentTags: [CurrentTag]?
    var history: [CustomerTag]?
    var range: String?
}
struct CurrentTag: Decodable { var tag: String?; var isNew: Bool? }

/// GET /api/session/<sid>/evaluations 响应。
struct EvaluationsResponse: Decodable { var evaluations: [Evaluation]? }
