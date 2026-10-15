# 验证记录

2026-10-15，构建 0.1.2（无通知支付窗口 OCR、悬浮状态、云闪付格式）。

## 已完成

- JDK 17、Android API 36、Build Tools 35.0.0、Gradle 8.13。
- `:app:assembleDebug :app:lintDebug`：BUILD SUCCESSFUL；lint 0 错误、2 个原有非阻塞警告（原生 Switch 和固定 AndroidX Core 版本）。原生悬浮组件不使用 AppCompat 的 inflater / tint，相关自定义 View 检查仅在该类上注明适用原因。
- `bash scripts/test-core.sh`：52 个记账测试 + 31 个更新测试 + 18 个页面采集策略测试，共 101 项通过。
- `bash scripts/check-source.sh`：19 个 Java、9 个 XML 语法解析通过，完整 Android 类型检查由 Gradle 编译完成。
- `apksigner verify --verbose --print-certs`：v2 签名通过；与 0.1.0、0.1.1 的证书一致。
- `zipalign -c -P 16 4`：通过。APK ZIP 完整性通过；OCR 模型与原生库和 0.1.1 字节一致，保留已验证的 ARM64 16 KB ELF LOAD 对齐。
- APK 内已编译的无障碍配置包含 `canTakeScreenshot=true`，Android 14+ 窗口截图调用有系统版本保护。
- 包名 `com.qingji.app`，versionName 0.1.2 / versionCode 3，minSdk 26 / targetSdk 36。

## 产物

- `qingji-0.1.2-debug.apk`，47,627,841 字节。
- SHA-256：`0f7f459a3d6930054b60d2bbbac591984d5ccd88d9c79a80ca6cf7af498fa0e6`。
- 证书 SHA-256：`57a1dcdb9b45ff5f2426b0e2786641195eebe5979db1cf9d6ffe5013a3dda833`。

## 测试覆盖

保留此前 37 项去重、转账、退款、金额精度、时间、广告、验证码与外币测试；增加云闪付结果页负号金额、商户全称、商户单号去重、扣款与失败排除、交易方向缺失、收款方标签歧义、自动 OCR 历史日期与缺日期待确认等 15 项。

页面策略验证关闭开关、未选 App、锁屏、输入页面、老系统、已解析交易不截图、普通聊天不截图，以及跨窗口节流、同页三次上限和新页重新识别。更新策略验证版本选择、渠道、URL 信任边界、包体积和哈希元数据。

## 仍待实机验证

Redmi Note 12 Turbo / Android 16 / HyperOS 3 的无障碍重新授权、窗口截图 / 回调 / 锁屏切换、云闪付实际页面结构、悬浮窗权限 / 拖动 / 显隐、覆盖安装数据保留、后台存活与真实支付解析覆盖率。SDK 编译和纯 Java 测试不能替代这些验证。按 DEVICE-CHECKLIST.md 操作。
