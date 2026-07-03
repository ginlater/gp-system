import Foundation

/// 主壳内 NavigationStack 的次级页路由。android 端对应 `nav/Routes.kt`。
enum AppRoute: Hashable {
    case report(Int)                       // sessionId
    case bind(Int)                         // recordingId
    case sessionPreview(Int)               // sessionId
    case sessionPreviewByCustomer(Int, String) // customerId, date
    case customerDetail(Int)               // customerId
    case reminders
    case settings
}
