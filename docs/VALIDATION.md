# 验证记录

## 2026-10-21：0.1.4 并存版本发布验证

- 应用标识 `com.qingji.app.next`，显示名称“轻记新版”，versionName **0.1.4** / versionCode **5**，minSdk 26 / targetSdk 36。使用独立的文件共享标识、私有数据目录及 `update-next.json` 更新渠道，旧版保留原标识和 `update.json` 渠道。
- 原有和新增核心检查共 **197 项**通过；**10 项数据库集成测试 + 1 项应用标识 / 启动入口 / FileProvider 并存检查**通过。旧数据库迁移检查通过。
- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 成功；lint 0 错误、2 个原有警告。环境与下方核心修复验证相同。
- 最终 APK：`qingji-0.1.4-debug.apk`，47,676,701 字节；SHA-256 为 `1e43c0a6065d17cc2c84c2fbed7d288be07cff17574d8941a823519a7cf7b8de`。
- `apksigner verify --verbose --print-certs` 通过 v2 / v3 签名；固定证书 SHA-256 为 `f8e68ffb85614a086aa9716537ad8fa555d495033d1d8c39e31810c5e36e64ce`。`zipalign -c -P 16 4` 通过。更新清单从最终签名 APK 生成。
- 不包含旧渠道的 `update.json`，避免旧版将新版当作可覆盖更新。首次从发布页手动安装，新版从空账本开始；没有卸载或迁移用户旧账本。
- 上述为构建、宿主测试和安装包校验；尚未在 Redmi 上验证并存安装、OCR 实际识别及权限流程。

## 2026-10-21：核心记账修复初次验证

基于 `3155426` 修复退款金额、收支方向、转账配对、部分退款去重，以及待确认 OCR 覆盖已确认账目。初次修复验证使用版本号 0.1.3 / 4，数据库结构保持版本 2；随后准备的 0.1.4 发布验证见上方记录。

### 验证结果

- `bash scripts/test-core.sh`：原有 167 项和新增 30 项核心回归检查全部通过，共 **197 项**。
- `:app:testDebugUnitTest`：**10 项集成测试通过，0 失败、0 跳过**。使用 Robolectric 4.17 / API 34 / `SQLiteMode.NATIVE`，调用生产 `LedgerStore`，验证关闭并重新打开数据库后的持久化数据与统计。测试环境为 macOS / JDK 17，编译使用 Android API 36、Build Tools 35.0.0、Gradle 8.14.3。
- `:app:assembleDebug :app:lintDebug`：BUILD SUCCESSFUL；lint 0 错误，2 个原有警告（原生 Switch 和 AndroidX Core 版本）。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，为本机调试构建，未执行发布签名一致性验证。
- `python3 tests/test-storage-migration.py`：升级保留账单、人工修改、混合来源、忽略状态和截图引用的检查通过。
- `bash scripts/check-source.sh`：28 个 Java、9 个 XML 语法解析通过。
- GitHub 手动构建流程已加入数据库集成测试、迁移检查及测试报告产物；本次未触发远端工作流。

### OCR 写库问题的修复前后证据

先保留旧的 `LedgerStore` 合并逻辑运行集成测试，以下两项均失败：

| 场景 | 修复前写库结果 | 修复后结果 |
| --- | --- | --- |
| 已确认 38 元支付；同订单待确认 OCR 把年份识别为 2025 | 时间由 `1792562400000`（2026-10-21 14:00）变为 `1761026400000`（2025-10-21 14:00），原确认状态保留 | 保留 2026 年日期与确认状态；2026 年 10 月支出为 38 元，2025 年 10 月为 0 |
| 已确认支付缺少商家；同订单 OCR 提供错误商家 | 商家被写成“错误咖啡店” | 保留原商家与分类；新证据仍附加 |

以上失败发生在关闭并重新打开数据库后的读取断言中。修复后的日期保护分别覆盖截图导入、自动页面 OCR、屏幕识别三种来源，并验证截图引用和证据数量。金额冲突仍单独待确认；可信文字来源可补充缺失时间；人工编辑与忽略状态保留。已有待确认记录不会因另一来源重复出现而自动确认。

测试代码：[核心回归](../tests/AccountingRegressionTests.java)、[数据库集成](../app/src/test/java/com/qingji/app/LedgerStoreTest.java)。退款测试同时验证消费 100 元、两次各退 20 元后净支出为 60 元，重放同次退款不会重复冲减；转账测试验证后续独立到账 500 元保留为收入。

上述验证使用合成交易文字和主机原生 SQLite，确认的是解析、合并及持久化行为。尚未进行 Redmi 真机 OCR 字体识别、截图采集和覆盖安装验证，也未读取或自动修复用户历史账本。

## 0.1.3 已发布版本的历史验证

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
