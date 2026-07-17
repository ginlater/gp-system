import Foundation

/* ===================================================================
 * 回访话术 / 高情商话术(followup-agent)领域模型。
 * android 端对应 `data/model/FollowupModels.kt`。
 *
 * 服务端字段名 1:1 对齐 server.py 真实契约:
 *  - 登录 POST /login/account,密码字段名是 **key** 不是 password;
 *  - JWT 7 天有效、无单设备互踢,401 仅有过期一种(静默重登即可);
 *  - 生成 POST /generate 走 SSE 流式,帧解析在 FollowupRepository。
 * ⚠️ customers 相关 key 服务端本就是 camelCase(savedAt/lastScript),
 *    quota 是 snake(no_monthly_reset)——各自显式 CodingKeys,统一用 plainDecoder。
 * =================================================================== */

/// 任意 JSON 值(顾客档案 data / 战果 business 是自由 dict,服务端原样存取)。
enum JSONValue: Codable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case array([JSONValue])
    case object([String: JSONValue])
    case null

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else if let o = try? c.decode([String: JSONValue].self) { self = .object(o) }
        else { self = .null }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let n): try c.encode(n)
        case .bool(let b): try c.encode(b)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        case .null: try c.encodeNil()
        }
    }

    var stringValue: String? { if case .string(let s) = self { return s }; return nil }
    var doubleValue: Double? { if case .number(let n) = self { return n }; return nil }
    var stringList: [String]? {
        if case .array(let a) = self { return a.compactMap(\.stringValue) }
        return nil
    }

    /// 转 JSONSerialization 兼容值(SSE 请求体用)。
    var anyValue: Any {
        switch self {
        case .string(let s): return s
        case .number(let n): return n
        case .bool(let b): return b
        case .array(let a): return a.map(\.anyValue)
        case .object(let o): return o.mapValues(\.anyValue)
        case .null: return NSNull()
        }
    }
}

/// POST /login/account 响应。
struct FuLoginResp: Decodable {
    var token: String?
    var username: String?
    var company: String?
    var error: String?
}

/// GET /quota 响应。groups: 模型组名 → 用量;limit=-1 表示无限。
struct FuQuota: Decodable {
    var username: String?
    var bypass: Bool?
    var noMonthlyReset: Bool?
    var month: String?
    var groups: [String: FuQuotaGroup]?
    var error: String?

    enum CodingKeys: String, CodingKey {
        case username, bypass, month, groups, error
        case noMonthlyReset = "no_monthly_reset"
    }
}

struct FuQuotaGroup: Decodable {
    var used: Int?
    var limit: Int?
}

/// GET /customers 响应(导入历史:按账号隔离的顾客档案+话术版本)。
struct FuCustomers: Decodable {
    var customers: [FuCustomer]?
}

struct FuCustomer: Decodable {
    var name: String?
    var savedAt: String?
    var data: [String: JSONValue]?
    var lastScript: String?
    var favorited: Bool?
    var scripts: [FuScript]?
}

/// 单个历史话术版本。
struct FuScript: Decodable {
    var generatedAt: String?
    var script: String?
    var favorited: Bool?
    var business: [String: JSONValue]?
}

/// GET /employees/list 响应(顾问姓名/门店自动带出)。
struct FuEmployees: Decodable {
    var employees: [FuEmployee]?
}

struct FuEmployee: Decodable {
    var name: String?
    var store: String?
}
