# 版本更新与发布

轻记从 0.1.1 开始支持设置页更新。无 root 的 Android 需要用户确认安装，启动自动检查不等于后台静默安装。下载使用独立线程，不阻塞记账处理线程；进程被杀时可能中断，未完成的包不会交给安装器。

## 发布要求

1. 在 `app/build.gradle` 增大 versionCode，修改 versionName；Release tag 使用 `v` + versionName。
2. 使用与已发布版本相同的签名密钥编译。现有签名证书 SHA-256 为 `57a1dcdb9b45ff5f2426b0e2786641195eebe5979db1cf9d6ffe5013a3dda833`。私钥不在仓库中，必须另外妥善保管；不要重新生成后冒充可覆盖升级版本。
3. 执行 `bash scripts/test-core.sh`、完整编译、lint、APK 签名与对齐检查。确认实际 APK 的包名、versionCode、versionName、minSdk 正确。
4. 将产物命名为 `qingji-版本号-debug.apk`，从实际 APK 生成 `update.json`：

```bash
python3 scripts/make-update-manifest.py \
  --apk /产物目录/qingji-0.1.1-debug.apk \
  --output /产物目录/update.json \
  --aapt2 "$ANDROID_HOME/build-tools/35.0.0/aapt2"
```

5. 将 APK 和 `update.json` 作为**同一个 GitHub Release 的附件**上传，发布页仓库固定为 `Iloveshe/qingji-android`。Release 不可保持 Draft；测试版可标记 Prerelease。发布流程必须一起提供更新清单，只有 APK 的新版不会被 App 内更新器发现。
6. 当前发布工作流通过单独 `release-assets/v版本` 分支传送已校验产物；发布前校验 `SHA256SUMS.txt`、更新清单、附件大小及 GitHub 返回的哈希，完成后公开 Release。修改默认 tag/ref 或手动触发时，应确认引用对应版本。

## 清单格式

`schemaVersion` 为 1；字段包括 `applicationId`、整数 `versionCode`、`versionName`、`minSdk`、`apkName`、`apkSize` 和 APK 的 `sha256`。文件名与 tag 只允许有限字符，不接受任意下载 URL。更新器把下载地址固定到本仓库，只允许 HTTPS GitHub 及其指定附件 CDN 重定向，附件上限 128 MiB。

GitHub API 无需在 App 中配置令牌。网络不可达或访问受限时，设置显示失败并保留手动重试和发布页入口。最近检查时间指成功读取并校验版本列表的时间。启动检查频率按最近尝试时间限制。

## 实机检查

先用 0.1.0 建立一条测试账单，再手动覆盖安装 0.1.1，确认账本和开关保留。之后需要使用同一签名的更高 versionCode 测试版本，检查自动提示、测试版渠道、断网重试、下载损坏拒绝、未知来源授权返回、取消安装和成功安装后的数据保留。不要为验证而把虚构的更高版本发布到正式仓库。
