import CoreGraphics

/// 「暖玉柔光」间距 / 尺寸 / 圆角 token。android 端对应 `designsystem/Dimens.kt` + `Shape.kt`。
///
/// 间距 s1–s6 与 warm_2.html `:root` 一致;圆角 r-xs…r-pill 一致。
/// 屏幕基线 360–390pt,组件须弹性等分、不得横向溢出。
enum MeiliMetric {
    // ---- 间距阶梯(--s1…--s6) ----
    static let s1: CGFloat = 6
    static let s2: CGFloat = 10
    static let s3: CGFloat = 14
    static let s4: CGFloat = 18
    static let s5: CGFloat = 24
    static let s6: CGFloat = 32

    // ---- 布局留白 ----
    static let screenH: CGFloat = 18      // .scroll 左右内边距
    static let cardPad: CGFloat = 20      // .card padding
    static let cardPadTight: CGFloat = 15 // .card.tight padding
    static let cardGap: CGFloat = 16      // 卡片间距
    static let bottomNavInset: CGFloat = 96 // 底栏避让

    // ---- 组件固定尺寸 ----
    static let iconButton: CGFloat = 44
    static let iconButtonBack: CGFloat = 40
    static let avatar: CGFloat = 46
    static let avatarLg: CGFloat = 56
    static let companionCircle: CGFloat = 152
    static let companionIcon: CGFloat = 48
    static let playButton: CGFloat = 40
    static let bottomNav: CGFloat = 88
    static let trackHeight: CGFloat = 7
    static let borderThin: CGFloat = 1
    static let borderField: CGFloat = 1.5

    // ---- 图标尺寸(.ic 22 / .ic.sm 18 / .ic.lg 26 / xs 15) ----
    static let icon: CGFloat = 22
    static let iconSm: CGFloat = 18
    static let iconLg: CGFloat = 26
    static let iconXs: CGFloat = 15
}

/// 圆角(--r-* token)。
enum MeiliRadius {
    static let xs: CGFloat = 12
    static let sm: CGFloat = 16
    static let md: CGFloat = 20
    static let lg: CGFloat = 24
    static let xl: CGFloat = 30
    static let pill: CGFloat = 999
}
