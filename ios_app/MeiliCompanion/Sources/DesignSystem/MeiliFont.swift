import SwiftUI

/// 「暖玉柔光」字体层级。android 端对应 `designsystem/Type.kt`。
///
/// 对照 warm_2.html:
/// - `--serif:"Songti SC",…` —— 标题/大数字用宋体提升高级感。iOS 自带「Songti SC」,直接用最贴近原型。
/// - 正文/按钮用系统无衬线(PingFang SC,即 `.system`)。
///
/// 像素值取自原型高频字号(appbar 27、sheet h3 20、card-title 15、正文 12.5–14.5、smallnote 11.5…)。
/// 用固定字号(fixedSize)保 360pt 基线不溢出,与 android 的固定 sp 对齐;Dynamic Type 为后续待办。
enum MeiliFont {
    /// 全局字体缩放系数(设置·字体大小)。ThemeManager.fontScale 变 → RootView 重渲染 → 全 app 字体随之变。
    static var scale: CGFloat { ThemeManager.shared.fontScale }
    /// 宋体衬线(Songti SC)。系统自带;若缺失 SwiftUI 回退系统字体。
    static func serif(_ size: CGFloat) -> Font { .custom("Songti SC", fixedSize: size * scale) }
    /// 宋体加粗。
    static func serifBold(_ size: CGFloat) -> Font { .custom("STSongti-SC-Bold", fixedSize: size * scale) }
    /// 系统无衬线(PingFang SC)。
    static func sans(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size * scale, weight: weight)
    }

    // ---- 衬线标题 / 大数字 ----
    static var brandTitle: Font { serif(29) }        // 品牌标题(tracking 3)
    static var title: Font { serif(27) }             // appbar .title
    static var titleSm: Font { serif(23) }           // .title.sm
    static var sheetH3: Font { serif(20) }           // sheet h3
    static var timer: Font { serif(48) }             // .timer(tabular)
    static var scoreBig: Font { serif(34) }          // .score-big
    static var summaryBig: Font { serif(24) }        // .tasks-sum .big

    // ---- 无衬线正文 / 标题 / 标签 ----
    static var sectionHead: Font { sans(16, .heavy) }    // 顶栏次级标题 / report 头
    static var cardTitle: Font { sans(15, .heavy) }      // .card-title
    static var rowTitle: Font { sans(15, .heavy) }       // .nm / .et 行标题
    static var foldTitle: Font { sans(14, .heavy) }      // .fold-head .ft / part .pt
    static var bodyLarge: Font { sans(14.5) }            // .input / 大正文
    static var body: Font { sans(13) }                   // .bullet / .advice
    static var bodySm: Font { sans(12.5) }               // .meta / .card-sub
    static var button: Font { sans(14.5, .bold) }        // .btn
    static var label: Font { sans(11.5, .bold) }         // .section-lbl / .pill
    static var labelSm: Font { sans(10.5, .bold) }       // .smallnote / nav-item
}

/// 跟随「字体大小」设置整体缩放的系统字体。用于替换散落的 `.system(size:)`,
/// 使「设置·字体大小」能整体放大/缩小全 app 文字(含报告页那些固定小字)。
extension Font {
    static func sz(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size * MeiliFont.scale, weight: weight)
    }
}
