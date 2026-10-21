# 版本更新与发布

轻记从 0.1.1 开始支持设置页更新。无 root 的 Android 需要用户确认安装，启动自动检查不等于后台静默安装。下载使用独立线程，不阻塞记账处理线程；进程被杀时可能中断，未完成的包不会交给安装器。

## 0.1.4 起的新签名与并存安装

0.1.4 使用应用标识 `com.qingji.app.next`，名称为“轻记新版”，与 0.1.0–0.1.3 的 `com.qingji.app` 独立安装。旧签名私钥未能取得，因此不能覆盖升级；按用户选择保留旧应用及账本，新版从空账本开始。首次需从发布页下载，重新授予通知 / 无障碍等权限。旧账本可在旧版查看或导出 CSV；当前不提供 CSV 恢复。

新签名证书 SHA-256：`f8e68ffb85614a086aa9716537ad8fa555d495033d1d8c39e31810c5e36e64ce`。后续版本必须继续使用此密钥。私钥与口令不进入源码或公开发布附件，保存在本地忽略目录及仓库加密 Actions Secrets 中（`QINGJI_SIGNING_KEYSTORE_BASE64`、`QINGJI_SIGNING_PASSWORD`），别名 `qingji-release`。

新版只读取 `update-next.json`；旧版只读取 `update.json`。**新渠道 Release 不上传 `update.json`**，避免旧客户端将不同应用标识的包当成覆盖更新或因元数据不匹配而报错。

## 发布要求

1. 在 `app/build.gradle` 增大 versionCode，修改 versionName；Release tag 使用 `v` + versionName。
2. 使用上面的新版固定签名密钥签名，不使用临时生成的 Android debug.keystore。历史 0.1.0–0.1.3 证书为 `57a1dcdb9b45ff5f2426b0e2786641195eebe5979db1cf9d6ffe5013a3dda833`，仅用于识别旧渠道。
3. 执行 `bash scripts/test-core.sh`、`:app:testDebugUnitTest`、完整编译、lint、APK 签名与对齐检查。确认实际 APK 的包名为 `com.qingji.app.next`，版本及 minSdk 正确。
4. 将产物命名为 `qingji-版本号-debug.apk`，从实际 APK 生成 `update-next.json`：

```bash
python3 scripts/make-update-manifest.py \
  --apk /产物目录/qingji-0.1.4-debug.apk \
  --output /产物目录/update-next.json \
  --aapt2 "$ANDROID_HOME/build-tools/35.0.0/aapt2"
```

5. 将 APK 和 `update.json` 作为**同一个 GitHub Release 的附件**上传，发布页仓库固定为 `Iloveshe/qingji-android`。Release 不可保持 Draft；测试版可标记 Prerelease。发布流程必须一起提供更新清单，只有 APK 的新版不会被 App 内更新器发现。
6. 先创建 Draft，上传并核对 `SHA256SUMS.txt`、更新清单、附件大小及 GitHub 返回的哈希，再公开 Release。可以使用 GitHub CLI；备用发布工作流需先将已签名产物放入对应的 `release-assets/v版本` 分支，再手动触发。工作流配置提交不会自动发布。

## 清单格式

`schemaVersion` 为 1；字段包括 `applicationId`、整数 `versionCode`、`versionName`、`minSdk`、`apkName`、`apkSize` 和 APK 的 `sha256`。文件名与 tag 只允许有限字符，不接受任意下载 URL。更新器把下载地址固定到本仓库，只允许 HTTPS GitHub 及其指定附件 CDN 重定向，附件上限 128 MiB。

GitHub API 无需在 App 中配置令牌。网络不可达或访问受限时，设置显示失败并保留手动重试和发布页入口。最近检查时间指成功读取并校验版本列表的时间。启动检查频率按最近尝试时间限制。

## 实机检查

首次并存安装需确认“轻记”和“轻记新版”均可打开，旧账本不变，新版为空账本，授权列表显示“轻记新版”。后续在新版渠道使用同一签名的更高 versionCode，检查自动提示、测试版渠道、断网重试、下载损坏拒绝、未知来源授权返回、取消安装和成功安装后的数据保留。不要为验证而把虚构的更高版本发布到正式仓库。
