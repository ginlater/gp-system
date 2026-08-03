# 工牌接诊 Android 壳 App

这是一个原生 Android WebView 壳，默认打开：

`https://gp.aibeautyfulwomen.com/consultant`

## 可改配置

- App 名称：`app/src/main/res/values/strings.xml`
- 包名：`app/build.gradle` 的 `applicationId` 和 `namespace`
- 启动地址：`MainActivity.java` 的 `START_URL`

## 构建

```bash
./gradlew assembleRelease
```

release 签名读取根目录的 `keystore.properties`：

```properties
storeFile=release.keystore
storePassword=...
keyAlias=gongpai
keyPassword=...
```
