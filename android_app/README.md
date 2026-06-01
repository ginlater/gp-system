# 工牌接诊 App（Android）—— 构建 & 安装

医美「工牌接诊 / 美丽陪伴」接诊录音系统的 **原生 Android WebView 壳 App**。
在 AIREC 蓝牙录音笔 demo 的基础上改造而成。

它做两件事：

1. **WebView 壳**：用一个全屏 WebView 加载线上接诊页（`START_URL`），操作者直接在网页里登录、看客人、操作；
2. **原生录音**：网页底部的「录音条」触发 App 端的**手机麦克风前台 Service 录音**（`PhoneMicService`），录完后用 WebView 里登录得到的 cookie，把音频上传到服务端 `/api/consultant/upload`，再回到网页的「未归档录音」列表里绑定客人。

> 关键身份信息
> - 源码包名：`com.airec.bledemo`（沿用 demo，不用改）
> - 应用 applicationId（装到手机上的真实包名）：`com.aibeautyfulwomen.gongpai`
> - 启动页：`ConsultantActivity`（WebView 壳）
> - versionName `2.0.0` / versionCode `1`

**进度**：
- 阶段一（已完成）：WebView + 手机麦克风前台 Service 录音 + cookie 上传。
- 阶段二（进行中）：蓝牙录音笔（AIREC）兜底，权限已在 Manifest 提前声明。

---

## ⭐ 第一件事：配置 START_URL（务必先确认）

App 启动后加载哪个网址，由一个常量决定。**这是装机前最需要先确认/修改的东西**——如果这个地址打不开或没在服务接诊页，App 启动就是一片白屏。

文件：`app/src/main/java/com/airec/bledemo/ConsultantActivity.java`
常量在文件顶部，大约第 45 行：

```java
private static final String START_URL = "https://gp.aibeautyfulwomen.com/consultant";
```

**怎么改**：
- 如果线上 `https://gp.aibeautyfulwomen.com/consultant` 能正常打开并且就是接诊页 → **不用改**，直接构建。
- 如果该 https 域名打不开 / 还没上线 / 没服务这个页面 → 改成一个**当前可访问的地址**，例如直接用服务器 IP：

  ```java
  private static final String START_URL = "http://<服务器IP>/gp/consultant";
  ```

  （`http://` 明文也可以，见下面「明文 HTTP」说明。）

**上传地址会自动跟随 START_URL**，无需单独配置：代码把 `START_URL` 末尾的 `/consultant` 去掉、拼成 `/api/consultant/upload`，兼容根路径部署和带 `/gp` 前缀的部署。所以只要把 `START_URL` 指对，登录和上传就都对了。

### 关于明文 HTTP

工程已允许明文 `http`（方便用服务器 IP 或未上 https 的域名测试）：
- `AndroidManifest.xml` 里 `android:usesCleartextTraffic="true"`；
- `app/src/main/res/xml/network_security_config.xml` 里 `cleartextTrafficPermitted="true"`。

生产环境建议把 `START_URL` 指向 https 域名；要收紧时，把 `network_security_config.xml` 里的 `cleartextTrafficPermitted` 改 `false` 并只放行特定域名即可。

---

## 环境要求

| 项 | 版本 |
| --- | --- |
| Android Gradle Plugin (AGP) | 8.13.1 |
| Gradle | 8.13（已带 wrapper，无需手动装） |
| JDK | 17（AGP 8.x 要求 JDK 17） |
| compileSdk / targetSdk | 34 |
| minSdk | 24（Android 7.0 及以上） |
| Android Studio | Hedgehog (2023.1) 或更高 |

- SDK aar 在 `app/libs/blesdk-release.aar`，已随工程提交，无需另外下载。
- **不需要 NDK / CMake**。Opus 解码改用 JNA 直接加载 `droidkit` 的 `libopus.so`。
  （原 demo 曾在 `gradle.properties` 里写死一台 Mac 的 NDK 路径，换机器必定构建失败，已删除。）

---

## 方式一：Android Studio 打开并构建 Debug APK（推荐，最省事）

1. 打开 Android Studio → **Open**，选择 `android_app` 这个目录（注意：不是它的上级目录 `gp-system`，就选 `android_app`）。
2. 首次打开会自动同步 Gradle、下载依赖，耐心等。如果提示缺 SDK / 缺 Build Tools，点提示里的链接装上即可；Studio 会自动生成 `local.properties` 指向你机器上的 Android SDK。
3. **先按上面「配置 START_URL」改好地址。**
4. 顶部菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
5. 构建完成后右下角弹窗点 **locate**，产物在：
   `app/build/outputs/apk/debug/app-debug.apk`
6. 也可以直接连上手机点绿色 ▶ **Run**，自动装到手机上跑。

> Debug 包用调试签名，**可以直接装机测试**，做内部验收完全够用。先用 debug 包跑通流程，签名 release 包等正式发布时再做。

---

## 方式二：命令行构建（CI / 无 Studio 环境）

工程自带 Gradle wrapper，命令行只需 JDK 17 和 Android SDK。

```bash
cd android_app
./gradlew assembleDebug
```

产物同样在 `app/build/outputs/apk/debug/app-debug.apk`。

命令行需要满足两个前提（Android Studio 会自动帮你搞定，命令行得自己配）：

1. **JAVA_HOME 指向 JDK 17**，例如：
   ```bash
   export JAVA_HOME=/path/to/jdk-17
   ```
2. **告诉 Gradle Android SDK 在哪**，二选一：
   - 设环境变量 `export ANDROID_HOME=/path/to/Android/sdk`，或
   - 在 `android_app/` 下建 `local.properties`（此文件**不要提交**）：
     ```properties
     sdk.dir=/path/to/Android/sdk
     ```

`android_app/` 下提供了 `build_manual.sh`，封装了上面这条命令并会在缺工具时给出提示，可直接：

```bash
cd android_app
./build_manual.sh
```

---

## 构建签名 Release APK

**现状：`app/build.gradle` 里目前没有任何 `signingConfig`。**
所以 `./gradlew assembleRelease` 只会产出一个**未签名**的包
（`app/build/outputs/apk/release/app-release-unsigned.apk`），**不能直接装机**。

测试阶段不必折腾签名，**用 debug 包装机就行**。需要发布正式版时，按下面任一方式补签名。

### 方案 A：在 build.gradle 里配 keystore（推荐）

1. 生成一个 keystore（只需做一次，妥善保管，别丢）：
   ```bash
   keytool -genkeypair -v -keystore gongpai-release.jks \
     -alias gongpai -keyalg RSA -keysize 2048 -validity 36500
   ```
2. 在 `android_app/` 下建 `keystore.properties`（**不要提交到 git**）：
   ```properties
   storeFile=/绝对路径/gongpai-release.jks
   storePassword=你的库密码
   keyAlias=gongpai
   keyPassword=你的key密码
   ```
3. 在 `app/build.gradle` 的 `android { ... }` 块里加入 signingConfig（顶部读取属性文件，`buildTypes.release` 里引用）。示例：
   ```gradle
   // android { 块之前：
   def keystorePropsFile = rootProject.file("keystore.properties")
   def keystoreProps = new Properties()
   if (keystorePropsFile.exists()) {
       keystoreProps.load(new FileInputStream(keystorePropsFile))
   }

   android {
       // ...
       signingConfigs {
           release {
               if (keystorePropsFile.exists()) {
                   storeFile file(keystoreProps['storeFile'])
                   storePassword keystoreProps['storePassword']
                   keyAlias keystoreProps['keyAlias']
                   keyPassword keystoreProps['keyPassword']
               }
           }
       }
       buildTypes {
           release {
               signingConfig signingConfigs.release   // ← 新增这一行
               minifyEnabled false
               proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
           }
       }
   }
   ```
4. 构建：
   ```bash
   ./gradlew assembleRelease
   ```
   产物：`app/build/outputs/apk/release/app-release.apk`（已签名，可装机/分发）。

> Android Studio 里也可以走 **Build → Generate Signed Bundle / APK**，按向导选 keystore，等价于上面的手动配置，不用改 build.gradle。

### 方案 B：先别管 release（测试期推荐）

直接用 **debug 包**装机自测，跑通全流程即可。等真要发布再回来做方案 A。

---

## 用 adb 安装到手机

1. 手机开 **开发者选项 → USB 调试**，连电脑，授权调试。
2. 安装（`-r` 表示覆盖安装、保留数据）：
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. 装好后桌面会出现 App（图标名见 `@string/app_name`）。首次启动会依次申请：录音、通知、（阶段二）蓝牙等权限，**全部允许**。

---

## ⚠️ 国产 ROM 必做：后台保活

小米 / 华为 / OPPO / vivo / 荣耀等国产系统会激进地杀后台进程。即使 App 用了**前台 Service + 常驻通知**录音，**如果不做下面的设置，锁屏或切后台后录音 Service 仍可能被系统杀掉**。

**这不是 App 的 bug，是国产系统的省电策略。** 装机后请在系统设置里对本 App 全部打开：

- **电池优化白名单 / 不优化 / 无限制**（允许后台高耗电）；
- **自启动**允许；
- **后台运行 / 锁屏后台运行 / 允许关联启动** 允许；
- 在「最近任务」里给 App **加锁**（下拉锁定图标），防止一键清理误杀。

不同品牌入口名称不同，但基本都在「设置 → 应用 → 本 App → 省电策略 / 权限」附近。

> 阶段二接入蓝牙录音笔后，录音由独立硬件完成，可彻底绕开手机系统杀进程的问题，作为最终兜底。

---

## ✅ 测试清单（装机后自测）

**正常流程**：

1. App 启动，WebView 显示接诊页 → **登录**（白屏请先回头核对 `START_URL`）。
2. 用网页**底部录音条**开始录音，对着手机说几句话。
3. 点**结束**录音。
4. 观察是否**自动上传**（录音条会显示「上传中…」）。
5. 上传成功后，到网页的「**未归档录音**」列表里能看到这条录音。
6. 给它**绑定客人**，确认归档成功。

**中断 / 保活测试**（验证前台 Service 是否真的扛得住）：

- **锁屏**：录音中按电源键锁屏，等 1–2 分钟再解锁结束 —— 录音应连续、不断。
- **切到别的 App**：录音中切到微信 / 浏览器停留几分钟再切回 —— 录音应继续。
- **从最近任务划掉**：录音中划掉本 App —— 观察通知栏录音 Service 是否还在 / 录音是否被杀。
  - 没做国产 ROM 保活设置时，这一步很可能被杀，**属预期**；
  - 做了保活设置后应能扛住；
  - 蓝牙录音笔（阶段二）是这一项的终极兜底。

把上面每一步的实际表现记录下来，反馈给开发。

---

## 目录速查

| 路径 | 说明 |
| --- | --- |
| `app/src/main/java/com/airec/bledemo/ConsultantActivity.java` | 启动页 WebView 壳，**START_URL 在这** |
| `app/src/main/java/com/airec/bledemo/recording/PhoneMicService.java` | 手机麦克风前台录音 Service |
| `app/src/main/AndroidManifest.xml` | 权限、Service、启动页声明 |
| `app/src/main/res/xml/network_security_config.xml` | 明文 HTTP 放行配置 |
| `app/build.gradle` | applicationId、SDK 版本、依赖（signingConfig 待补） |
| `app/libs/blesdk-release.aar` | AIREC 蓝牙录音笔 SDK |
| `build_manual.sh` | 命令行构建脚本 |
