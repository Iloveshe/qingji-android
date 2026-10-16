# 验证记录

2026-10-16，构建 0.1.3（OCR 错账保护、采集截图证据、脱敏诊断与 MVP 改进）。

## 已完成

- JDK 17、Android API 36、Build Tools 35.0.0、Gradle 8.13。
- `:app:assembleDebug :app:lintDebug`：BUILD SUCCESSFUL；lint 0 错误、2 个原有警告（原生 Switch 和固定 AndroidX Core 版本）。
- `bash scripts/test-core.sh`：63 个记账 + 31 个更新 + 18 个页面策略 + 22 个页面记忆 + 33 个 OCR 可靠性 / 诊断白名单 / 截图加密测试，共 167 项通过。
- `python3 tests/test-storage-migration.py`：使用实际迁移 SQL 验证 v1 到 v2 不丢账单、不改变金额 / 时间 / 人工修改；仅未经核对的 OCR 单来源确认记录退回待确认，混合来源和忽略记录保留。验证历史缺图说明和新图引用写入。此项在主机 SQLite 执行，Android 覆盖升级仍待实机验证。
- `bash scripts/check-source.sh`：28 个 Java、9 个 XML 语法解析通过，完整 Android 类型检查由 Gradle 完成。
- `apksigner verify --verbose --print-certs`：v2 签名通过；与已发布版本证书一致。
- `zipalign -c -P 16 4`：通过；ZIP 完整性通过；OCR 模型与原生库和 0.1.2 字节一致，保留既有 ARM64 16 KB ELF LOAD 对齐。
- APK 无障碍配置包含 `canTakeScreenshot=true`；已编译诊断任务与截图查看功能。
- 包名 `com.qingji.app`，versionName 0.1.3 / versionCode 4，minSdk 26 / targetSdk 36。

## 产物

- `qingji-0.1.3-debug.apk`，47,734,780 字节。
- SHA-256：`0262a701c2745af297211ca75c73c971d115a070412e22ede74395fa34b13db8`。
- 证书 SHA-256：`57a1dcdb9b45ff5f2426b0e2786641195eebe5979db1cf9d6ffe5013a3dda833`。

## 测试覆盖与局限

新增商品价格 5999、聊天语句、裸整数、卡尾号、时间不能冒充金额，明确货币金额仍保留为待确认，59.99 精确分币，OCR 不增加未确认支出，以及银行短信继续正常入账。真实 5999 不做金额黑名单。

诊断检查任意原文、金额、卡号不会透传为来源 / 阶段 / 规则字段，HTTPS 地址不接受明文、URL 凭据、查询和片段。证据 AES-GCM 验证随机 IV、明文不落盘、错误密钥、篡改、截断和跨交易重绑被拒绝。Android Keystore 实际设备行为、上传 TLS / 周期任务仍需实机验证。

尚未取得原误记画面，未复现具体字体 / 布局造成的 5999 误读。新增保护降低错账风险，不代表已验证所有支付格式。需在 Redmi Note 12 Turbo / Android 16 / HyperOS 3 验证窗口截图、证据保存 / 缩放 / 清除、权限切换、后台存活、漏记路径、旧数据覆盖升级。没有部署远程接收端，也未声称手机端到端上传已验证。
