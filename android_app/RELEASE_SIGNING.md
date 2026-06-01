# Release 正式签名包说明（⚠️ 含密钥，私有仓库）

> 应仓库所有者要求，签名密钥与口令一并提交到本私有仓库，方便后续迭代查找。
> ⚠️ **务必另存一份离线备份**（网盘/密码管理器）。这套 keystore 是「刁姐陪伴」App 的永久签名身份，
> **一旦丢失，现有用户将无法收到任何后续升级**（签名不一致，只能卸载重装、丢数据）。

## 密钥信息
| 项 | 值 |
|---|---|
| keystore 文件 | `android_app/gongpai-release.jks` |
| 口令配置 | `android_app/keystore.properties` |
| storePassword / keyPassword | `DiajieGongpai2026!` |
| keyAlias | `gongpai` |
| 算法 / 有效期 | RSA 2048 / 36500 天 |
| 证书 DN | `CN=Diajie Companion, O=Gongpai, L=Chengdu, ST=Sichuan, C=CN` |
| 证书 SHA-256 | `B2:78:37:52:AA:D8:8E:5E:92:50:7C:3A:3A:F9:CD:68:3B:9E:E0:1D:55:9E:35:40:9B:9F:6E:B3:85:D9:C7:7C` |

## 出 release 签名包
```bash
cd android_app
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew assembleRelease
# 产物（已签名，可直接发顾客装机）：
#   app/build/outputs/apk/release/app-release.apk
```
`app/build.gradle` 已配 `signingConfigs.release`，自动读 `keystore.properties` 给 release 包签名。

## 验证签名
```bash
~/Library/Android/sdk/build-tools/34.0.0/apksigner verify --print-certs \
  android_app/app/build/outputs/apk/release/app-release.apk
# 证书 SHA-256 应为上表的 b2783752...
```

## 升级注意
- 升级新版只需改 `app/build.gradle` 的 `versionCode`（每次+1）和 `versionName`，再 `assembleRelease`。
- **必须用同一个 `gongpai-release.jks`** 出包，顾问端才能覆盖升级。
- vivo 等装机仍可能提示「未经检测」（非应用商店分发的正常提示），放行即可；release 包已关闭 debuggable，比 debug 包更安全正规。
