# 验证记录

2026-10-15，完成 Android 工具链安装和第一版 APK 构建。

## 已完成

- JDK 17、Android API 36、Build Tools 35.0.0、platform-tools 和 Gradle 8.13 已安装。
- `:app:assembleDebug :app:lintDebug`：**BUILD SUCCESSFUL**。
- Android lint：**0 个错误、2 个非阻塞警告**，分别建议使用兼容版 Switch 组件、更新 AndroidX Core 版本。当前使用原生界面和已验证可构建的固定依赖版本。
- `bash scripts/test-core.sh`：**37 个交易核心测试通过**。
- `bash scripts/check-source.sh`：15 个 Java 文件语法解析、8 个 XML 文件解析通过。此检查为辅助检查，完整 Android 编译已另外完成。
- `apksigner verify --verbose --print-certs`：**APK 签名验证通过（v2）**。
- `zipalign -c -P 16 4`：通过。
- APK ZIP 完整性检查通过；包含中文 OCR 的本地资源和 ARM64 原生库。
- ARM64 OCR 原生库的 ELF LOAD 段对齐均为 **16384 字节**。
- 包名 `com.qingji.app`，版本 `0.1.0` / versionCode 1，目标 Android API 36。

## 交付文件

- APK：`qingji-0.1.0-debug.apk`
- 大小：50,834,634 字节，约 48.5 MiB。
- SHA-256：`8a5f11b82e09e3667f704609bdea7d0aaec80d4f81ff2e4a5d314f7891039d3e`
- 调试签名证书 SHA-256：`57a1dcdb9b45ff5f2426b0e2786641195eebe5979db1cf9d6ffe5013a3dda833`

## 核心测试覆盖

本人转账与支出统计、余额误识别、银行短信与支付通知合并、重复投递、两笔同额消费、不同商户 / 订单 / 钱包、无日期截图、历史日期、非法日期、商户订单号范围、本人转账目标关联、无关收入、普通个人转账、退款抵消、失败 / 验证码 / 月汇总排除、广告与待付款排除、还款提醒排除、未确认退款、分币精度、千分位、多金额截图、同订单金额冲突、已忽略记录重放、多候选匹配、长号码隐藏和外币隔离。

## 仍需实机验证

- Redmi Note 12 Turbo 的 UI 布局、系统设置入口和权限弹窗；
- Android 16 MediaProjection 生命周期与 HyperOS 后台自启动、后台存活；
- 真实微信、支付宝、云闪付及银行通知、短信、OCR 的覆盖率和长期耗电；
- SQLite 在进程终止和重启后的行为；
- 可选云端分类接口与密钥生命周期；
- 第三方 OCR SDK 的网络行为。

编译、静态检查与签名验证不等于实机测试。请按 [DEVICE-CHECKLIST.md](DEVICE-CHECKLIST.md) 验证，尤其是转账归属、误合并和后台被终止后的采集。
