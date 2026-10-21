# 在 GitHub Actions 上生成 APK

本地网络无法取得工具链时，可以将项目提交到**你自己的私有 GitHub 仓库**，使用 GitHub 的 Ubuntu runner 安装 SDK 并编译。工作流位于 `.github/workflows/android-apk.yml`；仅在你手动触发后运行。首版下载产物由已验证的本地构建提供。

1. 将 `qingji` 目录内的内容作为仓库根目录上传，包括 `.github/workflows/android-apk.yml`。
2. 打开仓库的 **Actions → Build Android APK → Run workflow**。
3. 等待 `Test transaction rules`、`Test database migration` 和 `Test ledger storage, build and lint` 完成。
4. 运行成功后，在页面底部 **Artifacts** 下载 `qingji-debug-apk`，解压得到 `app-debug.apk`。
5. 将 APK 发送到 Redmi 手机上安装，按 README 授予所需权限。

工作流会安装 JDK 17、Android API 36、Build Tools 35.0.0 和 Gradle 8.13，运行核心测试、数据库集成测试、迁移检查、完整构建与 lint；失败时可以下载 lint 和测试报告。任务只在手动点击后执行，权限仅为读取仓库，不会自动发布 Release 或上传账单。

这是个人测试用 Debug APK。GitHub runner 每次运行可能生成不同的调试签名密钥，后续版本更新需要保持同一签名；正式长期使用前应配置由你保管的固定签名密钥。切勿把模型 API Key、交易数据或签名私钥提交进仓库。

仓库可见性以 GitHub 页面为准。公开仓库的 Release 可直接下载；私有仓库需要先登录对应的 GitHub 账号，再访问 Releases 页面。
