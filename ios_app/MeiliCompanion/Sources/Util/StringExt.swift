import Foundation

extension String {
    /// 空白串 → nil(便于 `?? 兜底`)。全 app 通用。
    var nilIfBlank: String? { trimmingCharacters(in: .whitespaces).isEmpty ? nil : self }
}
