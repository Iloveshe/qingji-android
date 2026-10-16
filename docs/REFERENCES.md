# MVP 参考来源与实现范围

本轮参考项目：[AutoAccountingOrg/AutoAccounting](https://github.com/AutoAccountingOrg/AutoAccounting)。查看时的主分支参考提交为 `34b38f82ca9ad6d7905c715c66891977f8809c88`；主仓库 LICENSE 标注 GPL-3.0。参考了以下设计概念：

- [页面签名管理](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/net/ankio/auto/service/ocr/PageSignatureManager.kt)：记住页面特征，供自动识别触发使用。
- [无障碍页面事件](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt)：跟踪前台应用和页面，区分窗口变化与内容变化。
- [重复账单检测](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/server/src/main/java/org/ezbook/server/tools/DuplicateDetector.kt)：将采集、解析、匹配和归账分层，便于排查问题。

本版没有复制上述文件、接入上游代码 / SDK / 规则包，也没有把上游作为编译依赖。这里记录参考来源与设计比较，便于后续审查。若以后直接复用代码，应另行核实对应代码许可证并履行其要求。

## 本版独立实现的 MVP

1. 页面记忆由 `PaymentPageSignature`、`Config` 和页面服务完成。签名绑定具体包名、组件、节点结构和有限固定付款标签，不允许空组件或仅包名匹配，不以金额、商户、订单号等变化内容生成记忆。结构变化需重学，记忆最多 24 项。
2. 悬浮窗点按发起单次读取，长按尝试保存当前付款页，右侧打开账本。只能操作已勾选来源，不点击、输入或操作第三方支付页面。手动重试仅解除同页三次尝试上限，仍保留 4 秒间隔与所有权限 / 锁屏 / 输入保护。
3. `CaptureDiagnostics` 仅保存 32 条固定解析原因和归账结果，使用本地应用私有配置存储；不保存交易正文、图片、金额、商户、卡号或模型密钥。可复制与导出。独立的远程诊断配置默认关闭，主动配置自己的 HTTPS 接口并开启后只发送白名单枚举及字段是否齐全，不发送任意日志正文。
4. 保留原有整数分币、订单范围、商户 / 时间 / 来源匹配和本人转账规则，增加已知支付账户冲突保护。没有直接采用跨渠道同金额自动合并策略。

## 没有纳入本轮

Shizuku、Xposed、Hook 支付 App、云端交易解析、自定义脚本执行、规则市场、账户后台同步。本轮以 0.1.3 可安装版本验证闭环，默认本地处理保持不变。截图证据独立实现并把数据库版本迁移到 2，保留账单数据。
