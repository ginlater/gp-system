import CoreBluetooth

/// 探测用 CBCentralManager(与闭源 PNode SDK 并存,只看不连)。审计 L1/L4:
/// ① 蓝牙开关/权限感知:SDK 对"用户关了蓝牙/拒了权限"全盲,重连循环会对着空气扫;
///    这里监听系统状态,关→停重连+精准提示,开→立即踢一次扫描。
/// ② 僵尸连接检测:App 被杀后笔可能仍挂在系统蓝牙上(连着就不广播,扫描永远搜不到);
///    `retrieveConnectedPeripherals` 能看到系统级已连接外设,扫不到时先查这里,精准提示重启笔。
final class PenBluetoothWatch: NSObject, CBCentralManagerDelegate {
    static let shared = PenBluetoothWatch()

    /// 状态变化回调(主线程)。
    var onStateChange: ((CBManagerState) -> Void)?

    private var central: CBCentralManager!

    private override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    /// 触发懒加载(setup 时调一次)。
    func start() {}

    var isPoweredOn: Bool { central.state == .poweredOn }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        PenLog.d("系统蓝牙状态: \(central.state.rawValue)")
        onStateChange?(central.state)
    }

    /// 查系统级"已连接外设"里有没有陪伴笔(名字 CB 开头)。
    /// 服务 UUID 拿不到官方文档,按声云特征值 AE21/AE23 推测 + 常见透传服务多猜几个。
    func findSystemConnectedPen() -> String? {
        guard central.state == .poweredOn else { return nil }
        let candidates = ["AE00", "AE20", "AE30", "FFE0", "FFF0", "180A"].map { CBUUID(string: $0) }
        let found = central.retrieveConnectedPeripherals(withServices: candidates)
        return found.first(where: { ($0.name ?? "").uppercased().hasPrefix("CB") })?.name
    }
}
