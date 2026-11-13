#!/usr/bin/env bash
# 把本机 Android debug keystore 写入 GitHub Actions secrets。
# 历史版本（v0.25–v0.29）都由这把 key 签名，换 key 会导致老用户无法覆盖安装。
#
# 用法：
#   ./scripts/setup-github-secrets.sh [owner/repo]
# 可用环境变量覆盖默认值：
#   LITREADER_KEYSTORE / LITREADER_KEYSTORE_PASSWORD / LITREADER_KEY_ALIAS / LITREADER_KEY_PASSWORD
set -euo pipefail

REPO="${1:-wacilimonster-source/wxds}"
KEYSTORE="${LITREADER_KEYSTORE:-$HOME/.android/debug.keystore}"
STORE_PASS="${LITREADER_KEYSTORE_PASSWORD:-android}"
KEY_ALIAS="${LITREADER_KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${LITREADER_KEY_PASSWORD:-android}"
EXPECTED_SHA256="E4:36:A5:86:CD:E5:71:92:9D:01:2C:96:4D:02:A1:CC:A4:56:32:AB:CD:B6:62:74:5B:73:4D:DF:DB:27:4F:06"

command -v gh >/dev/null 2>&1 || { echo "需要 GitHub CLI: https://cli.github.com/"; exit 1; }
command -v keytool >/dev/null 2>&1 || { echo "找不到 keytool（需要 JDK 17）"; exit 1; }
[ -f "$KEYSTORE" ] || { echo "找不到 keystore: $KEYSTORE"; exit 1; }

if ! gh auth status >/dev/null 2>&1; then
  echo "还没登录 GitHub CLI，先执行 gh auth login"
  gh auth login
fi

# 注意：Git Bash 的 MSYS 会把含 “:/” 的参数当路径改写，这里用 awk 取最后一列，避免 sed 正则被破坏
ACTUAL_SHA256="$(keytool -J-Duser.language=en -list -keystore "$KEYSTORE" -storepass "$STORE_PASS" 2>/dev/null \
  | awk '/SHA-256/ {print $NF}' | head -1 | tr -d '\r' | tr '[:lower:]' '[:upper:]')"
if [ -n "$ACTUAL_SHA256" ] && [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
  echo "证书指纹不匹配！"
  echo "  期望: $EXPECTED_SHA256（v0.29 及历史版本）"
  echo "  实际: $ACTUAL_SHA256"
  echo "继续会导致老用户无法覆盖安装。确认仍要继续请设置 FORCE=1。"
  [ "${FORCE:-0}" = "1" ] || exit 1
fi

B64="$(base64 < "$KEYSTORE" | tr -d '\r\n')"
printf '%s' "$B64" | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$STORE_PASS" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$KEY_ALIAS" | gh secret set ANDROID_KEY_ALIAS --repo "$REPO"
printf '%s' "$KEY_PASS" | gh secret set ANDROID_KEY_PASSWORD --repo "$REPO"

echo "已写入 $REPO 的 4 个 secrets"
echo "keystore: $KEYSTORE"
echo "证书 SHA-256: ${ACTUAL_SHA256:-未知}"
