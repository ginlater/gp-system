import UIKit

/// 恢复「屏幕左缘右滑返回」手势。
/// 全 App 页面都用自定义顶栏并隐藏了系统导航栏(.toolbar(.hidden, for: .navigationBar)),
/// 系统的交互式返回手势默认跟导航栏绑定,藏掉导航栏它也被禁用了——绑定页/客户详情等
/// 推入页只能点左上角返回,体感差。这里把手势代理接管回来:
/// - 仅在导航栈深度 > 1 时允许(根部不触发,不影响首页 TabView 横滑切 tab);
/// - NavigationStack 底层就是 UINavigationController,该扩展对其直接生效。
extension UINavigationController: UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }
}
