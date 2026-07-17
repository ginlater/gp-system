import SwiftUI

/// 设置(SPEC §4.11)。android 端对应 `ui/settings/SettingsScreen.kt`。
/// 陪伴师信息 + 主题皮肤(6 套 + 自动日夜) + 关于/版本(检查更新) + 退出登录。
/// 注:iOS 走 App Store,android 的 APK 强制升级不适用 → 检查更新只提示。
struct SettingsView: View {
    /// 多系统账号:「切换系统工作台」回调(pop 回工作台宫格;nil = 单系统/非工作台栈,不显示该卡)。
    var onSwitchWorkspace: (() -> Void)? = nil

    @EnvironmentObject private var app: AppState
    @ObservedObject private var theme = ThemeManager.shared
    @Environment(\.dismiss) private var dismiss

    @State private var editNight = ThemeManager.isNightNow()
    @State private var versionMsg: String?
    @State private var updateUrl: String?   // ★有新版时=安装页地址,按钮变「去更新」
    @State private var diagMsg: String?
    @State private var diagBusy = false
    @State private var showChangePwd = false
    @State private var showDeleteAccount = false   // ★2026-07-17 注销账号弹窗(苹果 5.1.1(v))

    private var me: Me? { app.me }
    private var displayName: String { me?.advisorName?.nilIfBlank ?? me?.username?.nilIfBlank ?? "陪伴师" }
    private var roleLabel: String {
        switch me?.role {
        case "store_manager": return "店长"
        case "admin", "super": return "管理员"
        default: return "陪伴师"
        }
    }
    private var version: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(v) (\(b))"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: MeiliMetric.cardGap) {
                MeiliTopBar(title: "设置", onBack: { dismiss() })

                SectionLabel("陪伴师", icon: MeiliIcons.profile)
                accountCard
                changePasswordEntry

                // 多系统工作台(仅 ≥2 系统的账号显示;点击回工作台宫格选系统,不用退出重登)
                if me?.multiSystem == true, let onSwitchWorkspace {
                    SectionLabel("工作台", icon: MeiliIcons.workspace)
                    workspaceSwitchCard(onSwitchWorkspace)
                }

                SectionLabel("主题皮肤", icon: MeiliIcons.palette)
                themeCard

                SectionLabel("字体大小", icon: MeiliIcons.doc)
                fontSizeCard

                SectionLabel("关于美业私教", icon: MeiliIcons.info)
                aboutCard

                MeiliButton(app.me == nil ? "退出登录" : "退出登录", kind: .ghost, icon: MeiliIcons.lock, block: true) {
                    Task { await app.logout() }
                }
                .padding(.top, 4)

                // ★2026-07-17 合规 —— 账号注销入口。苹果 5.1.1(v) 硬性要求:支持登录的 App
                // 必须能在 App 内自助删账号,「联系客服注销」不算。跟「退出登录」是两码事:
                // 退出只清会话,注销是真删账号(个人信息删除,录音/报告归门店保留)。
                Button {
                    showDeleteAccount = true
                } label: {
                    Text("注销账号")
                        .font(.sz(12.5)).foregroundStyle(MeiliColor.ink3)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }

                Text("美业私教 · 高端身体美容陪伴助手")
                    .font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                    .frame(maxWidth: .infinity).padding(.top, 4)
            }
            .padding(.horizontal, MeiliMetric.screenH)
            .padding(.bottom, 28)
        }
        .background(MeiliColor.bg)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $showChangePwd) {
            ChangePasswordSheet()
        }
        .sheet(isPresented: $showDeleteAccount) {
            DeleteAccountSheet()
        }
    }

    // MARK: 修改密码 / 工作台切换

    /// 「修改密码」入口卡(改统一密码,全系统同步)。
    private var changePasswordEntry: some View {
        Button { showChangePwd = true } label: {
            MeiliCard {
                HStack(spacing: MeiliMetric.s2) {
                    MeiliIcon(MeiliIcons.lock, size: MeiliMetric.icon).foregroundStyle(MeiliColor.ink2)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("修改密码").font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                        Text("改一次,全部工作台一起改").font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                    }
                    Spacer()
                    MeiliIcon(MeiliIcons.chevRight, size: MeiliMetric.iconSm).foregroundStyle(MeiliColor.ink4)
                }
            }
        }
        .buttonStyle(PressScaleButtonStyle(scale: 0.98))
    }

    /// 「切换系统工作台」入口卡(仅多系统账号显示):点击回工作台宫格。
    private func workspaceSwitchCard(_ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            MeiliCard {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("切换系统工作台").font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                        Text("本账号已开通 \(me?.systems?.count ?? 0) 个系统，点击切换")
                            .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                    }
                    Spacer()
                    MeiliIcon(MeiliIcons.chevRight, size: MeiliMetric.iconSm).foregroundStyle(MeiliColor.ink4)
                }
            }
        }
        .buttonStyle(PressScaleButtonStyle(scale: 0.98))
    }

    // MARK: 账号

    private var accountCard: some View {
        MeiliCard {
            HStack(spacing: 13) {
                MeiliAvatar(name: displayName, size: 46)
                VStack(alignment: .leading, spacing: 6) {
                    Text(displayName).font(.sz(16, weight: .heavy)).foregroundStyle(MeiliColor.ink)
                    HStack(spacing: 8) {
                        StatusPill(text: roleLabel, kind: .run, icon: MeiliIcons.profile)
                        if let u = me?.username?.nilIfBlank { Text(u).font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3) }
                    }
                }
                Spacer()
            }
            if let p = me?.phone?.nilIfBlank {
                Rectangle().fill(MeiliColor.line).frame(height: 1).padding(.top, 13)
                HStack {
                    Text("联系电话").font(.sz(12.5)).foregroundStyle(MeiliColor.ink2)
                    Spacer()
                    Text(p).font(.sz(12.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                }
                .padding(.top, 9)
            }
        }
    }

    // MARK: 主题皮肤

    private var themeCard: some View {
        MeiliCard {
            // 自动日夜开关
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("自动日夜切换").font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    Text("晚 18:00–早 6:00 自动用夜间皮肤").font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                }
                Spacer()
                Toggle("", isOn: Binding(get: { theme.autoMode }, set: { theme.setAuto($0) }))
                    .labelsHidden().tint(MeiliColor.clay)
            }
            Spacer().frame(height: 13)

            if theme.autoMode {
                HStack(spacing: 8) {
                    segChip("☀ 白天", selected: !editNight) { editNight = false }
                    segChip("🌙 晚上", selected: editNight) { editNight = true }
                }
                Text(editNight ? "夜间（18:00–6:00）用这套：" : "白天（6:00–18:00）用这套：")
                    .font(.sz(11.5)).foregroundStyle(MeiliColor.ink3).padding(.top, 8)
                Spacer().frame(height: 11)
            } else {
                Text("选一套喜欢的配色，整个 App 会跟着变。")
                    .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
                Spacer().frame(height: 12)
            }

            let target = !theme.autoMode ? theme.currentId : (editNight ? theme.nightSkinId : theme.daySkinId)
            VStack(spacing: 9) {
                ForEach(ThemeManager.skins) { skin in
                    skinRow(skin, selected: skin.id == target) {
                        if !theme.autoMode { theme.apply(skin.id) }
                        else if editNight { theme.setNightSkin(skin.id) }
                        else { theme.setDaySkin(skin.id) }
                    }
                }
            }
        }
    }

    private func segChip(_ text: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.sz(13, weight: .bold))
                .foregroundStyle(selected ? MeiliColor.clayDeep : MeiliColor.ink2)
                .frame(maxWidth: .infinity).padding(.vertical, 9)
                .background(selected ? MeiliColor.clayTint : MeiliColor.surfaceSoft)
                .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
                .overlay { RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous).strokeBorder(selected ? MeiliColor.clay : MeiliColor.line, lineWidth: 1.5) }
        }
        .buttonStyle(.plain)
    }

    private func skinRow(_ skin: ThemeManager.Skin, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(skin.bg).frame(width: 26, height: 26)
                    Circle().fill(RadialGradient(colors: [skin.clay.mixWhite(0.3), skin.clay], center: .topLeading, startRadius: 0, endRadius: 14))
                        .frame(width: 15, height: 15)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(skin.name).font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    Text(skin.desc).font(.sz(11)).foregroundStyle(MeiliColor.ink3)
                }
                Spacer()
                if selected { MeiliIcon(MeiliIcons.check, size: 20).foregroundStyle(MeiliColor.clayDeep) }
            }
            .padding(.horizontal, 13).padding(.vertical, 11)
            .background(selected ? MeiliColor.clayTint : MeiliColor.surface)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous).strokeBorder(selected ? MeiliColor.clay : MeiliColor.line, lineWidth: 1.5) }
        }
        .buttonStyle(.plain)
    }

    // MARK: 字体大小

    private var fontSizeCard: some View {
        MeiliCard {
            Text("调整全 App 的文字大小，顾客报告也会跟着变大。")
                .font(MeiliFont.bodySm).foregroundStyle(MeiliColor.ink3)
            Spacer().frame(height: 13)
            HStack(spacing: 8) {
                fontChip("小", 0.9)
                fontChip("标准", 1.0)
                fontChip("大", 1.15)
                fontChip("特大", 1.3)
            }
            Spacer().frame(height: 14)
            // 实时预览(用 MeiliFont,随 fontScale 变)
            VStack(alignment: .leading, spacing: 6) {
                Text("预览").font(MeiliFont.label).foregroundStyle(MeiliColor.ink3)
                Text("顾客今天的状态不错，建议下次重点跟进肩颈放松与补水护理。")
                    .font(MeiliFont.body).foregroundStyle(MeiliColor.ink)
            }
            .padding(13)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(MeiliColor.surfaceSoft)
            .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
        }
    }

    private func fontChip(_ label: String, _ scale: CGFloat) -> some View {
        let selected = abs(theme.fontScale - scale) < 0.01
        return Button { theme.setFontScale(scale) } label: {
            Text(label).font(.sz(13, weight: .bold))
                .foregroundStyle(selected ? MeiliColor.clayDeep : MeiliColor.ink2)
                .frame(maxWidth: .infinity).padding(.vertical, 9)
                .background(selected ? MeiliColor.clayTint : MeiliColor.surfaceSoft)
                .clipShape(RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous))
                .overlay { RoundedRectangle(cornerRadius: MeiliRadius.sm, style: .continuous).strokeBorder(selected ? MeiliColor.clay : MeiliColor.line, lineWidth: 1.5) }
        }
        .buttonStyle(.plain)
    }

    // MARK: 关于 / 版本

    private var aboutCard: some View {
        MeiliCard {
            HStack {
                Text("当前版本").font(.sz(12.5)).foregroundStyle(MeiliColor.ink2)
                Spacer()
                Text(version).font(.sz(12.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
            }
            Rectangle().fill(MeiliColor.line).frame(height: 1).padding(.vertical, 11)
            // ★2026-07-16 工信部《移动互联网应用程序备案》强制要求:备案号必须在 App 内展示。
            HStack {
                Text("ICP 备案号").font(.sz(12.5)).foregroundStyle(MeiliColor.ink2)
                Spacer()
                Text("蜀ICP备2024099992号-4A").font(.sz(12.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
            }
            Rectangle().fill(MeiliColor.line).frame(height: 1).padding(.vertical, 11)
            // ★2026-07-17 合规 —— App 内可随时查看的隐私政策/用户协议入口(安卓设置页同款)。
            // ⚠️ 链接必须走 PrivacyConsent 的常量:商店元数据/首启弹窗/本入口三处要同一个地址。
            Button {
                UIApplication.shared.open(PrivacyConsent.privacyURL)
            } label: {
                HStack {
                    Text("隐私政策").font(.sz(12.5)).foregroundStyle(MeiliColor.ink2)
                    Spacer()
                    Text("查看").font(.sz(12.5, weight: .bold)).foregroundStyle(MeiliColor.clay)
                }
            }
            Rectangle().fill(MeiliColor.line).frame(height: 1).padding(.vertical, 11)
            Button {
                UIApplication.shared.open(PrivacyConsent.termsURL)
            } label: {
                HStack {
                    Text("用户协议").font(.sz(12.5)).foregroundStyle(MeiliColor.ink2)
                    Spacer()
                    Text("查看").font(.sz(12.5, weight: .bold)).foregroundStyle(MeiliColor.clay)
                }
            }
            Rectangle().fill(MeiliColor.line).frame(height: 1).padding(.vertical, 11)
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("检查更新").font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    Text(versionMsg ?? "看看有没有更顺手的新版本").font(.sz(11.5)).foregroundStyle(MeiliColor.ink3)
                }
                Spacer()
                MeiliButton(updateUrl == nil ? "检查" : "去更新", kind: .ghost, size: .xs) {
                    // ★真实检查(Ad Hoc 分发没有自动更新):比对服务端 latestBuild 与本机 build,
                    //   有新版按钮变「去更新」,点击 Safari 打开安装页重装即升级。
                    if let u = updateUrl, let url = URL(string: u) {
                        UIApplication.shared.open(url)
                        return
                    }
                    Task { await checkUpdate() }
                }
            }
            Rectangle().fill(MeiliColor.line).frame(height: 1).padding(.vertical, 11)
            // 一键诊断上传(F11):penlog+设备信息直达工程师,不用连电脑
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("上传诊断日志").font(.sz(13.5, weight: .bold)).foregroundStyle(MeiliColor.ink)
                    Text(diagMsg ?? "遇到陪伴笔问题时点这个，日志直达工程师").font(.sz(11.5)).foregroundStyle(MeiliColor.ink3)
                }
                Spacer()
                MeiliButton(diagBusy ? "上传中…" : "上传", kind: .ghost, size: .xs, enabled: !diagBusy) {
                    uploadDiag()
                }
            }
        }
    }

    /// ★真实版本检查(替换原来写死的"已是最新"):比对服务端 /api/app/ios/version 的 latestBuild。
    private func checkUpdate() async {
        guard let v = try? await ConsultantRepo.iosVersion() else {
            versionMsg = "检查失败，请稍后再试"
            return
        }
        let local = Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1") ?? 1
        if let latest = v.latestBuild, latest > local, let url = v.installUrl, !url.isEmpty {
            versionMsg = "发现新版本 \(v.latestVersionName ?? "") (\(latest))：\(v.updateNote ?? "点「去更新」安装")"
            updateUrl = url
        } else {
            versionMsg = "已是最新版本，无需更新"
            updateUrl = nil
        }
    }

    private func uploadDiag() {
        diagBusy = true
        Task {
            defer { diagBusy = false }
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let penlog = docs.appendingPathComponent("penlog.txt")
            guard FileManager.default.fileExists(atPath: penlog.path) else {
                diagMsg = "暂无日志可上传"
                return
            }
            let model = UIDevice.current.model
            let sys = UIDevice.current.systemVersion
            let app = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
            // ★对齐安卓2.1.3③:诊断带蓝牙状态——btState 原始值是分辨"用户没开蓝牙"vs"蓝牙栈假死"的证据
            let btOn = PenBluetoothWatch.shared.isPoweredOn
            let btState = PenBluetoothWatch.shared.stateRaw
            let meta = "{\"platform\":\"ios\",\"model\":\"\(model)\",\"system\":\"\(sys)\",\"app\":\"\(app)\",\"btEnabled\":\(btOn),\"btState\":\(btState)}"
            do {
                _ = try await ConsultantRepo.uploadDiag(penlogURL: penlog, meta: meta)
                diagMsg = "已上传，工程师可远程查看"
            } catch {
                diagMsg = "上传失败，请检查网络后重试"
            }
        }
    }
}
