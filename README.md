# 轻记 · Android 自动记账第一版

为 **Redmi Note 12 Turbo / Android 16 / HyperOS 3 / 无 root** 场景编写的原生 Android 项目。默认本地处理，无登录、无服务器。最低 API 26，编译及目标 API 36。

**交付状态：已安装 Android SDK，完成 Android 编译及 lint，生成可安装的 Debug APK。37 项核心测试通过，APK 签名与 16 KB ZIP 对齐检查通过。** 尚未在你的 Redmi 手机上实机验证，不能保证所有银行格式或后台场景都已覆盖。

源码仓库：https://github.com/Iloveshe/qingji-android

安装包发布页：https://github.com/Iloveshe/qingji-android/releases/tag/v0.1.0

## 第一版实现了什么

| 来源 / 功能 | 实现方式 | 使用条件与边界 |
| --- | --- | --- |
| 微信、支付宝、云闪付、银行通知 | `NotificationListenerService` | 授予通知读取；通知自身必须包含金额或其他可用交易字段。分组摘要不入账。 |
| 新到达的交易短信 | `RECEIVE_SMS` 广播，拼接分段短信 | 开启短信采集并授予权限；默认仅允许 955、953、106 前缀，可自行修改；不读取历史短信，忽略验证码。 |
| 可见支付页面 | 无障碍读取文本 | 开启开关并授予权限；只读取勾选 App，跳过密码和可编辑节点，不点击、不操作支付。只能在页面显示时获取。 |
| 截图分享 / 单张导入 | Android 分享入口、文件选择器、打包的中文 ML Kit OCR | 图片在内存识别，不保存副本。缺少时间或商户时待确认。 |
| 新截图自动识别 | 系统 `JobScheduler` 相册内容触发 | 可选图片权限；只查询开启后新增的截屏目录，不扫描普通照片。系统可能延迟任务。 |
| 临时屏幕识别 | `MediaProjection` 前台服务 + OCR | 每次由用户授权；需要无障碍判断前台支付 App；每 4 秒最多一帧、常驻通知、锁屏停止。银行安全页面可能是黑屏。 |
| 本地自动入账 | 金额、交易方向、日期、商户与类型规则 | 当前识别算法为**本地规则及 OCR**，未集成本地大语言模型。明确交易自动入账，歧义交易待确认。 |
| 可选云端模型 | HTTPS Chat Completions 分类接口 | 默认关闭；填写接口、模型、API Key 后，只为新待确认交易建议分类。不会修改金额、日期或自动确认交易。 |
| 账单与核对 | 总览、账单筛选、待确认、证据查看、编辑、忽略、人工合并 | 转账不计收支；退款冲减支出；已忽略交易不会被同一事件恢复。 |
| 数据导出 | Android 保存文件选择器，UTF-8 CSV | 导出含已确认、待确认和已忽略状态；无截图、短信全文和模型密钥。导出文件由你选择保存位置。 |

系统通知仅从勾选的金融 App 获取；不会记录整个系统通知流。没有发出通知的交易、隐藏金额、无法读取的安全页面或未知交易格式都可能漏记。现有系统录屏文件 / 视频导入不在第一版中；实现的是授权期间的实时画面 OCR。

## 转账与去重

你举的“先转到云闪付的一张本人银行卡，再消费”会保留资金移动和实际消费两条记录：

```
转入本人银行卡 500 元 → 内部转账 → 不计入收入 / 支出
咖啡消费 38 元        → 支出     → 支出 +38 元
银行短信消费 38 元    → 同笔消费的证据 → 不重复增加支出
```

不是将 500 元与 38 元强行合并。转账需要有本人账户、充值 / 提现 / 信用卡还款等明确含义，或能匹配你填写的两个本人账户尾号。普通“转账成功”不自动断定为本人账户互转，而是待确认。

- 同一来源事件重复投递直接忽略；指纹只保存哈希。
- 同一订单、类型、金额和币种可合并；同一订单金额冲突进入待确认。
- 订单号按来源 / 订单字段分范围，商户订单号不能跨不同商户复用。
- 不同来源在短时间内商户与金额一致可合并；没有明确日期的可视页面只能凭订单号强匹配。
- **仅凭金额相同不会自动合并。** 商户缺失时可能重复的记录进入待确认，暂不计入统计。
- 同一来源两个独立消费、不同订单、不同商户、不同钱包的独立消费都保留。
- 退款独立记录，冲减净支出，不与原支付互相吞掉。
- 余额不作为交易金额，历史截图使用其交易日期，人民币统计不混入外币。
- 跨来源的商户与时间匹配是启发式规则，仍可能误判。可在账单详情查看依据；建议先用小额真实交易核对。

## 编译与安装

### Android Studio（建议）

1. 安装支持 Android Gradle Plugin 8.11.1 的 Android Studio，使用 JDK 17 或 21。
2. 在 SDK Manager 安装 Android SDK Platform **36** 和 Android SDK Build-Tools **35.0.0**。
3. 打开本项目。首次同步需要访问 Google Maven、Maven Central 和 Gradle 下载站。
4. 本项目提供下载校验版 `gradlew` shell 引导脚本，**尚未附标准 Gradle wrapper JAR**。Android Studio 如提示 wrapper 不完整，可以选择已安装的 Gradle **8.13**，或在终端先运行 `./gradlew wrapper --gradle-version 8.13` 生成标准 wrapper。
5. 构建 Debug APK：`./gradlew :app:assembleDebug`（macOS / Linux），或使用下方 Windows 脚本。
6. 安装 `app/build/outputs/apk/debug/app-debug.apk`。这是调试包，适合你本人试用。公开分发前需使用你自己的长期签名密钥。

`local.properties` 示例（不要提交本机路径）：

```properties
sdk.dir=/你的/Android/Sdk
```

命令行：

```bash
chmod +x gradlew scripts/*.sh
./gradlew :app:assembleDebug :app:lintDebug
# 可选，USB 调试连接手机后
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows PowerShell：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-windows.ps1
```

本地规则测试无须 Android SDK：

```bash
bash scripts/test-core.sh
bash scripts/check-source.sh
```

## 在 Redmi 上开启

打开轻记 → 设置，按需要逐项开启：

1. **通知读取**：基础来源，建议最先开启。
2. **交易短信**：允许接收新短信。侧载 App 可能遇到系统权限限制，无须将轻记设为默认短信应用。
3. **支付页面识别**：系统无障碍权限；侧载 APK 如出现“受限设置”，到应用信息中允许受限设置后再尝试。
4. **本人银行卡尾号**：只填你自己的卡尾号；多个尾号用逗号分隔。完整卡号不需要填。
5. **勾选实际使用的 App**。预设部分银行包名，不保证适配全部银行版本，支持补充包名。
6. **后台**：应用信息 → 电量与性能 → 无限制；开启后台自启动；最近任务中锁定轻记。不同 HyperOS 构建的菜单名称可能有差异。
7. 截图目录扫描与临时屏幕识别按需要开启。屏幕授权不能永久保持，录屏也不会在开机后自行重启。

“强制停止”会阻止后台接收，需要重新打开轻记。通知页面的“服务已连接”会在系统完成绑定后显示；可以点击“重新连接通知采集”。系统升级、重启、后台限制可能需要复查权限。

## 隐私

- 交易数据库在应用私有目录，依赖 Android 文件加密与应用沙箱，**没有额外的 SQLCipher 数据库加密**。
- 禁用应用系统备份。OTP、密码框、非交易消息不入库；不保存完整短信 / 页面文本，只保存提取的金额、商户、类型、时间、账户尾号和判断摘要，以及事件哈希。
- 截图、屏幕图像只在内存处理，不生成录屏文件。
- 云端分类默认关闭，开启后仅向你填写的 HTTPS 接口发送金额、币种、商户、类型、本地分类。模型 API Key 使用 Android Keystore 的 AES-GCM 加密保存，不写日志、不包含在导出中。
- 默认功能不调用云端模型。本项目包含第三方本地 OCR SDK，其初始化行为依赖 SDK；这是“交易识别在本地”，不是经过网络抓包验证的“绝对零网络请求”。
- 可导出 CSV、清空本地账单、清除模型密钥、撤销系统权限或卸载。

## 验证与后续校准

见 [docs/VALIDATION.md](docs/VALIDATION.md) 和 [docs/DEVICE-CHECKLIST.md](docs/DEVICE-CHECKLIST.md)。

Android 完整编译与 lint 已通过，Redmi 实机检查仍待进行。微信、支付宝、云闪付和银行文案会变化；要扩大自动覆盖率，需要真实的去敏交易样例来增加解析规则。金额和转账归属有歧义时不能靠开放更多权限消除歧义。

本次环境中的 SDK 位于 `/workspace/toolchains/android-sdk`，JDK 位于 `/workspace/toolchains/jdk-17`。安装与构建脚本为 `scripts/setup-android.py`；GitHub Actions 备用构建方式见 [docs/GITHUB-BUILD.md](docs/GITHUB-BUILD.md)。工作流需要手动触发，上传仓库本身不会运行云端构建。
