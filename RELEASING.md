# 发版说明

从 v0.30 起，APK 由 GitHub Actions 构建、签名并发布，不再手工提交 `assembleDebug` 产物。

## 发版模型

```
推 tag v0.30 / Actions 手动运行
        │
        ▼
GitHub Actions (JDK 17 + Android SDK 34)
  ├─ assembleRelease（注入 versionName/versionCode）
  ├─ 用 ANDROID_KEYSTORE_BASE64 签名
  ├─ 生成 litreader-v0.30.apk + latest.json（含 SHA-256）
  ├─ force-push 到 release 分支（只保留最新版）
  ├─ purge jsDelivr 缓存
  ├─ 可选：把 APK/latest.json 提交到 master（旧版过渡）
  └─ 创建/更新 GitHub Release（APK 附件）
        │
        ▼
App 更新检查：release 分支 latest.json
  raw → cdn.jsdelivr → fastly.jsdelivr → gcore.jsdelivr → master（迁移期兜底）
APK 下载：同顺序 + GitHub Release 附件兜底；安装前校验 SHA-256
```

`master` 根目录只保留一个 **v0.30 过渡包** 和冻结在 0.30 的 `latest.json`，供 v0.29 及更早版本升级；
v0.30 之后的版本只进 `release` 分支与 GitHub Releases。

## 一次性配置

CI 必须使用与历史版本相同的 debug 证书（SHA-256 `E4:36:A5:86:…:4F:06`），否则老用户无法覆盖安装。

在能访问本机 `~/.android/debug.keystore` 的机器上：

```bash
gh auth login
./scripts/setup-github-secrets.sh
```

脚本会写入 4 个仓库 secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`（debug keystore 默认 `android`）
- `ANDROID_KEY_ALIAS`（默认 `androiddebugkey`）
- `ANDROID_KEY_PASSWORD`（默认 `android`）

也可以在 GitHub 仓库 Settings → Secrets and variables → Actions 手动添加。
脚本会校验证书指纹；指纹不一致时会拒绝写入（除非 `FORCE=1`）。

## 日常发版

推荐用 annotated tag（tag 注解会作为更新说明）：

```bash
git tag -a v0.31 -m "· 修复……
· 优化……"
git push origin v0.31
```

或者 Actions → **Release** → Run workflow：

- `version`：`0.31`（不要带 `v`）
- `notes`：更新说明（可留空）
- `publish_legacy_master`：仅首次迁移 v0.30 时勾选，日常发版不要勾

说明取值优先级：手动输入 → `release-notes/v<版本>.md` → annotated tag 注解 → 默认文本。

## 首次迁移（v0.30）

1. 完成上面的 secrets 配置。
2. 推送本 workflow 到 master。
3. Actions → Release → Run workflow：
   - `version` = `0.30`
   - 填写更新说明
   - 勾选 `publish_legacy_master`
4. 完成后确认：
   - GitHub Releases 有 `v0.30` + `litreader-v0.30.apk`
   - `release` 分支有 `litreader-v0.30.apk` 和 `latest.json`
   - master 根目录有 `litreader-v0.30.apk`，`latest.json` 为 `0.30.0`
5. 删除 master 上 v0.25–v0.29 的 APK（历史仍在 git 中），**保留 v0.30 过渡包**。
6. 以后发版不再勾选 `publish_legacy_master`，master 的 `latest.json` 保持 0.30 不动。

## 版本号规则

- tag：`vX.Y` 或 `vX.Y.Z`，例如 `v0.30`、`v0.30.1`
- APK 文件名：`litreader-vX.Y.apk` / `litreader-vX.Y.Z.apk`
- Android `versionName`：与 tag 一致（`0.30` 或 `0.30.1`）
- Android `versionCode`：`major * 10000 + minor * 100 + patch`（0.30 → 3000），只需单调递增
- `latest.json` 的 `versionName`：统一三段式（0.30 → `0.30.0`）
- CI 会拒绝 `versionCode` 不高于 master `latest.json` 的版本，防止误发旧 tag；
  需要重发同一版本时用补丁号（0.30.1），或先回退 master 的 `latest.json`。

本地构建默认取最近可达的 `v*` tag 作为版本；CI 用 `-PversionName` / `-PversionCode` 覆盖。
发版不再需要手改 `build.gradle` 或 `Constants.kt`。

## 本地构建

签名 release：

```bash
cd litreader
LITREADER_KEYSTORE_FILE="$HOME/.android/debug.keystore" \
LITREADER_KEYSTORE_PASSWORD=android \
LITREADER_KEY_ALIAS=androiddebugkey \
LITREADER_KEY_PASSWORD=android \
./gradlew :app:assembleRelease -PversionName=0.30 -PversionCode=3000
```

不设置 `LITREADER_KEYSTORE_FILE` 时产物为 unsigned（与改造前一致），适合只做编译检查。

Windows Git Bash 下不要直接调用 `gradlew.bat`（`-PversionName=...` 会被拆坏成任务名），
用 Unix wrapper 并显式给 POSIX 形式的 `JAVA_HOME`：

```bash
JAVA_HOME='/c/Program Files/Android/Android Studio/jbr' \
LITREADER_KEYSTORE_FILE='C:/Users/<you>/.android/debug.keystore' \
LITREADER_KEYSTORE_PASSWORD=android \
LITREADER_KEY_ALIAS=androiddebugkey \
LITREADER_KEY_PASSWORD=android \
./gradlew :app:assembleRelease -PversionName=0.30 -PversionCode=3000
```

## 更新通道与排障

- App 读 `release` 分支的 `latest.json`；下载 APK 依次尝试 jsDelivr 三节点 → raw → GitHub Release 附件 → master（迁移期）。
- `latest.json` 新增 `sha256`，下载完成后校验，不匹配会删除文件并提示重试。
- 如果 CI 提示缺少 secret：重新运行 `scripts/setup-github-secrets.sh`。
- 如果签名证书不匹配：CI 会在 Collect artifact 步骤直接失败，并打印实际证书指纹。
- 如果 `Publish release branch` 或 master push 被拒绝：检查 Settings → Actions → General 的
  Workflow permissions 是否为 Read and write，以及 Rulesets 是否保护了 `master` / `release` 分支。
- workflow 必须先存在于 master，打 tag 才会按新流程构建（tag 指向旧提交时不会带上本 workflow）。
- 如果 jsDelivr 暂时缓存旧 `latest.json`：CI 已调用 purge；raw 通道通常几分钟内刷新。
- 重新运行同一版本的 workflow 会重建 APK 并覆盖 Release 附件、`release` 分支和 **master 过渡包（如果勾选）**；SHA-256 会更新。
